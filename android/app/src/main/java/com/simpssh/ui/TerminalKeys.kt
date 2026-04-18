package com.simpssh.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.charset.StandardCharsets

enum class KeyModifier { Shift, Ctrl, Alt }

enum class KeyGroup { Control, Tabs, Modifiers, Navigation, Arrows, FKeys, Punctuation, Other }

/// `values()` 每次调用都会分配新数组,此处缓存为不可变列表,
/// 供工具栏与设置页的复选框列表共享。
val KeyGroups: List<KeyGroup> = KeyGroup.values().toList()

fun List<TerminalKey>.groupedNonEmpty(): List<Pair<KeyGroup, List<TerminalKey>>> {
    if (isEmpty()) return emptyList()
    val byGroup = groupBy { it.group }
    return KeyGroups.mapNotNull { g -> byGroup[g]?.let { g to it } }
}

/// 当 [topLabel] 非空时,工具栏渲染两行按键:
/// 上方显示 [topLabel],下方显示 [bottomLabel](默认回退到 [displayName])。
/// 设置页始终只使用 [displayName],不受这两个字段影响。
data class TerminalKey(
    val id: String,
    val displayName: String,
    val group: KeyGroup,
    val bytes: ByteArray? = null,
    val modifier: KeyModifier? = null,
    val topLabel: String? = null,
    val bottomLabel: String? = null,
)

val AllKeys: List<TerminalKey> = listOf(
    TerminalKey("Esc", "Esc", KeyGroup.Control, bytes = byteArrayOf(0x1B)),
    TerminalKey("Tab",      "Tab",       KeyGroup.Tabs, bytes = byteArrayOf(0x09)),
    TerminalKey(
        id = "ShiftTab", displayName = "Shift+Tab", group = KeyGroup.Tabs,
        bytes = "\u001B[Z".toByteArray(),
        topLabel = "Shift", bottomLabel = "Tab",
    ),
    TerminalKey(
        id = "Shift", displayName = "Shift", group = KeyGroup.Modifiers,
        modifier = KeyModifier.Shift,
    ),
    TerminalKey(
        id = "Ctrl",  displayName = "Ctrl",  group = KeyGroup.Modifiers,
        modifier = KeyModifier.Ctrl,
    ),
    TerminalKey(
        id = "Alt",   displayName = "Alt",   group = KeyGroup.Modifiers,
        modifier = KeyModifier.Alt, topLabel = "opt",
    ),
    TerminalKey("Home", "Home", KeyGroup.Navigation, bytes = "\u001B[H".toByteArray()),
    TerminalKey("End",  "End",  KeyGroup.Navigation, bytes = "\u001B[F".toByteArray()),
    TerminalKey("PgUp", "PgUp", KeyGroup.Navigation, bytes = "\u001B[5~".toByteArray()),
    TerminalKey("PgDn", "PgDn", KeyGroup.Navigation, bytes = "\u001B[6~".toByteArray()),
    TerminalKey("Insert","Ins", KeyGroup.Navigation, bytes = "\u001B[2~".toByteArray()),
    TerminalKey("Delete","Del", KeyGroup.Navigation, bytes = "\u001B[3~".toByteArray()),
    TerminalKey("ArrowUp",    "↑", KeyGroup.Arrows, bytes = "\u001B[A".toByteArray()),
    TerminalKey("ArrowDown",  "↓", KeyGroup.Arrows, bytes = "\u001B[B".toByteArray()),
    TerminalKey("ArrowLeft",  "←", KeyGroup.Arrows, bytes = "\u001B[D".toByteArray()),
    TerminalKey("ArrowRight", "→", KeyGroup.Arrows, bytes = "\u001B[C".toByteArray()),
    TerminalKey("F1",  "F1",  KeyGroup.FKeys, bytes = "\u001BOP".toByteArray()),
    TerminalKey("F2",  "F2",  KeyGroup.FKeys, bytes = "\u001BOQ".toByteArray()),
    TerminalKey("F3",  "F3",  KeyGroup.FKeys, bytes = "\u001BOR".toByteArray()),
    TerminalKey("F4",  "F4",  KeyGroup.FKeys, bytes = "\u001BOS".toByteArray()),
    TerminalKey("F5",  "F5",  KeyGroup.FKeys, bytes = "\u001B[15~".toByteArray()),
    TerminalKey("F6",  "F6",  KeyGroup.FKeys, bytes = "\u001B[17~".toByteArray()),
    TerminalKey("F7",  "F7",  KeyGroup.FKeys, bytes = "\u001B[18~".toByteArray()),
    TerminalKey("F8",  "F8",  KeyGroup.FKeys, bytes = "\u001B[19~".toByteArray()),
    TerminalKey("F9",  "F9",  KeyGroup.FKeys, bytes = "\u001B[20~".toByteArray()),
    TerminalKey("F10", "F10", KeyGroup.FKeys, bytes = "\u001B[21~".toByteArray()),
    TerminalKey("F11", "F11", KeyGroup.FKeys, bytes = "\u001B[23~".toByteArray()),
    TerminalKey("F12", "F12", KeyGroup.FKeys, bytes = "\u001B[24~".toByteArray()),
    TerminalKey("Pipe",  "|", KeyGroup.Punctuation, bytes = byteArrayOf(0x7C)),
    TerminalKey("Slash", "/", KeyGroup.Punctuation, bytes = byteArrayOf(0x2F)),
    TerminalKey("Dash",  "-", KeyGroup.Punctuation, bytes = byteArrayOf(0x2D)),
    TerminalKey("Tilde", "~", KeyGroup.Punctuation, bytes = byteArrayOf(0x7E)),
)

private val KeyIndex: Map<String, TerminalKey> = AllKeys.associateBy { it.id }
fun keyById(id: String): TerminalKey? = KeyIndex[id]

val DefaultToolbarKeyIds: List<String> = listOf(
    "Esc", "Tab", "ShiftTab", "Shift", "Ctrl", "Alt", "Home", "End",
)

@Stable
class ModifierState {
    var shift by mutableStateOf(false); private set
    var ctrl  by mutableStateOf(false); private set
    var alt   by mutableStateOf(false); private set

    fun isAnyOn(): Boolean = shift || ctrl || alt

    fun toggle(m: KeyModifier) {
        when (m) {
            KeyModifier.Shift -> shift = !shift
            KeyModifier.Ctrl  -> ctrl  = !ctrl
            KeyModifier.Alt   -> alt   = !alt
        }
    }

    fun isOn(m: KeyModifier): Boolean = when (m) {
        KeyModifier.Shift -> shift
        KeyModifier.Ctrl  -> ctrl
        KeyModifier.Alt   -> alt
    }

    fun resetAll() { shift = false; ctrl = false; alt = false }

    /// 非 ASCII 字符回退到纯 UTF-8 编码:`c.code.toByte()` 会静默截断
    /// 16 位 code point 的高字节,产出无效 UTF-8(例如 "," U+FF0C 会变成
    /// `<008b><0095>` 这种乱码序列)。
    fun consume(input: String): ByteArray {
        try {
            val isSingleAscii = input.length == 1 && input[0].code <= 0x7F
            if (!isSingleAscii) return input.toByteArray(StandardCharsets.UTF_8)
            var c = input[0]
            if (shift) c = c.uppercaseChar()
            val core: Byte = if (ctrl && c in 'a'..'z') (c - 'a' + 1).toByte()
                             else if (ctrl && c in 'A'..'Z') (c - 'A' + 1).toByte()
                             else c.code.toByte()
            return if (alt) byteArrayOf(0x1B, core) else byteArrayOf(core)
        } finally {
            resetAll()
        }
    }
}
