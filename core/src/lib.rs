//! simpssh core — shared Rust library for Android/iOS native UIs.
//!
//! Exposes, via uniffi:
//!   * `TerminalView` — headless xterm state machine (alacritty_terminal).
//!   * `SshSession`   — interactive shell over russh.
//!   * `SftpSession`  — file browse / read / write over the SFTP subsystem.
//! UI wires them: shell bytes → TerminalView; file ops → SftpSession.

uniffi::setup_scaffolding!();

mod sftp;
mod ssh;
mod terminal;

pub use sftp::{DirEntry, SftpError, SftpSession};
pub use ssh::{SshError, SshSession};
pub use terminal::{CursorPos, TerminalView};
