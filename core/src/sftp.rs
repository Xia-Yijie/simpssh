//! Minimal SFTP client over russh.
//!
//! Owns its own SSH connection and tokio runtime so that opening a Files tab
//! is independent from any open shell session. The FFI surface is sync;
//! internally we `block_on` an actor task. This trades a tiny bit of latency
//! for very simple Kotlin-side ergonomics (no async-FFI gymnastics).

use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client::{self, Handler};
use russh::keys::ssh_key::PublicKey;
use russh_sftp::client::SftpSession as SftpInner;
use russh_sftp::protocol::{FileType, OpenFlags};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt, SeekFrom};
use tokio::runtime::Runtime;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SftpError {
    #[error("connect failed: {msg}")]
    Connect { msg: String },
    #[error("auth failed: {msg}")]
    Auth { msg: String },
    #[error("subsystem failed: {msg}")]
    Subsystem { msg: String },
    #[error("io: {msg}")]
    Io { msg: String },
}

fn io_err(e: impl std::fmt::Display) -> SftpError { SftpError::Io { msg: e.to_string() } }

#[derive(Clone, Debug, uniffi::Record)]
pub struct DirEntry {
    pub name: String,
    pub path: String,
    pub size: u64,
    /// Seconds since unix epoch, 0 if unknown.
    pub mtime: u64,
    pub is_dir: bool,
    pub is_link: bool,
    /// Octal permission bits (e.g. 0o644). 0 if unknown.
    pub mode: u32,
}

struct AcceptAny;

impl Handler for AcceptAny {
    type Error = russh::Error;
    async fn check_server_key(
        &mut self,
        _server_public_key: &PublicKey,
    ) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

#[derive(uniffi::Object)]
pub struct SftpSession {
    runtime: Runtime,
    sftp: Arc<SftpInner>,
    _client: Arc<Mutex<Option<client::Handle<AcceptAny>>>>,
}

#[uniffi::export]
impl SftpSession {
    /// Open an SFTP subsystem on a fresh SSH connection.
    #[uniffi::constructor]
    pub fn connect_password(
        host: String,
        port: u16,
        user: String,
        password: String,
    ) -> Result<Arc<Self>, SftpError> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| SftpError::Connect { msg: e.to_string() })?;

        let (sftp, client) = rt.block_on(async {
            let config = Arc::new(client::Config {
                inactivity_timeout: Some(Duration::from_secs(600)),
                ..Default::default()
            });
            let mut session = client::connect(config, (host.as_str(), port), AcceptAny)
                .await
                .map_err(|e| SftpError::Connect { msg: e.to_string() })?;

            let authed = session
                .authenticate_password(&user, &password)
                .await
                .map_err(|e| SftpError::Auth { msg: e.to_string() })?;
            if !authed.success() {
                return Err(SftpError::Auth { msg: "password rejected".into() });
            }

            let channel = session
                .channel_open_session()
                .await
                .map_err(|e| SftpError::Subsystem { msg: e.to_string() })?;

            channel
                .request_subsystem(true, "sftp")
                .await
                .map_err(|e| SftpError::Subsystem { msg: e.to_string() })?;

            let sftp = SftpInner::new(channel.into_stream())
                .await
                .map_err(|e| SftpError::Subsystem { msg: e.to_string() })?;
            Ok::<_, SftpError>((Arc::new(sftp), Arc::new(Mutex::new(Some(session)))))
        })?;

        Ok(Arc::new(Self { runtime: rt, sftp, _client: client }))
    }

    /// List directory entries. Implicit "." / ".." are not included.
    pub fn list_dir(&self, path: String) -> Result<Vec<DirEntry>, SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move {
            let entries = sftp.read_dir(&path).await.map_err(io_err)?;
            let base = path.trim_end_matches('/');
            let mut out: Vec<DirEntry> = Vec::new();
            for e in entries {
                let name = e.file_name();
                if name == "." || name == ".." { continue; }
                let attrs = e.metadata();
                let kind = attrs.file_type();
                out.push(DirEntry {
                    name: name.clone(),
                    path: if base.is_empty() { format!("/{name}") } else { format!("{base}/{name}") },
                    size: attrs.size.unwrap_or(0),
                    mtime: attrs.mtime.unwrap_or(0) as u64,
                    is_dir: kind == FileType::Dir,
                    is_link: kind == FileType::Symlink,
                    mode: attrs.permissions.unwrap_or(0),
                });
            }
            out.sort_by(|a, b| match (a.is_dir, b.is_dir) {
                (true, false) => std::cmp::Ordering::Less,
                (false, true) => std::cmp::Ordering::Greater,
                _ => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
            });
            Ok(out)
        })
    }

    pub fn stat(&self, path: String) -> Result<DirEntry, SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move {
            let attrs = sftp.metadata(&path).await.map_err(io_err)?;
            let kind = attrs.file_type();
            let name = path.rsplit('/').next().unwrap_or("").to_string();
            Ok(DirEntry {
                name,
                path: path.clone(),
                size: attrs.size.unwrap_or(0),
                mtime: attrs.mtime.unwrap_or(0) as u64,
                is_dir: kind == FileType::Dir,
                is_link: kind == FileType::Symlink,
                mode: attrs.permissions.unwrap_or(0),
            })
        })
    }

    /// Read a slice of a file. Use `offset=0, len=cap` for "whole file up to cap".
    /// Caller decides the cap to avoid unbounded reads.
    pub fn read_file(&self, path: String, offset: u64, len: u32) -> Result<Vec<u8>, SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move {
            let mut f = sftp.open_with_flags(&path, OpenFlags::READ).await.map_err(io_err)?;
            f.seek(SeekFrom::Start(offset)).await.map_err(io_err)?;
            let mut buf = vec![0u8; len as usize];
            let mut total = 0usize;
            while total < buf.len() {
                let n = f.read(&mut buf[total..]).await.map_err(io_err)?;
                if n == 0 { break; }
                total += n;
            }
            buf.truncate(total);
            Ok(buf)
        })
    }

    /// Overwrite (or create) a remote file with `bytes`.
    pub fn write_file(&self, path: String, bytes: Vec<u8>) -> Result<(), SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move {
            let mut f = sftp
                .open_with_flags(
                    &path,
                    OpenFlags::WRITE | OpenFlags::CREATE | OpenFlags::TRUNCATE,
                )
                .await
                .map_err(io_err)?;
            f.write_all(&bytes).await.map_err(io_err)?;
            f.shutdown().await.map_err(io_err)?;
            Ok(())
        })
    }

    pub fn rename(&self, from: String, to: String) -> Result<(), SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move {
            sftp.rename(&from, &to).await.map_err(io_err)
        })
    }

    pub fn delete_file(&self, path: String) -> Result<(), SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move { sftp.remove_file(&path).await.map_err(io_err) })
    }

    pub fn delete_dir(&self, path: String) -> Result<(), SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move { sftp.remove_dir(&path).await.map_err(io_err) })
    }

    pub fn mkdir(&self, path: String) -> Result<(), SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move { sftp.create_dir(&path).await.map_err(io_err) })
    }

    /// Resolves the user's home directory by asking SFTP for the canonical
    /// representation of ".".
    pub fn home_dir(&self) -> Result<String, SftpError> {
        let sftp = self.sftp.clone();
        self.runtime.block_on(async move { sftp.canonicalize(".").await.map_err(io_err) })
    }

    pub fn disconnect(&self) {
        // Drop will close the channel; nothing extra needed for MVP.
    }
}
