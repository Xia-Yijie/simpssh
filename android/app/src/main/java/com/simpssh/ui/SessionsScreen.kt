package com.simpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.simpssh_core.StyledRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(manager: SessionManager, onHome: () -> Unit) {
    val activeId = manager.activeId
    val active = manager.tabs.firstOrNull { it.id == activeId }

    LaunchedEffect(manager.tabs.size) {
        if (manager.tabs.isEmpty()) onHome()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("会话") },
                    navigationIcon = {
                        IconButton(onClick = onHome) { NerdIcon(NerdGlyphs.HOME, "首页", size = 20.dp) }
                    },
                )
                if (manager.tabs.isNotEmpty()) {
                    val selectedIndex = manager.tabs.indexOfFirst { it.id == activeId }
                        .coerceAtLeast(0)
                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        edgePadding = 4.dp,
                    ) {
                        manager.tabs.forEachIndexed { i, t ->
                            Tab(
                                selected = i == selectedIndex,
                                onClick = { manager.activate(t.id) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(t.title, maxLines = 1)
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { manager.close(t.id) },
                                            modifier = Modifier.size(20.dp),
                                        ) {
                                            NerdIcon(NerdGlyphs.TIMES, "关闭", size = 14.dp)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (active != null) {
                SessionBody(active, manager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionBody(tab: TabState, manager: SessionManager) {
    // Lazy SFTP connect on first switch to Files.
    LaunchedEffect(tab.view) {
        if (tab.view == TabState.View.Files) manager.ensureFilesConnected(tab)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // View switcher
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = tab.view == TabState.View.Terminal,
                    onClick = { tab.view = TabState.View.Terminal },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { NerdIcon(NerdGlyphs.TERMINAL, null, size = 16.dp) },
                ) {
                    Text("Terminal")
                }
                SegmentedButton(
                    selected = tab.view == TabState.View.Files,
                    onClick = { tab.view = TabState.View.Files },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { NerdIcon(NerdGlyphs.FOLDER, null, size = 16.dp) },
                ) {
                    Text("Files")
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (tab.view) {
                TabState.View.Terminal -> ShellBody(tab, manager) { bytes ->
                    manager.send(tab.id, bytes)
                }
                TabState.View.Files -> FilesBody(tab, manager)
            }
        }
    }
}

@Composable
private fun ShellBody(tab: TabState, manager: SessionManager, onSendBytes: (ByteArray) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(tab.rows.size) {
        if (tab.rows.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(tab.rows.lastIndex) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Text(
                tab.shellStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(8.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth().weight(1f)
                .background(TerminalBackground),
        ) {
            val termStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            val measurer = rememberTextMeasurer()
            val sample = remember(termStyle) { measurer.measure(AnnotatedString("M"), termStyle) }
            val padPx = with(LocalDensity.current) { 12.dp.toPx() }
            val cols = (((constraints.maxWidth - 2 * padPx) / sample.size.width).toInt()).coerceAtLeast(20)
            val rows = (((constraints.maxHeight - 2 * padPx) / sample.size.height).toInt()).coerceAtLeast(5)

            LaunchedEffect(cols, rows) {
                manager.resizeTerminal(tab.id, cols.toUShort(), rows.toUShort())
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            ) {
                itemsIndexed(tab.rows) { i, row ->
                    val ann = row.toAnnotatedString()
                    val withCursor = if (i == tab.cursorRow) addCursorBlock(ann, tab.cursorCol) else ann
                    Text(withCursor, style = termStyle)
                }
            }
        }
        TerminalInput(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            onSendBytes = onSendBytes,
        )
        Text(
            "光标 (${tab.cursorRow},${tab.cursorCol})  ·  ${tab.cols.toInt()}×${tab.terminalRows.toInt()}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/// Per-keystroke input for the terminal.
///
/// We never display what the user types in this field — the local text style
/// is transparent so they only see characters echoed back from the remote
/// shell. The local TextFieldValue is used purely as a "shadow" so the IME
/// has something to delete with Backspace; on every change we diff old vs
/// new and send the delta as bytes.
///
/// Hardware keyboard special keys (Esc / Tab / arrows / Ctrl-letter) are
/// caught via `onPreviewKeyEvent` and translated to the matching escape
/// sequences. Soft keyboards usually lack these, so for now those operations
/// require a hardware keyboard.
@Composable
private fun TerminalInput(
    modifier: Modifier = Modifier,
    onSendBytes: (ByteArray) -> Unit,
) {
    var shadow by remember { mutableStateOf(TextFieldValue("")) }

    OutlinedTextField(
        value = shadow,
        onValueChange = { new ->
            val oldText = shadow.text
            val newText = new.text
            if (newText != oldText) {
                val common = commonPrefixLen(oldText, newText)
                val toDelete = oldText.length - common
                val toAdd = newText.substring(common)
                if (toDelete > 0) onSendBytes(ByteArray(toDelete) { 0x7F })
                if (toAdd.isNotEmpty()) onSendBytes(toAdd.toByteArray())
            }
            shadow = new
        },
        modifier = modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val bytes = specialKeyBytes(ev) ?: return@onPreviewKeyEvent false
            onSendBytes(bytes)
            true
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.Transparent),
        placeholder = { Text("点这里调出键盘 — 按键即发送") },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = {
            onSendBytes(byteArrayOf('\r'.code.toByte()))
            shadow = TextFieldValue("")
        }),
    )
}

// Default terminal background — matches the DEFAULT_BG in Rust so cells with
// no explicit background blend with the surrounding container.
private val TerminalBackground = Color(0xFF000000)

private val CursorBg = Color(0xFFD3D7CF.toInt())   // matches default fg → block cursor
private val CursorFg = Color(0xFF000000.toInt())   // black char on light block

private fun addCursorBlock(s: AnnotatedString, col: Int): AnnotatedString {
    if (col < 0) return s
    // Pad with spaces if the cursor is past the end of the row text.
    val padded = if (col >= s.length) {
        AnnotatedString.Builder(s).apply { append(" ".repeat(col - s.length + 1)) }.toAnnotatedString()
    } else s
    val b = AnnotatedString.Builder(padded)
    b.addStyle(
        SpanStyle(background = CursorBg, color = CursorFg),
        col,
        col + 1,
    )
    return b.toAnnotatedString()
}

private fun StyledRow.toAnnotatedString(): AnnotatedString {
    val b = AnnotatedString.Builder()
    b.append(text)
    spans.forEach { sp ->
        val fg = Color(0xFF000000.toInt() or sp.fg.toInt())
        val bg = Color(0xFF000000.toInt() or sp.bg.toInt())
        val flags = sp.flags.toInt()
        b.addStyle(
            SpanStyle(
                color = fg,
                background = bg,
                fontWeight = if (flags and 0b0001 != 0) FontWeight.Bold else null,
                fontStyle = if (flags and 0b0010 != 0) FontStyle.Italic else null,
                textDecoration = if (flags and 0b0100 != 0) TextDecoration.Underline else null,
            ),
            sp.start.toInt(),
            (sp.start + sp.len).toInt().coerceAtMost(text.length),
        )
    }
    return b.toAnnotatedString()
}

private fun commonPrefixLen(a: String, b: String): Int {
    var i = 0
    val n = minOf(a.length, b.length)
    while (i < n && a[i] == b[i]) i++
    return i
}

private fun specialKeyBytes(ev: KeyEvent): ByteArray? {
    // Hardware keyboard special keys → ANSI/VT escape sequences.
    return when (ev.key) {
        Key.DirectionUp    -> "\u001B[A".toByteArray()
        Key.DirectionDown  -> "\u001B[B".toByteArray()
        Key.DirectionRight -> "\u001B[C".toByteArray()
        Key.DirectionLeft  -> "\u001B[D".toByteArray()
        Key.Escape         -> byteArrayOf(0x1B)
        Key.Tab            -> byteArrayOf(0x09)
        Key.Backspace      -> byteArrayOf(0x7F)
        Key.MoveHome       -> "\u001B[H".toByteArray()
        Key.MoveEnd        -> "\u001B[F".toByteArray()
        Key.Delete         -> "\u001B[3~".toByteArray()
        Key.PageUp         -> "\u001B[5~".toByteArray()
        Key.PageDown       -> "\u001B[6~".toByteArray()
        else -> {
            if (ev.isCtrlPressed) {
                val cp = ev.utf16CodePoint
                when (cp) {
                    in 'a'.code..'z'.code -> byteArrayOf((cp - 'a'.code + 1).toByte())
                    in 'A'.code..'Z'.code -> byteArrayOf((cp - 'A'.code + 1).toByte())
                    '['.code -> byteArrayOf(0x1B)             // Ctrl+[ == Esc
                    '\\'.code -> byteArrayOf(0x1C)
                    ']'.code -> byteArrayOf(0x1D)
                    else -> null
                }
            } else null
        }
    }
}
