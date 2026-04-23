use std::sync::{Arc, Mutex};

use russh::client::{Handler, Msg, Session};
use russh::keys::ssh_key::PublicKey;
use russh::Channel;

use crate::forwarding::{RemoteForwardMap, LOOPBACK};

// SFTP 不需要 port forwarding 的 handler 回调,用这个最小 handler。
pub(crate) struct AcceptAny;

impl Handler for AcceptAny {
    type Error = russh::Error;
    async fn check_server_key(
        &mut self,
        _server_public_key: &PublicKey,
    ) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

pub(crate) struct SessionHandler {
    // server_channel_open_forwarded_tcpip 回调里用。每个被服务端接受的 tcpip_forward 注册
    // 一项:connected_port -> (本机目标 host, port, 该规则的 stats)。
    pub remote_forwards: Arc<Mutex<RemoteForwardMap>>,
}

impl SessionHandler {
    pub fn new(remote_forwards: Arc<Mutex<RemoteForwardMap>>) -> Self {
        Self { remote_forwards }
    }
}

impl Handler for SessionHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        _server_public_key: &PublicKey,
    ) -> Result<bool, Self::Error> {
        Ok(true)
    }

    // 远端转发:服务端收到指向被 tcpip_forward 注册过的端口的连接后,
    // 会在本 session 上开 "forwarded-tcpip" channel,带上 connected_port。
    // 我们根据注册表找到本地目标,起一个 task 把 channel 与本地 TcpStream 双向对接。
    async fn server_channel_open_forwarded_tcpip(
        &mut self,
        channel: Channel<Msg>,
        _connected_address: &str,
        connected_port: u32,
        originator_address: &str,
        originator_port: u32,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        let target = self.remote_forwards.lock().unwrap().get(&connected_port).cloned();
        let Some((port, stats)) = target else {
            log::warn!("forwarded-tcpip on unregistered port {connected_port}; dropping");
            return Ok(());
        };
        stats.lock().unwrap().accept(format!("入连 {originator_address}:{originator_port}"));
        tokio::spawn(async move {
            match tokio::net::TcpStream::connect((LOOPBACK, port)).await {
                Ok(mut tcp) => {
                    stats.lock().unwrap().note(format!("已连到 {LOOPBACK}:{port}"));
                    let mut stream = channel.into_stream();
                    if let Err(e) = tokio::io::copy_bidirectional(&mut stream, &mut tcp).await {
                        stats.lock().unwrap().note(format!("连接结束: {e}"));
                    }
                }
                Err(e) => {
                    stats.lock().unwrap().fail(format!("本地连接失败: {e}"));
                }
            }
        });
        Ok(())
    }
}
