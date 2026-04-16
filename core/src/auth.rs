//! Shared SSH client building blocks (handlers, etc.) used by both the
//! interactive shell session and the SFTP session.

use russh::client::Handler;
use russh::keys::ssh_key::PublicKey;

/// Accept-any host-key handler. MVP only — replace with a known_hosts
/// style check before this ships.
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
