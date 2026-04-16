//! Minimal interactive SSH session built on `russh`.
//!
//! Design: the FFI-facing `SshSession` is sync. Internally an actor task owns
//! the russh channel and communicates through two tokio mpsc queues. `read`
//! blocks up to a configurable timeout; `write` never blocks. Concurrency
//! comes from a per-session tokio runtime we spin up in `connect_password`.
//!
//! Host-key verification is TOFU-OFF for this MVP (always accepts). That will
//! be replaced with a known_hosts-style check before anything ships.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client::{self, Handler};
use russh::keys::ssh_key::PublicKey;
use russh::{ChannelId, ChannelMsg, Disconnect};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SshError {
    #[error("connect failed: {msg}")]
    Connect { msg: String },
    #[error("auth failed: {msg}")]
    Auth { msg: String },
    #[error("shell failed: {msg}")]
    Shell { msg: String },
    #[error("session closed")]
    Closed,
}

impl From<russh::Error> for SshError {
    fn from(e: russh::Error) -> Self {
        SshError::Connect { msg: e.to_string() }
    }
}

/// Accept-any host-key handler. MVP only.
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

enum ActorCmd {
    Write(Vec<u8>),
    Resize { cols: u16, rows: u16 },
    Close,
}

struct SessionInner {
    _runtime: Runtime,
    cmd_tx: mpsc::UnboundedSender<ActorCmd>,
    rx: Mutex<mpsc::UnboundedReceiver<Vec<u8>>>,
}

#[derive(uniffi::Object)]
pub struct SshSession {
    inner: SessionInner,
}

#[uniffi::export]
impl SshSession {
    /// Connect, authenticate with a password, and open an interactive shell
    /// with a PTY of the given size. Blocks until the shell is ready.
    #[uniffi::constructor]
    pub fn connect_password(
        host: String,
        port: u16,
        user: String,
        password: String,
        cols: u16,
        rows: u16,
    ) -> Result<Arc<Self>, SshError> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| SshError::Connect { msg: e.to_string() })?;

        let (bytes_tx, bytes_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel::<ActorCmd>();

        rt.block_on(async {
            let config = Arc::new(client::Config {
                inactivity_timeout: Some(Duration::from_secs(600)),
                ..Default::default()
            });
            let mut session = client::connect(config, (host.as_str(), port), AcceptAny)
                .await
                .map_err(|e| SshError::Connect { msg: e.to_string() })?;

            let authed = session
                .authenticate_password(&user, &password)
                .await
                .map_err(|e| SshError::Auth { msg: e.to_string() })?;
            if !authed.success() {
                return Err(SshError::Auth { msg: "password rejected".into() });
            }

            let channel = session
                .channel_open_session()
                .await
                .map_err(|e| SshError::Shell { msg: e.to_string() })?;

            channel
                .request_pty(false, "xterm-256color", cols as u32, rows as u32, 0, 0, &[])
                .await
                .map_err(|e| SshError::Shell { msg: e.to_string() })?;

            channel
                .request_shell(false)
                .await
                .map_err(|e| SshError::Shell { msg: e.to_string() })?;

            let id: ChannelId = channel.id();
            tokio::spawn(run_actor(session, channel, id, cmd_rx, bytes_tx));
            Ok::<(), SshError>(())
        })?;

        Ok(Arc::new(Self {
            inner: SessionInner {
                _runtime: rt,
                cmd_tx,
                rx: Mutex::new(bytes_rx),
            },
        }))
    }

    /// Non-blocking enqueue of bytes to the remote shell's stdin.
    pub fn write(&self, bytes: Vec<u8>) -> Result<(), SshError> {
        self.inner
            .cmd_tx
            .send(ActorCmd::Write(bytes))
            .map_err(|_| SshError::Closed)
    }

    /// Drain any bytes received from the shell, waiting up to `timeout_ms`
    /// for the first chunk. Returns an empty vec on timeout or on close.
    pub fn read(&self, timeout_ms: u32) -> Vec<u8> {
        let mut rx = self.inner.rx.lock().unwrap();
        let mut out = Vec::new();
        while let Ok(chunk) = rx.try_recv() {
            out.extend_from_slice(&chunk);
        }
        if !out.is_empty() {
            return out;
        }
        // Bounded wait. Poll the mpsc directly to avoid holding a future across
        // another borrow of `rx`.
        let deadline = std::time::Instant::now() + Duration::from_millis(timeout_ms as u64);
        loop {
            match rx.try_recv() {
                Ok(c) => {
                    out.extend_from_slice(&c);
                    while let Ok(more) = rx.try_recv() {
                        out.extend_from_slice(&more);
                    }
                    return out;
                }
                Err(mpsc::error::TryRecvError::Empty) => {
                    if std::time::Instant::now() >= deadline { return out; }
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(mpsc::error::TryRecvError::Disconnected) => return out,
            }
        }
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), SshError> {
        self.inner
            .cmd_tx
            .send(ActorCmd::Resize { cols, rows })
            .map_err(|_| SshError::Closed)
    }

    pub fn disconnect(&self) {
        let _ = self.inner.cmd_tx.send(ActorCmd::Close);
    }
}

async fn run_actor(
    session: client::Handle<AcceptAny>,
    mut channel: russh::Channel<client::Msg>,
    _id: ChannelId,
    mut cmd_rx: mpsc::UnboundedReceiver<ActorCmd>,
    bytes_tx: mpsc::UnboundedSender<Vec<u8>>,
) {
    loop {
        tokio::select! {
            maybe_cmd = cmd_rx.recv() => {
                match maybe_cmd {
                    Some(ActorCmd::Write(buf)) => {
                        if channel.data(&buf[..]).await.is_err() { break; }
                    }
                    Some(ActorCmd::Resize { cols, rows }) => {
                        let _ = channel.window_change(cols as u32, rows as u32, 0, 0).await;
                    }
                    Some(ActorCmd::Close) | None => {
                        let _ = session.disconnect(Disconnect::ByApplication, "", "en").await;
                        break;
                    }
                }
            }
            maybe_msg = channel.wait() => {
                match maybe_msg {
                    Some(ChannelMsg::Data { data }) => {
                        if bytes_tx.send(data.to_vec()).is_err() { break; }
                    }
                    Some(ChannelMsg::ExtendedData { data, .. }) => {
                        if bytes_tx.send(data.to_vec()).is_err() { break; }
                    }
                    Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) | None => break,
                    _ => {}
                }
            }
        }
    }
}
