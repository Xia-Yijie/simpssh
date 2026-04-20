// key = sha256(host|user|path|size|mtime),格式与 Android SftpCache.kt 保持一致。

use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use sha2::{Digest, Sha256};

pub const MAX_CACHE_BYTES: u64 = 200 * 1024 * 1024;
const TRIM_BUDGET: u64 = 10 * 1024 * 1024;

pub struct SftpCache {
    root: PathBuf,
    bytes_since_trim: AtomicU64,
}

impl SftpCache {
    pub fn new(root: PathBuf) -> io::Result<Self> {
        fs::create_dir_all(&root)?;
        clean_writing_orphans(&root);
        Ok(Self {
            root,
            bytes_since_trim: AtomicU64::new(0),
        })
    }

    pub fn default_root() -> PathBuf {
        // Linux 走 ~/.simpssh(用户明确要求不用 XDG_CACHE_HOME)。
        if cfg!(target_os = "linux") {
            if let Ok(home) = std::env::var("HOME") {
                return Path::new(&home).join(".simpssh").join("cache");
            }
        } else if cfg!(target_os = "macos") {
            if let Ok(home) = std::env::var("HOME") {
                return Path::new(&home)
                    .join("Library")
                    .join("Caches")
                    .join("simpssh");
            }
        } else if cfg!(target_os = "windows") {
            if let Ok(local) = std::env::var("LOCALAPPDATA") {
                return Path::new(&local).join("simpssh").join("cache");
            }
        }
        PathBuf::from(".simpssh-cache")
    }

    pub fn key_for(host: &str, user: &str, path: &str, size: u64, mtime: u64) -> String {
        let raw = format!("{host}|{user}|{path}|{size}|{mtime}");
        let digest = Sha256::digest(raw.as_bytes());
        hex::encode(digest)
    }

    pub fn final_file(&self, key: &str) -> PathBuf {
        self.root.join(format!("{key}.dat"))
    }

    pub fn tmp_file(&self, key: &str) -> PathBuf {
        self.root.join(format!("{key}.dat.tmp"))
    }

    /// 完整命中:size 完全匹配才算。否则返回 None,让调用方决定续传还是重下。
    pub fn complete_file_of(&self, key: &str, expected_size: u64) -> Option<PathBuf> {
        let p = self.final_file(key);
        if let Ok(meta) = fs::metadata(&p) {
            if meta.is_file() && meta.len() == expected_size {
                return Some(p);
            }
        }
        None
    }

    pub fn record_write(&self, bytes: u64) {
        self.bytes_since_trim.fetch_add(bytes, Ordering::Relaxed);
    }

    /// 写入累计过 TRIM_BUDGET 才扫目录按 mtime 淘汰最旧,直到总量 ≤ max_bytes。
    /// 保留最新一条,避免单文件 > cap 时把刚下好的干掉。
    pub fn trim(&self, max_bytes: u64) {
        if self.bytes_since_trim.load(Ordering::Relaxed) < TRIM_BUDGET {
            return;
        }
        self.bytes_since_trim.store(0, Ordering::Relaxed);

        let mut snaps: Vec<(PathBuf, u64, std::time::SystemTime)> = match fs::read_dir(&self.root) {
            Ok(rd) => rd
                .filter_map(|e| e.ok())
                .filter_map(|e| {
                    let md = e.metadata().ok()?;
                    if !md.is_file() {
                        return None;
                    }
                    Some((e.path(), md.len(), md.modified().ok()?))
                })
                .collect(),
            Err(_) => return,
        };
        if snaps.is_empty() {
            return;
        }
        snaps.sort_by_key(|s| s.2);
        let mut total: u64 = snaps.iter().map(|s| s.1).sum();
        let last = snaps.len().saturating_sub(1);
        for (i, (path, size, _)) in snaps.iter().enumerate() {
            if i == last {
                break;
            }
            if total <= max_bytes {
                break;
            }
            if fs::remove_file(path).is_ok() {
                total -= size;
            }
        }
    }

    /// 读完整文件(预览场景用)。
    #[allow(dead_code)]
    pub fn read_all(&self, key: &str, expected_size: u64) -> Option<Vec<u8>> {
        let p = self.complete_file_of(key, expected_size)?;
        let mut buf = Vec::with_capacity(expected_size as usize);
        fs::File::open(&p).ok()?.read_to_end(&mut buf).ok()?;
        Some(buf)
    }

    #[allow(dead_code)]
    pub fn write_atomic(&self, key: &str, bytes: &[u8]) -> io::Result<()> {
        let writing = self.root.join(format!("{key}.dat.writing"));
        {
            let mut f = fs::File::create(&writing)?;
            f.write_all(bytes)?;
            f.sync_all()?;
        }
        fs::rename(&writing, self.final_file(key))?;
        self.bytes_since_trim
            .fetch_add(bytes.len() as u64, Ordering::Relaxed);
        Ok(())
    }

    pub fn promote_tmp_to_final(&self, key: &str) -> io::Result<()> {
        fs::rename(self.tmp_file(key), self.final_file(key))
    }
}

fn clean_writing_orphans(root: &Path) {
    if let Ok(rd) = fs::read_dir(root) {
        for entry in rd.flatten() {
            if let Some(name) = entry.file_name().to_str() {
                if name.ends_with(".dat.writing") {
                    let _ = fs::remove_file(entry.path());
                }
            }
        }
    }
}
