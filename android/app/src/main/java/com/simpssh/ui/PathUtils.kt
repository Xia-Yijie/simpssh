package com.simpssh.ui

internal const val DEFAULT_TERM_COLS: UShort = 80u
internal const val DEFAULT_TERM_ROWS: UShort = 24u
internal const val SHELL_READ_TIMEOUT_MS: UInt = 200u
internal const val PREVIEW_MAX_BYTES: Int = 256 * 1024
internal const val DOWNLOAD_MAX_BYTES: Int = 16 * 1024 * 1024
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
