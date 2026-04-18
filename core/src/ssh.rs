//! 安全警告:本模块使用 AcceptAny 处理 host key,等于完全关闭 TOFU 校验,
//! 对任何中间人攻击都是开放的。这是 MVP 阶段的占位方案,上线前必须换成
//! known_hosts 风格的指纹校验,否则不得发布。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client;
use russh::{ChannelId, ChannelMsg, Disconnect};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use crate::auth::AcceptAny;

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

enum ActorCmd {
    Write(Vec<u8>),
    Resize { cols: u16, rows: u16 },
    Close,
}

struct SessionInner {
    _runtime: Runtime,
    cmd_tx: mpsc::UnboundedSender<ActorCmd>,
    rx: Mutex<mpsc::UnboundedReceiver<Vec<u8>>>,
    closed: Arc<AtomicBool>,
}

#[derive(uniffi::Object)]
pub struct SshSession {
    inner: SessionInner,
}

#[uniffi::export]
impl SshSession {
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
        let closed = Arc::new(AtomicBool::new(false));
        let closed_actor = closed.clone();

        rt.block_on(async {
            // 不设 inactivity_timeout,改用 keepalive:30s 发一次,允许丢 3 次,
            // 累计 ~90s 才判死。移动网络短时断连很常见,这个窗口可以扛住地铁/电梯
            // 级别的抖动而不误杀会话,又不会让用户在真正掉线时等太久。
            let config = Arc::new(client::Config {
                keepalive_interval: Some(Duration::from_secs(30)),
                keepalive_max: 3,
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

            // Workaround:很多服务端默认 locale 是 C/POSIX,此时 shell 会把非 ASCII
            // 字节以 \xXX 形式回显,中文会全部变成乱码。强制 UTF-8 locale 规避这个坑。
            for (name, value) in [
                ("LANG", "C.UTF-8"),
                ("LC_CTYPE", "C.UTF-8"),
                ("LC_ALL", "C.UTF-8"),
            ] {
                channel
                    .set_env(false, name, value)
                    .await
                    .map_err(|e| SshError::Shell { msg: e.to_string() })?;
            }

            channel
                .request_shell(false)
                .await
                .map_err(|e| SshError::Shell { msg: e.to_string() })?;

            let id: ChannelId = channel.id();
            tokio::spawn(run_actor(session, channel, id, cmd_rx, bytes_tx, closed_actor));
            Ok::<(), SshError>(())
        })?;

        Ok(Arc::new(Self {
            inner: SessionInner {
                _runtime: rt,
                cmd_tx,
                rx: Mutex::new(bytes_rx),
                closed,
            },
        }))
    }

    pub fn is_closed(&self) -> bool {
        self.inner.closed.load(Ordering::SeqCst)
    }

    pub fn write(&self, bytes: Vec<u8>) -> Result<(), SshError> {
        self.inner
            .cmd_tx
            .send(ActorCmd::Write(bytes))
            .map_err(|_| SshError::Closed)
    }

    pub fn read(&self, timeout_ms: u32) -> Vec<u8> {
        let mut rx = self.inner.rx.lock().unwrap();
        let mut out = Vec::new();
        while let Ok(chunk) = rx.try_recv() {
            out.extend_from_slice(&chunk);
        }
        if !out.is_empty() {
            return out;
        }
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
    closed: Arc<AtomicBool>,
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
    closed.store(true, Ordering::SeqCst);
}
