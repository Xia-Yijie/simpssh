uniffi::setup_scaffolding!();

mod auth;
mod forwarding;
mod sftp;
mod ssh;
mod terminal;

pub use sftp::{DirEntry, SftpError, SftpSession};
pub use ssh::{SshError, SshSession};
pub use terminal::{CursorPos, StyleSpan, StyledRow, TerminalView};

#[uniffi::export]
pub fn init_native_logging() {
    #[cfg(target_os = "android")]
    {
        use std::sync::Once;
        // 用 Once 防止 panic hook 重复注册：set_hook 会叠加,重复调用会让同一条 panic 被记录多次。
        static INIT: Once = Once::new();
        INIT.call_once(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_tag("simpssh")
                    .with_max_level(log::LevelFilter::Info),
            );
            let default_hook = std::panic::take_hook();
            std::panic::set_hook(Box::new(move |info| {
                // 用户设备上没有 RUST_BACKTRACE 环境变量,必须用 force_capture 才能拿到栈。
                let backtrace = std::backtrace::Backtrace::force_capture();
                log::error!("rust panic: {}\n{}", info, backtrace);
                default_hook(info);
            }));
        });
    }
}
