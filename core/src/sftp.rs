use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client;
use russh_sftp::client::SftpSession as SftpInner;
use russh_sftp::protocol::{FileType, OpenFlags};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt, SeekFrom};
use tokio::runtime::Runtime;

use crate::auth::AcceptAny;

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

// 只能做字符串 substring 匹配:russh-sftp 把底层断开全部裹成通用 io::Error,
// 没有暴露类型化的 "session gone" variant,没法按 enum 区分。只匹配 Io/Subsystem
// 是为了避免在凭据错误(Auth)等业务错误上误触发重连。
fn looks_disconnected(err: &SftpError) -> bool {
    let msg = match err {
        SftpError::Io { msg } => msg.as_str(),
        SftpError::Subsystem { msg } => msg.as_str(),
        _ => return false,
    };
    let lc = msg.to_ascii_lowercase();
    lc.contains("session closed")
        || lc.contains("channel closed")
        || lc.contains("eof")
        || lc.contains("broken pipe")
        || lc.contains("connection reset")
        || lc.contains("not connected")
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct DirEntry {
    pub name: String,
    pub path: String,
    pub size: u64,
    pub mtime: u64,
    pub is_dir: bool,
    pub is_link: bool,
    pub mode: u32,
}

#[derive(Clone)]
struct ConnectArgs {
    host: String,
    port: u16,
    user: String,
    password: String,
}

async fn establish(args: &ConnectArgs) -> Result<(Arc<SftpInner>, client::Handle<AcceptAny>), SftpError> {
    let config = Arc::new(client::Config {
        inactivity_timeout: Some(Duration::from_secs(600)),
        ..Default::default()
    });
    let mut session = client::connect(config, (args.host.as_str(), args.port), AcceptAny)
        .await
        .map_err(|e| SftpError::Connect { msg: e.to_string() })?;
    let authed = session
        .authenticate_password(&args.user, &args.password)
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
    Ok((Arc::new(sftp), session))
}

#[derive(uniffi::Object)]
pub struct SftpSession {
    runtime: Runtime,
    sftp: Mutex<Arc<SftpInner>>,
    client: Mutex<Option<client::Handle<AcceptAny>>>,
    creds: ConnectArgs,
}

impl SftpSession {
    fn reconnect(&self) -> Result<(), SftpError> {
        let (new_sftp, newclient) = self.runtime.block_on(establish(&self.creds))?;
        *self.sftp.lock().unwrap() = new_sftp;
        *self.client.lock().unwrap() = Some(newclient);
        Ok(())
    }

    // 这里故意用 Fn 而不是 FnOnce,因为断连时要能第二次调用同一个闭包重跑操作。
    // 代价是:调用方捕获的 String/Vec 必须在闭包内部 clone,不能直接 move 消费,
    // 否则第一次调用就把值吃掉,重试时 borrow checker 会报 already moved。
    fn retry_once<T, F>(&self, op: F) -> Result<T, SftpError>
    where
        F: Fn() -> Result<T, SftpError>,
    {
        match op() {
            Err(e) if looks_disconnected(&e) => {
                self.reconnect()?;
                op()
            }
            r => r,
        }
    }

    fn current_sftp(&self) -> Arc<SftpInner> { self.sftp.lock().unwrap().clone() }
}

#[uniffi::export]
impl SftpSession {
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
        let creds = ConnectArgs { host, port, user, password };
        let (sftp, client) = rt.block_on(establish(&creds))?;
        Ok(Arc::new(Self {
            runtime: rt,
            sftp: Mutex::new(sftp),
            client: Mutex::new(Some(client)),
            creds,
        }))
    }

    pub fn list_dir(&self, path: String) -> Result<Vec<DirEntry>, SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
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
        })
    }

    pub fn stat(&self, path: String) -> Result<DirEntry, SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
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
        })
    }

    pub fn read_file(&self, path: String, offset: u64, len: u32) -> Result<Vec<u8>, SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
            self.runtime.block_on(async move {
                let mut f = sftp.open_with_flags(&path, OpenFlags::READ).await.map_err(io_err)?;
                // 优化:offset==0 时不发 seek,省掉一次 SFTP round-trip。
                // 整文件读取(预览/下载常见路径)就是 offset=0,这里直接从头读。
                if offset > 0 {
                    f.seek(SeekFrom::Start(offset)).await.map_err(io_err)?;
                }
                let mut buf = Vec::new();
                AsyncReadExt::take(&mut f, len as u64)
                    .read_to_end(&mut buf)
                    .await
                    .map_err(io_err)?;
                Ok(buf)
            })
        })
    }

    pub fn write_file(&self, path: String, bytes: Vec<u8>) -> Result<(), SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
            let bytes = bytes.clone();
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
        })
    }

    pub fn rename(&self, from: String, to: String) -> Result<(), SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let from = from.clone();
            let to = to.clone();
            self.runtime.block_on(async move { sftp.rename(&from, &to).await.map_err(io_err) })
        })
    }

    pub fn delete_file(&self, path: String) -> Result<(), SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
            self.runtime.block_on(async move { sftp.remove_file(&path).await.map_err(io_err) })
        })
    }

    pub fn delete_dir(&self, path: String) -> Result<(), SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
            self.runtime.block_on(async move { sftp.remove_dir(&path).await.map_err(io_err) })
        })
    }

    pub fn mkdir(&self, path: String) -> Result<(), SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            let path = path.clone();
            self.runtime.block_on(async move { sftp.create_dir(&path).await.map_err(io_err) })
        })
    }

    pub fn home_dir(&self) -> Result<String, SftpError> {
        self.retry_once(|| {
            let sftp = self.current_sftp();
            self.runtime.block_on(async move { sftp.canonicalize(".").await.map_err(io_err) })
        })
    }

    pub fn disconnect(&self) {
    }
}
