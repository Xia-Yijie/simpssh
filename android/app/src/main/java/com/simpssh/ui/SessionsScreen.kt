package com.simpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

    // Light or dark Material variant chosen automatically based on the
    // palette's background luminance, so the session view matches the home
    // screen primary colour and adapts to light palettes too.
    MaterialTheme(colorScheme = sessionSchemeFor(LocalPalette.current)) {
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
    LaunchedEffect(tab.rows.size, tab.rows.lastOrNull()?.text) {
        if (tab.rows.isNotEmpty()) {
            runCatching { listState.scrollToItem(tab.rows.lastIndex) }
        }
    }

    val focusReq = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var ctrlPending by remember { mutableStateOf(false) }
    var shadow by remember(tab.id) { mutableStateOf(TextFieldValue("")) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status line
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

        val palette = LocalPalette.current
        val termBg = palette.darkBackground
        val termFg = bestForeground(termBg)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth().weight(1f)
                .background(termBg),
        ) {
            val termStyle = TerminalTextStyle
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
                    val ann = row.toAnnotatedString(themeFg = termFg, themeBg = termBg)
                    val withCursor = if (i == tab.cursorRow)
                        addCursorBlock(ann, tab.cursorCol, fg = termBg, bg = termFg)
                    else ann
                    Text(withCursor, style = termStyle)
                }
            }
        }

        // Bottom toolbar: shortcut keys + keyboard trigger
        TerminalToolbar(
            ctrlPending = ctrlPending,
            onToggleCtrl = { ctrlPending = !ctrlPending },
            onSendBytes = onSendBytes,
            onShowKeyboard = {
                runCatching { focusReq.requestFocus() }
                keyboard?.show()
            },
        )

        // Tiny invisible BasicTextField that owns the IME connection. We
        // give it 1.dp + alpha 0 (rather than 0.dp) so Compose actually
        // includes it in layout/focus tree — a 0.dp child gets skipped and
        // FocusRequester.requestFocus() throws "FocusRequester is not
        // initialized". Sits below the toolbar where it doesn't intercept
        // any visible touches.
        BasicTextField(
            value = shadow,
            onValueChange = { new ->
                val oldText = shadow.text
                val newText = new.text
                if (newText != oldText) {
                    val common = oldText.commonPrefixWith(newText).length
                    val toDelete = oldText.length - common
                    val toAdd = newText.substring(common)
                    if (toDelete > 0) onSendBytes(ByteArray(toDelete) { 0x7F })
                    if (toAdd.isNotEmpty()) {
                        if (ctrlPending && toAdd.length == 1) {
                            val ch = toAdd[0]
                            val byte: Byte? = when (ch) {
                                in 'a'..'z' -> (ch - 'a' + 1).toByte()
                                in 'A'..'Z' -> (ch - 'A' + 1).toByte()
                                else -> null
                            }
                            if (byte != null) onSendBytes(byteArrayOf(byte))
                            else onSendBytes(toAdd.toByteArray())
                        } else {
                            onSendBytes(toAdd.toByteArray())
                        }
                        ctrlPending = false
                    }
                }
                shadow = new
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusReq)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val bytes = specialKeyBytes(ev) ?: return@onPreviewKeyEvent false
                    onSendBytes(bytes)
                    true
                },
            singleLine = true,
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent),
            keyboardOptions = KeyboardOptions(
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = {
                onSendBytes(byteArrayOf(0x0D))
                shadow = TextFieldValue("")
            }),
        )
    }
}

