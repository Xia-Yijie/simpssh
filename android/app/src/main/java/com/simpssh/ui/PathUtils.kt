package com.simpssh.ui

internal fun joinPath(base: String, name: String): String =
    if (base.endsWith('/')) "$base$name" else "$base/$name"

internal fun parentOf(p: String): String {
    if (p == "/") return "/"
    val trimmed = p.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else trimmed.substring(0, cut)
}
