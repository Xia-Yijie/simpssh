use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client;
use russh::{ChannelMsg, Disconnect};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use crate::auth::SessionHandler;
use crate::forwarding::{
    new_stats, snapshot, start_local_forward, start_remote_forward, ForwardEntry, ForwardStats,
    RemoteForwardMap, LOOPBACK,
};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SshError {
    #[error("connect failed: {msg}")]
    Connect { msg: String },
    #[error("auth failed: {msg}")]
    Auth { msg: String },
    #[error("shell failed: {msg}")]
    Shell { msg: String },
    #[error("forward failed: {msg}")]
    Forward { msg: String },
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
    // runtime 在 SessionInner 的字段末尾,按 Rust 字段析构顺序(反向)最先被 drop:
    // forwards / session 里的 tokio 任务随之一并取消,避免监听 socket 泄漏占端口。
    cmd_tx: mpsc::UnboundedSender<ActorCmd>,
    rx: Mutex<mpsc::UnboundedReceiver<Vec<u8>>>,
    closed: Arc<AtomicBool>,
    session: Arc<client::Handle<SessionHandler>>,
    remote_forwards: Arc<Mutex<RemoteForwardMap>>,
    forwards: Arc<Mutex<HashMap<String, ForwardEntry>>>,
    runtime: Runtime,
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
        let remote_forwards: Arc<Mutex<RemoteForwardMap>> =
            Arc::new(Mutex::new(HashMap::new()));

        let session_arc = rt.block_on(async {
            // 不设 inactivity_timeout,改用 keepalive:30s 发一次,允许丢 3 次,
            // 累计 ~90s 才判死。移动网络短时断连很常见,这个窗口可以扛住地铁/电梯
            // 级别的抖动而不误杀会话,又不会让用户在真正掉线时等太久。
            let config = Arc::new(client::Config {
                keepalive_interval: Some(Duration::from_secs(30)),
                keepalive_max: 3,
                ..Default::default()
            });
            let handler = SessionHandler::new(remote_forwards.clone());
            let mut session = client::connect(config, (host.as_str(), port), handler)
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

            let session_arc = Arc::new(session);
            tokio::spawn(run_actor(
                session_arc.clone(),
                channel,
                cmd_rx,
                bytes_tx,
                closed_actor,
            ));
            Ok::<Arc<client::Handle<SessionHandler>>, SshError>(session_arc)
        })?;

        Ok(Arc::new(Self {
            inner: SessionInner {
                cmd_tx,
                rx: Mutex::new(bytes_rx),
                closed,
                session: session_arc,
                remote_forwards,
                forwards: Arc::new(Mutex::new(HashMap::new())),
                runtime: rt,
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
        // 无数据时阻塞到 timeout_ms 或收到第一块;之前手写 10ms 忙等每个空 read 会醒
        // 20 次,现在走 tokio 定时器,闲置时零唤醒。拿到第一块后再 try_recv 榨干队列,
        // 减少 JNI 往返次数。
        let wait = Duration::from_millis(timeout_ms as u64);
        let first = self.inner.runtime.block_on(async {
            tokio::time::timeout(wait, rx.recv()).await.ok().flatten()
        });
        if let Some(c) = first {
            out.extend_from_slice(&c);
            while let Ok(more) = rx.try_recv() {
                out.extend_from_slice(&more);
            }
        }
        out
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), SshError> {
        self.inner
            .cmd_tx
            .send(ActorCmd::Resize { cols, rows })
            .map_err(|_| SshError::Closed)
    }

    // 本地端口转发:设备本地 127.0.0.1:bind_port 监听,来连接经 SSH direct-tcpip 打到
    // 服务端的 127.0.0.1:target_port。id 由调用方维护,相同 id 重复调用会顶掉旧条目。
    pub fn start_local_forward(
        &self,
        id: String,
        bind_port: u16,
        target_port: u16,
    ) -> Result<(), SshError> {
        let session = self.inner.session.clone();
        let stats = new_stats();
        let task_stats = stats.clone();
        let task = self
            .inner
            .runtime
            .block_on(async move {
                start_local_forward(session, bind_port, target_port, task_stats).await
            })
            .map_err(|e| SshError::Forward { msg: e.to_string() })?;
        let mut map = self.inner.forwards.lock().unwrap();
        map.insert(id, ForwardEntry::Local { task, stats });
        Ok(())
    }

    // 远端端口转发:向服务端 tcpip_forward 注册 127.0.0.1:bind_port,
    // 服务端接受连接后推过来 forwarded-tcpip channel(见 SessionHandler),
    // 连到设备本地 127.0.0.1:target_port。返回服务端实际绑定端口(请求 0 时由服务端分配)。
    pub fn start_remote_forward(
        &self,
        id: String,
        bind_port: u16,
        target_port: u16,
    ) -> Result<u16, SshError> {
        let session = self.inner.session.clone();
        let remote_forwards = self.inner.remote_forwards.clone();
        let stats = new_stats();
        let stats_task = stats.clone();
        let effective = self
            .inner
            .runtime
            .block_on(async move {
                start_remote_forward(
                    session,
                    remote_forwards,
                    bind_port,
                    target_port,
                    stats_task,
                )
                .await
            })
            .map_err(|e| SshError::Forward { msg: e.to_string() })?;
        let mut map = self.inner.forwards.lock().unwrap();
        map.insert(
            id,
            ForwardEntry::Remote {
                bind_port: effective as u32,
                stats,
            },
        );
        Ok(effective)
    }

    pub fn stop_forward(&self, id: String) {
        let entry = self.inner.forwards.lock().unwrap().remove(&id);
        let Some(entry) = entry else { return };
        if let ForwardEntry::Remote { bind_port, .. } = &entry {
            // 同步从 remote_forwards 移除:若调用方随即用同 id 重新上架,避免后面 async
            // cancel 的 remove 误删新注册。cancel_tcpip_forward 本身继续异步跑。
            let port = *bind_port;
            self.inner.remote_forwards.lock().unwrap().remove(&port);
            let session = self.inner.session.clone();
            self.inner.runtime.spawn(async move {
                let _ = session.cancel_tcpip_forward(LOOPBACK, port).await;
            });
        }
        // entry 走出作用域,Local::task 被 abort。
    }

    // UI 轮询用:读出当前规则的统计 + 最近一条事件文字。未注册或已停返回 None。
    pub fn get_forward_stats(&self, id: String) -> Option<ForwardStats> {
        let map = self.inner.forwards.lock().unwrap();
        map.get(&id).map(|e| snapshot(&e.stats()))
    }

    pub fn disconnect(&self) {
        // 先清转发:ForwardEntry::Drop 会 abort TcpListener 任务,立刻释放本地端口。
        // 否则老监听还活着,下一次重连 bind 同一端口会 EADDRINUSE。
        self.inner.forwards.lock().unwrap().clear();
        let _ = self.inner.cmd_tx.send(ActorCmd::Close);
    }
}

async fn run_actor(
    session: Arc<client::Handle<SessionHandler>>,
    mut channel: russh::Channel<client::Msg>,
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