/// Termius-style shortcut bar pinned below the terminal area. Single tap
/// on any key sends the matching byte sequence; Ctrl is sticky (next
/// typed letter becomes Ctrl+letter, then resets). The right-side ⌨
/// button is the only way to bring up the soft keyboard — the terminal
/// grid above no longer auto-focuses the IME when tapped.
@Composable
private fun TerminalToolbar(
    ctrlPending: Boolean,
    onToggleCtrl: () -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolKey("Esc")  { onSendBytes(byteArrayOf(0x1B)) }
            ToolKey("Ctrl", selected = ctrlPending) { onToggleCtrl() }
            ToolKey("Tab")  { onSendBytes(byteArrayOf(0x09)) }
            ToolKey("↑")    { onSendBytes("\u001B[A".toByteArray()) }
            ToolKey("↓")    { onSendBytes("\u001B[B".toByteArray()) }
            ToolKey("←")    { onSendBytes("\u001B[D".toByteArray()) }
            ToolKey("→")    { onSendBytes("\u001B[C".toByteArray()) }
            ToolKey("|")    { onSendBytes(byteArrayOf(0x7C)) }
            ToolKey("/")    { onSendBytes(byteArrayOf(0x2F)) }
            ToolKey("-")    { onSendBytes(byteArrayOf(0x2D)) }
            ToolKey("~")    { onSendBytes(byteArrayOf(0x7E)) }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onShowKeyboard),
            contentAlignment = Alignment.Center,
        ) {
            NerdIcon(
                NerdGlyphs.KEYBOARD, "调出键盘",
                size = 18.dp,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ToolKey(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(color = fg))
    }
}

/// Shared text style for any monospace terminal-like rendering (the real
/// shell view, the file-preview dialog, and the per-theme preview). Keep
/// these in sync so the preview really looks like the terminal.
internal val TerminalTextStyle: TextStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

// Rust DEFAULT_FG / DEFAULT_BG, must match `core/src/terminal.rs`
// (DEFAULT_FG = 0xD3D7CF, DEFAULT_BG = 0x000000). Cells whose fg/bg equal
// these are treated as "use the theme colour" instead of the literal value
// — keep both ends in sync if you ever change the Rust defaults.
private const val RUST_DEFAULT_FG = 0xD3D7CF
private const val RUST_DEFAULT_BG = 0x000000

private fun addCursorBlock(s: AnnotatedString, col: Int, fg: Color, bg: Color): AnnotatedString {
    if (col < 0) return s
    val padded = if (col >= s.length) {
        AnnotatedString.Builder(s).apply { append(" ".repeat(col - s.length + 1)) }.toAnnotatedString()
    } else s
    val b = AnnotatedString.Builder(padded)
    b.addStyle(SpanStyle(background = bg, color = fg), col, col + 1)
    return b.toAnnotatedString()
}

private fun StyledRow.toAnnotatedString(themeFg: Color, themeBg: Color): AnnotatedString {
    val b = AnnotatedString.Builder()
    b.append(text)
    spans.forEach { sp ->
        val fgInt = sp.fg.toInt() and 0xFFFFFF
        val bgInt = sp.bg.toInt() and 0xFFFFFF
        val fg = if (fgInt == RUST_DEFAULT_FG) themeFg
                 else Color(0xFF000000.toInt() or fgInt)
        val bg = if (bgInt == RUST_DEFAULT_BG) themeBg
                 else Color(0xFF000000.toInt() or bgInt)
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
        // F-keys — used heavily by vim / htop / tmux / midnight commander.
        Key.F1             -> "\u001BOP".toByteArray()
        Key.F2             -> "\u001BOQ".toByteArray()
        Key.F3             -> "\u001BOR".toByteArray()
        Key.F4             -> "\u001BOS".toByteArray()
        Key.F5             -> "\u001B[15~".toByteArray()
        Key.F6             -> "\u001B[17~".toByteArray()
        Key.F7             -> "\u001B[18~".toByteArray()
        Key.F8             -> "\u001B[19~".toByteArray()
        Key.F9             -> "\u001B[20~".toByteArray()
        Key.F10            -> "\u001B[21~".toByteArray()
        Key.F11            -> "\u001B[23~".toByteArray()
        Key.F12            -> "\u001B[24~".toByteArray()
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
