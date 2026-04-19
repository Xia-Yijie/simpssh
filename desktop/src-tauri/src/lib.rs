use std::collections::HashMap;
use std::str;
use std::sync::{Arc, Mutex};
use std::thread;

use serde::{Deserialize, Serialize};
use simpssh_core::{DirEntry, SftpSession, SshSession};
use tauri::{AppHandle, Emitter, State};
use uuid::Uuid;

#[derive(Default)]
struct SessionStore {
    sessions: Arc<Mutex<HashMap<String, ManagedSession>>>,
}

struct ManagedSession {
    ssh: Arc<SshSession>,
    sftp: Arc<SftpSession>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConnectRequest {
    host: String,
    port: u16,
    user: String,
    password: String,
    cols: u16,
    rows: u16,
    init_script: Option<InitScriptRequest>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitScriptRequest {
    working_dir: String,
    content: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectReply {
    session_id: String,
    root_path: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DirEntryDto {
    name: String,
    path: String,
    size: u64,
    mtime: u64,
    is_dir: bool,
    is_link: bool,
    mode: u32,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SshOutputEvent {
    session_id: String,
    bytes: Vec<u8>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SessionMessageEvent {
    session_id: String,
    message: String,
}

#[tauri::command]
async fn connect_session(
    request: ConnectRequest,
    app: AppHandle,
    state: State<'_, SessionStore>,
) -> Result<ConnectReply, String> {
    let sessions = state.sessions.clone();

    tauri::async_runtime::spawn_blocking(move || {
        // SSH 和 SFTP 目前各自独立建连（两次 TCP + 握手 + 认证）。串行跑会叠加
        // 1-2s × 2 的延迟；并行发起，总延迟降到单次握手的时间。
        // TODO(core): 让 SftpSession 能复用 SshSession 的 transport，开一个
        // channel 而不是另起一条 SSH 连接，一次握手就够。
        let ssh_handle = {
            let host = request.host.clone();
            let user = request.user.clone();
            let password = request.password.clone();
            let port = request.port;
            let cols = request.cols;
            let rows = request.rows;
            thread::spawn(move || {
                SshSession::connect_password(host, port, user, password, cols, rows)
            })
        };
        let sftp_handle = {
            let host = request.host.clone();
            let user = request.user.clone();
            let password = request.password.clone();
            let port = request.port;
            thread::spawn(move || SftpSession::connect_password(host, port, user, password))
        };

        let ssh = ssh_handle
            .join()
            .map_err(|_| "ssh thread panicked".to_string())?
            .map_err(|error| error.to_string())?;
        let sftp = sftp_handle
            .join()
            .map_err(|_| "sftp thread panicked".to_string())?
            .map_err(|error| error.to_string())?;

        let home_dir = sftp.home_dir().unwrap_or_else(|_| "/".to_string());
        let requested_root = request
            .init_script
            .as_ref()
            .map(|script| resolve_root_path(&home_dir, &script.working_dir))
            .unwrap_or_else(|| home_dir.clone());
        let root_path = if sftp.stat(requested_root.clone()).is_ok() {
            requested_root
        } else {
            home_dir.clone()
        };
        let session_id = Uuid::new_v4().to_string();

        sessions.lock().unwrap().insert(
            session_id.clone(),
            ManagedSession {
                ssh: ssh.clone(),
                sftp,
            },
        );

        spawn_reader(app, sessions.clone(), session_id.clone(), ssh);

        if let Some(script) = request.init_script.as_ref() {
            run_init_script(
                sessions
                    .lock()
                    .unwrap()
                    .get(&session_id)
                    .ok_or_else(|| "session not found".to_string())?
                    .ssh
                    .clone(),
                script,
            )?;
        }

        Ok(ConnectReply {
            session_id,
            root_path,
        })
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
fn write_input(
    session_id: String,
    input: String,
    state: State<'_, SessionStore>,
) -> Result<(), String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session
        .ssh
        .write(input.into_bytes())
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn resize_session(
    session_id: String,
    cols: u16,
    rows: u16,
    state: State<'_, SessionStore>,
) -> Result<(), String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session
        .ssh
        .resize(cols, rows)
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn disconnect_session(session_id: String, state: State<'_, SessionStore>) -> Result<(), String> {
    let removed = state.sessions.lock().unwrap().remove(&session_id);
    if let Some(session) = removed {
        session.ssh.disconnect();
        session.sftp.disconnect();
        Ok(())
    } else {
        Err("session not found".to_string())
    }
}

#[tauri::command]
fn list_dir(
    session_id: String,
    path: String,
    state: State<'_, SessionStore>,
) -> Result<Vec<DirEntryDto>, String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session
        .sftp
        .list_dir(path)
        .map(|entries| entries.into_iter().map(DirEntryDto::from).collect())
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn read_file(
    session_id: String,
    path: String,
    max_bytes: u32,
    state: State<'_, SessionStore>,
) -> Result<Vec<u8>, String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session
        .sftp
        .read_file(path, 0, max_bytes)
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn write_file(
    session_id: String,
    path: String,
    bytes: Vec<u8>,
    state: State<'_, SessionStore>,
) -> Result<(), String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session
        .sftp
        .write_file(path, bytes)
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn mkdir(session_id: String, path: String, state: State<'_, SessionStore>) -> Result<(), String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session.sftp.mkdir(path).map_err(|error| error.to_string())
}

#[tauri::command]
fn rename_path(
    session_id: String,
    from: String,
    to: String,
    state: State<'_, SessionStore>,
) -> Result<(), String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    session
        .sftp
        .rename(from, to)
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn delete_path(
    session_id: String,
    path: String,
    is_dir: bool,
    state: State<'_, SessionStore>,
) -> Result<(), String> {
    let sessions = state.sessions.lock().unwrap();
    let session = sessions
        .get(&session_id)
        .ok_or_else(|| "session not found".to_string())?;
    if is_dir {
        session
            .sftp
            .delete_dir(path)
            .map_err(|error| error.to_string())
    } else {
        session
            .sftp
            .delete_file(path)
            .map_err(|error| error.to_string())
    }
}

fn spawn_reader(
    app: AppHandle,
    sessions: Arc<Mutex<HashMap<String, ManagedSession>>>,
    session_id: String,
    ssh: Arc<SshSession>,
) {
    thread::spawn(move || loop {
        let bytes = ssh.read(100);
        if !bytes.is_empty() {
            let _ = app.emit(
                "ssh-output",
                SshOutputEvent {
                    session_id: session_id.clone(),
                    bytes,
                },
            );
        }

        if ssh.is_closed() {
            sessions.lock().unwrap().remove(&session_id);
            let _ = app.emit(
                "session-closed",
                SessionMessageEvent {
                    session_id,
                    message: "session closed".to_string(),
                },
            );
            break;
        }
    });
}

fn run_init_script(ssh: Arc<SshSession>, script: &InitScriptRequest) -> Result<(), String> {
    let working_dir = script.working_dir.trim();
    if !working_dir.is_empty() {
        ssh.write(format!("cd {}\r", working_dir).into_bytes())
            .map_err(|error| error.to_string())?;
    }

    for line in script.content.lines().map(str::trim_end) {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        ssh.write(format!("{line}\r").into_bytes())
            .map_err(|error| error.to_string())?;
    }

    Ok(())
}

fn resolve_root_path(home_dir: &str, working_dir: &str) -> String {
    let raw = working_dir.trim();
    if raw.is_empty() || raw == "~" {
        return home_dir.to_string();
    }
    if let Some(stripped) = raw.strip_prefix("~/") {
        return join_path(home_dir, stripped);
    }
    if raw.starts_with('/') {
        return raw.to_string();
    }
    join_path(home_dir, raw)
}

fn join_path(base: &str, child: &str) -> String {
    let base = base.trim_end_matches('/');
    let child = child.trim_start_matches('/');
    if base.is_empty() {
        format!("/{child}")
    } else {
        format!("{base}/{child}")
    }
}

impl From<DirEntry> for DirEntryDto {
    fn from(value: DirEntry) -> Self {
        Self {
            name: value.name,
            path: value.path,
            size: value.size,
            mtime: value.mtime,
            is_dir: value.is_dir,
            is_link: value.is_link,
            mode: value.mode,
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(SessionStore::default())
        .invoke_handler(tauri::generate_handler![
            connect_session,
            write_input,
            resize_session,
            disconnect_session,
            list_dir,
            read_file,
            write_file,
            mkdir,
            rename_path,
            delete_path
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
