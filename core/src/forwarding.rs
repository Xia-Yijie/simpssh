use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client::Handle;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

use crate::auth::SessionHandler;

#[derive(uniffi::Record, Clone, Debug)]
pub struct ForwardStats {
    pub accepted: u64,
    pub failed: u64,
    pub last_event: Option<String>,
}

pub(crate) struct StatsCore {
    pub accepted: u64,
    pub failed: u64,
    pub last_event: Option<String>,
}

impl StatsCore {
    fn new() -> Self { Self { accepted: 0, failed: 0, last_event: None } }
    pub(crate) fn accept(&mut self, msg: String) { self.accepted += 1; self.last_event = Some(msg); }
    pub(crate) fn fail(&mut self, msg: String) { self.failed += 1; self.last_event = Some(msg); }
    pub(crate) fn note(&mut self, msg: String) { self.last_event = Some(msg); }
    fn snapshot(&self) -> ForwardStats {
        ForwardStats {
            accepted: self.accepted,
            failed: self.failed,
            last_event: self.last_event.clone(),
        }
    }
}

pub(crate) type Stats = Arc<Mutex<StatsCore>>;
pub(crate) fn new_stats() -> Stats { Arc::new(Mutex::new(StatsCore::new())) }
pub(crate) fn snapshot(s: &Stats) -> ForwardStats { s.lock().unwrap().snapshot() }

// Remote forward 注册表:connected_port -> (本机目标 port, 该规则的 stats)。
// SessionHandler 收到 forwarded-tcpip 时查表,拿到 stats 记录进出事件。本机目标地址统一 127.0.0.1,不入表。
pub(crate) type RemoteForwardMap = HashMap<u32, (u16, Stats)>;

pub(crate) enum ForwardEntry {
    Local { task: JoinHandle<()>, stats: Stats },
    Remote { bind_port: u32, stats: Stats },
}

impl ForwardEntry {
    pub fn stats(&self) -> Stats {
        match self {
            ForwardEntry::Local { stats, .. } => stats.clone(),
            ForwardEntry::Remote { stats, .. } => stats.clone(),
        }
    }
}

impl Drop for ForwardEntry {
    fn drop(&mut self) {
        if let ForwardEntry::Local { task, .. } = self {
            task.abort();
        }
    }
}

// 两侧地址对用户隐藏,都走 loopback;未来要"高级模式"时再把地址做成参数。
pub(crate) const LOOPBACK: &str = "127.0.0.1";

pub(crate) async fn start_local_forward(
    session: Arc<Handle<SessionHandler>>,
    bind_port: u16,
    remote_port: u16,
    stats: Stats,
) -> std::io::Result<JoinHandle<()>> {
    let listener = TcpListener::bind((LOOPBACK, bind_port)).await?;
    stats.lock().unwrap().note(format!("监听 {LOOPBACK}:{bind_port}"));
    let task_stats = stats.clone();
    let task = tokio::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((tcp, peer)) => {
                    let session = session.clone();
                    let stats_pump = task_stats.clone();
                    stats_pump.lock().unwrap().accept(format!("入连 {peer}"));
                    tokio::spawn(async move {
                        match session
                            .channel_open_direct_tcpip(
                                LOOPBACK,
                                remote_port as u32,
                                peer.ip().to_string(),
                                peer.port() as u32,
                            )
                            .await
                        {
                            Ok(ch) => {
                                stats_pump.lock().unwrap().note(
                                    format!("已连到 {LOOPBACK}:{remote_port}"));
                                let mut ssh = ch.into_stream();
                                let mut tcp = tcp;
                                if let Err(e) =
                                    tokio::io::copy_bidirectional(&mut ssh, &mut tcp).await
                                {
                                    stats_pump.lock().unwrap().note(
                                        format!("连接结束: {e}"));
                                }
                            }
                            Err(e) => {
                                stats_pump.lock().unwrap().fail(
                                    format!("打通道失败: {e}"));
                            }
                        }
                    });
                }
                Err(e) => {
                    task_stats.lock().unwrap().fail(format!("accept 失败: {e}"));
                    tokio::time::sleep(Duration::from_millis(200)).await;
                }
            }
        }
    });
    Ok(task)
}

pub(crate) async fn start_remote_forward(
    session: Arc<Handle<SessionHandler>>,
    remote_forwards: Arc<Mutex<RemoteForwardMap>>,
    bind_port: u16,
    local_port: u16,
    stats: Stats,
) -> Result<u16, russh::Error> {
    // russh 约定:port == 0 时服务端返回实际分配的端口;port != 0 时返回 0 表示按请求绑定。
    let returned = session.tcpip_forward(LOOPBACK, bind_port as u32).await?;
    let effective: u32 = if returned == 0 { bind_port as u32 } else { returned };
    remote_forwards
        .lock()
        .unwrap()
        .insert(effective, (local_port, stats.clone()));
    stats.lock().unwrap().note(format!("服务端 {LOOPBACK}:{effective} 已监听"));
    Ok(effective as u16)
}
