use russh::client::Handler;
use russh::keys::ssh_key::PublicKey;

// MVP 标记:AcceptAny 等价于完全关闭 TOFU —— 对任何 host key 都返回 true。
// 这是未完成的功能占位,上线前必须替换为持久化的 known_hosts 风格指纹校验。
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
