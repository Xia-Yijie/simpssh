package com.simpssh.ui

internal const val DEFAULT_TERM_COLS: UShort = 80u
internal const val DEFAULT_TERM_ROWS: UShort = 24u
internal const val SHELL_READ_TIMEOUT_MS: UInt = 200u
internal const val REMOTE_RESIZE_COALESCE_MS: Long = 80L
/// Ink 的 `useInput` 会在一个匹配窗口内按键名吞字节;[TYPING_GAP_MS]
/// 与 [KEYWORD_TRAP_SET] 一起用于避开这个外部行为。
internal const val TYPING_GAP_MS: Long = 50L

/// Ink 的 `useInput` 会在其匹配窗口内按这些关键字整段识别到达的字节,
/// 单次写入任何一个都会被误判为对应的导航键而吞掉。
internal val KEYWORD_TRAP_SET: Set<String> = setOf(
    "up", "down", "left", "right",
    "tab", "enter", "return", "space", "backspace",
    "escape", "esc", "delete",
    "home", "end", "pageup", "pagedown",
    "f1", "f2", "f3", "f4", "f5", "f6",
    "f7", "f8", "f9", "f10", "f11", "f12",
)
internal const val MAX_KEYWORD_LEN: Int = 12

/// UI 中用于抑制状态横幅的比较值,必须与 [SessionManager] /
/// [SessionsScreen] 保持完全一致。
internal const val STATUS_CONNECTED: String = "已连接"
internal const val PREVIEW_MAX_BYTES: Int = 256 * 1024
/// 图片会被解码为内存中的 `ImageBitmap`,此处限制是为了避免解码 OOM;
/// 普通手机照片远低于这个上限。
internal const val IMAGE_MAX_BYTES: Int = 32 * 1024 * 1024
/// 1 MB 和整数 MB 显示粒度对齐,进度条不会出现小数跳动。
internal const val DOWNLOAD_CHUNK_BYTES: Int = 1024 * 1024
internal const val TREE_INDENT_DP: Int = 16

internal fun joinPath(base: String, name: String): String =
    if (base.endsWith('/')) "$base$name" else "$base/$name"

internal fun parentOf(p: String): String {
    if (p == "/") return "/"
    val trimmed = p.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else trimmed.substring(0, cut)
}

internal fun formatError(action: String, err: Throwable?): String =
    "${action}失败: ${err?.message ?: "未知错误"}"

internal suspend fun reportFilesError(
    tab: TabState,
    action: String,
    err: Throwable?,
) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        tab.filesStatus = formatError(action, err)
    }
}
