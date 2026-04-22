package com.simpssh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.simpssh.R
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.charset.StandardCharsets
import uniffi.simpssh_core.StyledRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionsScreen(
    manager: SessionManager,
    toolbarKeyIds: List<String>,
    terminalFontSize: Float,
    onTerminalFontSizeChange: (Float) -> Unit,
    showModeBadges: Boolean,
    onHome: () -> Unit,
) {
    val activeId = manager.activeId
    val active = manager.tabs.firstOrNull { it.id == activeId }

    LaunchedEffect(manager.tabs.size) {
        if (manager.tabs.isEmpty()) onHome()
    }

    // 切 tab 时强制收键盘。先 clearFocus 摘掉 BasicTextField 的焦点,
    // 否则有些 ROM 在 hide 后会马上因焦点仍在输入框而再弹起来。
    val imeController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(activeId) {
        focusManager.clearFocus(force = true)
        imeController?.hide()
    }


    val window = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)?.window
    androidx.compose.runtime.DisposableEffect(Unit) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    MaterialTheme(colorScheme = sessionSchemeFor(LocalPalette.current)) {
    Scaffold(
        topBar = {
            CompactTopBar(
                tabs = manager.tabs,
                activeTab = active,
                onHome = onHome,
                onActivate = manager::activate,
                onClose = manager::close,
                onSetView = { v ->
                    manager.tabs.firstOrNull { it.id == manager.activeId }?.view = v
                },
            )
        },
    ) { padding ->
        // 用 imeAnimationTarget 而非 ime:布局直接采纳"动画结束后"的 inset,
        // 动画中不再逐帧重新 measure+layout,避免跨 cell 边界导致 ShellBody 反复重组。
        Box(modifier = Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.imeAnimationTarget)) {
            if (active != null) {
                SessionBody(
                    tab = active,
                    manager = manager,
                    toolbarKeyIds = toolbarKeyIds,
                    terminalFontSize = terminalFontSize,
                    onTerminalFontSizeChange = onTerminalFontSizeChange,
                    showModeBadges = showModeBadges,
                )
            }
        }
    }
    }
}

@Composable
private fun CompactTopBar(
    tabs: List<TabState>,
    activeTab: TabState?,
    onHome: () -> Unit,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
    onSetView: (TabState.View) -> Unit,
) {
    val activeId = activeTab?.id
    var menuTabId by remember { mutableStateOf<String?>(null) }
    var renameTabId by remember { mutableStateOf<String?>(null) }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onHome, modifier = Modifier.size(36.dp)) {
                NerdIcon(NerdGlyphs.HOME, "首页", size = 18.dp)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { t ->
                    Box {
                        CompactTab(
                            title = t.shortTitle,
                            selected = t.id == activeId,
                            onClick = { onActivate(t.id) },
                            onLongPress = { menuTabId = t.id },
                        )
                        DropdownMenu(
                            expanded = menuTabId == t.id,
                            onDismissRequest = { menuTabId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("修改会话名") },
                                onClick = {
                                    menuTabId = null
                                    renameTabId = t.id
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("关闭") },
                                onClick = {
                                    menuTabId = null
                                    onClose(t.id)
                                },
                            )
                        }
                    }
                }
            }
            if (activeTab != null) {
                ViewToggleIcon(
                    glyph = NerdGlyphs.TERMINAL,
                    contentDescription = "终端",
                    selected = activeTab.view == TabState.View.Terminal,
                    onClick = { onSetView(TabState.View.Terminal) },
                )
                ViewToggleIcon(
                    glyph = NerdGlyphs.FOLDER,
                    contentDescription = "文件",
                    selected = activeTab.view == TabState.View.Files,
                    onClick = { onSetView(TabState.View.Files) },
                )
            }
        }
    }
    val renamingTab = tabs.firstOrNull { it.id == renameTabId }
    if (renamingTab != null) {
        TextInputDialog(
            title = "修改会话名",
            label = "",
            initial = renamingTab.displayName,
            placeholder = renamingTab.server.name.ifBlank { renamingTab.server.host },
            confirm = "保存",
            onCancel = { renameTabId = null },
            onConfirm = {
                renamingTab.displayName = it
                renameTabId = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ViewToggleIcon(
    glyph: String,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NerdIcon(glyph, contentDescription, size = 16.dp, tint = fg)
    }
}

@Composable
private fun SessionBody(
    tab: TabState,
    manager: SessionManager,
    toolbarKeyIds: List<String>,
    terminalFontSize: Float,
    onTerminalFontSizeChange: (Float) -> Unit,
    showModeBadges: Boolean,
) {
    LaunchedEffect(tab.view) {
        if (tab.view == TabState.View.Files) manager.ensureFilesConnected(tab)
    }
    when (tab.view) {
        TabState.View.Terminal -> ShellBody(
            tab = tab,
            manager = manager,
            toolbarKeyIds = toolbarKeyIds,
            fontSize = terminalFontSize,
            onFontSizeChange = onTerminalFontSizeChange,
            showModeBadges = showModeBadges,
            onSendBytes = { bytes -> manager.send(tab.id, bytes) },
        )
        TabState.View.Files -> FilesBody(tab, manager)
    }
}

@Composable
private fun ShellBody(
    tab: TabState,
    manager: SessionManager,
    toolbarKeyIds: List<String>,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    showModeBadges: Boolean,
    onSendBytes: (ByteArray) -> Unit,
) {
    val listState = remember(tab.id) { androidx.compose.foundation.lazy.LazyListState() }
    LaunchedEffect(tab.id, tab.rows.size, tab.rows.lastOrNull()?.text) {
        if (tab.rows.isNotEmpty()) {
            runCatching { listState.scrollToItem(tab.rows.lastIndex) }
        }
    }

    // key(tab.id): 切 tab 后 toolbar 的 show-keyboard 必须绑到新 tab 的 TextField。
    val focusReq = remember(tab.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val mods = remember(tab.id) { ModifierState() }
    var shadow by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
    var showOverflow by remember(tab.id) { mutableStateOf(false) }
    val overflowGrouped = remember(toolbarKeyIds) {
        val taken = toolbarKeyIds.toSet()
        AllKeys.filter { it.id !in taken }.groupedNonEmpty()
    }

    // JNI 模式位读取不受 Snapshot 追踪,只能轮询;由 `showModeBadges` 门控,默认路径不做周期任务。
    var bracketedPaste by remember(tab.id) { mutableStateOf(false) }
    var altScreen by remember(tab.id) { mutableStateOf(false) }
    var mouseReporting by remember(tab.id) { mutableStateOf(false) }
    if (showModeBadges) {
        LaunchedEffect(tab.id) {
            while (true) {
                val t = tab.term
                val bp = t?.isBracketedPaste() == true
                val alt = t?.isAltScreen() == true
                val ms = t?.isMouseReporting() == true
                if (bp != bracketedPaste) bracketedPaste = bp
                if (alt != altScreen) altScreen = alt
                if (ms != mouseReporting) mouseReporting = ms
                kotlinx.coroutines.delay(150L)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TerminalToolbar(
            keyIds = toolbarKeyIds,
            mods = mods,
            onSendBytes = onSendBytes,
            overflowOpen = showOverflow,
            mouseMode = tab.mouseMode,
            onToggleMouseMode = { tab.mouseMode = !tab.mouseMode },
            onShowKeyboard = {
                runCatching { focusReq.requestFocus() }
                keyboard?.show()
            },
            onToggleOverflow = { showOverflow = !showOverflow },
        )

        if (showModeBadges) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeBadge("BP", active = bracketedPaste)
                ModeBadge("ALT", active = altScreen)
                ModeBadge("MS", active = mouseReporting)
            }
        }

        if (tab.shellStatus != STATUS_CONNECTED) {
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
        }

        val palette = LocalPalette.current
        val termBg = palette.darkBackground
        val termFg = bestForeground(termBg)
        val termStyle = remember(fontSize) { TerminalTextStyle.copy(fontSize = fontSize.sp) }
        val measurer = rememberTextMeasurer()
        // TextLayoutResult.size 给的是 bounding box,不是 advance-width 总和;两次长度相减可以抵消两端共有的 trim。
        val (charW, charH) = remember(termStyle) {
            val short = measurer.measure(AnnotatedString("M".repeat(10)), termStyle)
            val long  = measurer.measure(AnnotatedString("M".repeat(50)), termStyle)
            val latinW = (long.size.width - short.size.width).toFloat() / 40f
            val hanShort = measurer.measure(AnnotatedString("汉".repeat(10)), termStyle)
            val hanLong = measurer.measure(AnnotatedString("汉".repeat(30)), termStyle)
            val hanCellW = ((hanLong.size.width - hanShort.size.width).toFloat() / 20f) / 2f
            val w = maxOf(1f, minOf(latinW, hanCellW))
            val h = short.size.height.toFloat()
            w to h
        }

        // viewportSize 不按 tab.id 重置:同一个 terminal viewport,切 tab 不该清零。
        // 若按 tab.id 重置,切 tab 后 onSizeChanged 的 previousSize(LayoutNode 复用)
        // 仍是上一个 tab 测出的尺寸,同尺寸不触发回调,导致 contentSize 卡在 0。
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        // 重组只在整数 cell 边界跨越时触发,IME 动画逐像素变化不会每帧重组 ShellBody。
        val cols by remember(charW) {
            derivedStateOf {
                // -1 吸收亚像素布局松弛,否则最右一格会被 `clipToBounds` 裁掉。
                if (charW > 0f) ((viewportSize.width / charW).toInt() - 1).coerceAtLeast(0) else 0
            }
        }
        val rows by remember(charH) {
            derivedStateOf {
                if (charH > 0f) (viewportSize.height / charH).toInt() else 0
            }
        }
        // tab.id 进 key:切 tab 时即便 cols/rows 与前一个 tab 相同也强制对新 tab 再 reconcile 一次,
        // 否则 SessionManager 里 tab.cols 和真实尺寸一旦错位(比如另一个 tab 开过键盘缩过)就没人能纠正。
        LaunchedEffect(tab.id, cols, rows) {
            // 初次测量可能给出极小值(布局 pass 还没跑完);给 pty 发这种尺寸没意义。
            if (cols >= 20 && rows >= 5) {
                manager.resizeTerminal(tab.id, cols.toUShort(), rows.toUShort())
            }
        }

        val ctx = androidx.compose.ui.platform.LocalContext.current
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        // live-read:缩放更新映射但不重启 pointerInput 协程。
        val charWLatest by androidx.compose.runtime.rememberUpdatedState(charW)
        val charHLatest by androidx.compose.runtime.rememberUpdatedState(charH)
        val fontSizeLatest by androidx.compose.runtime.rememberUpdatedState(fontSize)
        Box(
            modifier = Modifier
                .fillMaxWidth().weight(1f)
                .background(termBg)
                .padding(TERM_PAD)
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                .terminalGestures(
                    key = tab.id,
                    charW = { charWLatest },
                    charH = { charHLatest },
                    onLeftClick = { col, row ->
                        if (tab.selection != null) {
                            tab.selection = null
                        } else if (tab.mouseMode) {
                            onSendBytes(sgrMouseClick(MouseBtn.LEFT, col, row))
                        }
                    },
                    onRightClick = { col, row ->
                        if (tab.selection != null) {
                            tab.selection = null
                        } else if (tab.mouseMode) {
                            onSendBytes(sgrMouseClick(MouseBtn.RIGHT, col, row))
                        }
                    },
                    onWheelStep = { col, row, steps -> dispatchWheel(tab, manager, onSendBytes, col, row, steps) },
                    onSwipeRight = { onSendBytes(TAB_BYTES) },
                    onSwipeLeft = { onSendBytes(SHIFT_TAB_BYTES) },
                    onPinch = { zoom ->
                        // 手势边界可能给出 NaN / Infinity / ≤0,会污染 fontSize 导致文本布局崩溃。
                        if (zoom.isFinite() && zoom > 0f) {
                            val next = (fontSizeLatest * zoom).coerceIn(
                                com.simpssh.data.TERM_FONT_SIZE_MIN,
                                com.simpssh.data.TERM_FONT_SIZE_MAX,
                            )
                            if (next.isFinite() && next != fontSizeLatest) {
                                onFontSizeChange(next)
                            }
                        }
                    },
                    onLongPress = { col, row ->
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                        val rowIdx = row - 1
                        val colIdx = col - 1
                        val word = wordCellBoundsAt(tab.rows, rowIdx, colIdx)
                        tab.selection = if (word != null) {
                            TerminalSelection(rowIdx, word.first, rowIdx, word.last)
                        } else {
                            TerminalSelection(rowIdx, colIdx, rowIdx, colIdx)
                        }
                        tab.selectionDragging = true
                    },
                    onSelectionExtend = { col, row ->
                        tab.selection?.let { tab.selection = it.copy(endRow = row - 1, endCol = col - 1) }
                    },
                    onSelectionCommit = { tab.selectionDragging = false },
                ),
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(tab.rows) { i, row ->
                    TerminalRowCanvas(
                        row = row,
                        cursorOffset = if (i == tab.cursorRow) tab.cursorCol else null,
                        selectionCells = tab.selection?.let { selectionCellRange(it, i, row.text) },
                        termStyle = termStyle,
                        charW = charW,
                        charH = charH,
                        themeFg = termFg,
                        themeBg = termBg,
                        measurer = measurer,
                    )
                }
            }
            tab.selection?.let { sel ->
                // 使用原始 anchor/end(不做归一化):每个 handle 各自拥有一个字段,
                // 这样即便拖动中途选区反向,手指也仍然粘在"自己那头"的端点上。
                SelectionHandle(
                    cellCol = sel.anchorCol,
                    cellRow = sel.anchorRow,
                    charW = charW,
                    charH = charH,
                    onDragStart = { tab.selectionDragging = true },
                    onDragEnd = { tab.selectionDragging = false },
                    onDragTo = { r, c ->
                        tab.selection = tab.selection?.copy(anchorRow = r, anchorCol = c)
                    },
                )
                SelectionHandle(
                    cellCol = sel.endCol + 1,
                    cellRow = sel.endRow,
                    charW = charW,
                    charH = charH,
                    onDragStart = { tab.selectionDragging = true },
                    onDragEnd = { tab.selectionDragging = false },
                    onDragTo = { r, c ->
                        tab.selection = tab.selection?.copy(endRow = r, endCol = c)
                    },
                )
                if (!tab.selectionDragging) {
                    SelectionFloatingBar(
                        selection = sel,
                        charW = charW,
                        charH = charH,
                        onCopy = {
                            val text = selectionText(tab.rows, sel)
                            if (text.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(text))
                                android.widget.Toast
                                    .makeText(ctx, "已复制 ${text.length} 字符", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                            tab.selection = null
                        },
                        onPaste = {
                            val text = clipboard.getText()?.text
                            tab.selection = null
                            if (!text.isNullOrEmpty()) onSendBytes(pasteBytes(tab, text))
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showOverflow,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            InlineOverflowPanel(
                byGroup = overflowGrouped,
                mods = mods,
                onSendBytes = onSendBytes,
            )
        }

        // 隐藏的 IME 通道。必须 size(1.dp) + alpha(0f):0.dp 子节点会被 Compose 跳过,`focusReq.requestFocus()` 将失效。
        BasicTextField(
            value = shadow,
            onValueChange = { new ->
                // 必须始终 `shadow = new`:下一次 diff 要对准 IME 当前的真实 field 状态,
                // 否则残留的已提交部分会在下一次事件里被重复发送。
                val committed = shadow.newCommittedSlice(new)
                if (committed.isNotEmpty()) {
                    manager.sendTyped(tab.id, mods.consume(committed))
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
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            // 这里不要重置 `shadow`:有些 IME 在 Send 之后还保留其 field 内容,
            // 若把 shadow 清空,残留部分会在下一次 onValueChange 被再次发送。
            keyboardActions = KeyboardActions(onSend = {
                onSendBytes(byteArrayOf(0x0D))
            }),
        )
    }
}

@Composable
private fun TerminalToolbar(
    keyIds: List<String>,
    mods: ModifierState,
    onSendBytes: (ByteArray) -> Unit,
    overflowOpen: Boolean,
    mouseMode: Boolean,
    onToggleMouseMode: () -> Unit,
    onShowKeyboard: () -> Unit,
    onToggleOverflow: () -> Unit,
) {
    val byGroup = remember(keyIds) { keyIds.mapNotNull { keyById(it) }.groupedNonEmpty() }
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
            MouseModeToggle(on = mouseMode, onClick = onToggleMouseMode)
            GroupSeparator()
            byGroup.forEachIndexed { i, (_, groupKeys) ->
                if (i > 0) GroupSeparator()
                groupKeys.forEach { k -> ToolKey(k, mods, onSendBytes) }
            }
        }
        Spacer(Modifier.width(6.dp))
        RightSlot(
            overflowOpen = overflowOpen,
            onShowKeyboard = onShowKeyboard,
            onToggleOverflow = onToggleOverflow,
        )
    }
}

@Composable
private fun ModeBadge(label: String, active: Boolean) {
    val bg = if (active) androidx.compose.ui.graphics.Color(0xFF2E7D32)
             else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
    val fg = if (active) androidx.compose.ui.graphics.Color.White
             else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(color = fg),
        )
    }
}

@Composable
private fun MouseModeToggle(on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surface
    val fg = if (on) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        NerdIcon(NerdGlyphs.MOUSE_POINTER, "鼠标模式", size = 16.dp, tint = fg)
    }
}

@Composable
private fun GroupSeparator() {
    Spacer(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
    )
}

// 隔离 WindowInsets.ime 读取,避免 IME 动画逐帧触发整条工具栏重组。
@Composable
private fun RightSlot(
    overflowOpen: Boolean,
    onShowKeyboard: () -> Unit,
    onToggleOverflow: () -> Unit,
) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeVisible by remember(ime, density) {
        derivedStateOf { ime.getBottom(density) > 0 }
    }
    val (glyph, cd, action) = when {
        overflowOpen -> Triple(NerdGlyphs.TIMES, "收起按键面板", onToggleOverflow)
        imeVisible -> Triple(NerdGlyphs.ELLIPSIS_V, "更多按键", onToggleOverflow)
        else -> Triple(NerdGlyphs.KEYBOARD, "调出键盘", onShowKeyboard)
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        NerdIcon(
            glyph = glyph,
            contentDescription = cd,
            size = 18.dp,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ToolKey(
    key: TerminalKey,
    mods: ModifierState,
    onSendBytes: (ByteArray) -> Unit,
) {
    val mod = key.modifier
    val selected = mod != null && mods.isOn(mod)
    val bg = if (selected) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface
    val pressModifier = if (mod != null) {
        Modifier.clickable { mods.toggle(mod) }
    } else {
        Modifier.repeatOnHold {
            if (mods.isAnyOn()) mods.resetAll()
            key.bytes?.let(onSendBytes)
        }
    }
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .then(pressModifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (key.topLabel != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    key.topLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = fg.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                    ),
                )
                Text(
                    key.bottomLabel ?: key.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(color = fg),
                )
            }
        } else {
            Text(key.displayName, style = MaterialTheme.typography.labelLarge.copy(color = fg))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineOverflowPanel(
    byGroup: List<Pair<KeyGroup, List<TerminalKey>>>,
    mods: ModifierState,
    onSendBytes: (ByteArray) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = OVERFLOW_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (byGroup.isEmpty()) {
                Text(
                    "已没有更多按键 — 你的工具栏已包含所有可用按键。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            byGroup.forEach { (group, groupKeys) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        groupLabel(group),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        groupKeys.forEach { k -> ToolKey(k, mods, onSendBytes) }
                    }
                }
            }
        }
    }
}

private val OVERFLOW_PANEL_MAX_HEIGHT = 260.dp

internal fun groupLabel(g: KeyGroup): String = when (g) {
    KeyGroup.Control     -> "控制"
    KeyGroup.Tabs        -> "Tab"
    KeyGroup.Modifiers   -> "修饰键"
    KeyGroup.Navigation  -> "导航"
    KeyGroup.Arrows      -> "方向"
    KeyGroup.FKeys       -> "功能键"
    KeyGroup.Punctuation -> "标点"
    KeyGroup.Other       -> "其他"
}

// Sarasa Mono SC:CJK 等宽字体,每个 CJK 字形正好 2 倍拉丁 advance,满足 wcwidth 的 cell 宽度不变量。
internal val SarasaFontFamily = FontFamily(Font(R.font.sarasa_mono_sc))

internal val TerminalTextStyle: TextStyle =
    TextStyle(
        fontFamily = SarasaFontFamily,
        fontSize = 11.sp,
        // includeFontPadding = false 是等宽对齐的必要条件,否则每行上下会多出 font padding 把网格撑歪。
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        // Compose 默认的 tracking(~0.5sp)会破坏 monospace 对齐。
        letterSpacing = 0.sp,
    )

// `minOf`/`maxOf` 是为了防御部分 IME 返回反向的 composition range。
private fun TextFieldValue.committedOnly(): String {
    val comp = composition ?: return text
    val len = text.length
    val lo = minOf(comp.start, comp.end).coerceIn(0, len)
    val hi = maxOf(comp.start, comp.end).coerceIn(0, len)
    if (lo == 0) return text.substring(hi)
    if (hi == len) return text.substring(0, lo)
    return text.substring(0, lo) + text.substring(hi)
}

// 对"已提交"部分做 diff(而非对 `.text` 整体):用来处理 IME 在一次更新里
// 把"提交上一音节 + 开始下一段 composition"链在一起的情况。
private fun TextFieldValue.newCommittedSlice(next: TextFieldValue): String {
    val oldCommitted = this.committedOnly()
    val newCommitted = next.committedOnly()
    if (newCommitted.isEmpty() || newCommitted == oldCommitted) return ""

    val prefix = oldCommitted.commonPrefixWith(newCommitted).length
    val oldTail = oldCommitted.substring(prefix)
    val newTail = newCommitted.substring(prefix)
    val suffix = oldTail.commonSuffixWith(newTail).length
    return newCommitted.substring(prefix, newCommitted.length - suffix)
}

// 紧凑内边距:手机上每 1dp padding 大约多占 1 个字符列。
private val TERM_PAD = 4.dp

// EAW-Ambiguous 字形:Sarasa Mono 按 2 个 cell 的 advance 渲染,但 alacritty 的网格里算 1 个 cell。
// 这里居中绘制 + 裁剪到 1-cell 槽位,让网格数学和可见像素一致(属于 Sarasa ink 与 alacritty 网格宽度的已知不一致)。
// 只对 cellWidth==1 的字符生效:下方 check 已显式守卫,所以收录范围可以放宽。
private fun isAmbigWideGlyph(codePoint: Int): Boolean = when (codePoint) {
    0x2014, 0x2015, 0x2026 -> true // em-dash / horizontal bar / ellipsis
    in 0x2190..0x21FF,             // 箭头
    in 0x2600..0x26FF,             // Miscellaneous Symbols
    in 0x2700..0x27BF,             // Dingbats(含 ✓ ✗ 等)
    in 0x2B00..0x2BFF,             // Miscellaneous Symbols and Arrows
    -> true
    else -> false
}

// 必须与 `core/src/terminal.rs` 的 DEFAULT_FG / DEFAULT_BG 保持一致
// (DEFAULT_FG = 0xD3D7CF, DEFAULT_BG = 0x000000)。fg/bg 等于这两个值的 cell 会被当作
// "使用主题色"而非字面颜色——如果改动 Rust 端默认值,这里必须同步更新。
private const val RUST_DEFAULT_FG = 0xD3D7CF
private const val RUST_DEFAULT_BG = 0x000000

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

@Composable
private fun TerminalRowCanvas(
    row: StyledRow,
    cursorOffset: Int?,
    selectionCells: IntRange?,
    termStyle: TextStyle,
    charW: Float,
    charH: Float,
    themeFg: Color,
    themeBg: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val glyphs = remember(row, themeFg, themeBg) { row.toTerminalGlyphs(themeFg, themeBg) }
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val density = LocalDensity.current
    val heightDp = with(density) { charH.toDp() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        val cursorCell = cursorOffset?.let { textOffsetToCellIndex(row.text, it) }

        // 绘制顺序 bg → 选区 → 光标 → 文字:选区必须叠在 cell 背景之上,否则 ANSI 着色行会盖住选区。
        var cellX = 0f
        for (glyph in glyphs) {
            if (cellX >= size.width) break
            val widthPx = charW * glyph.cellWidth
            if (glyph.style.background.alpha > 0f && glyph.style.background != themeBg) {
                drawRect(
                    color = glyph.style.background,
                    topLeft = Offset(cellX, 0f),
                    size = Size(widthPx, size.height),
                )
            }
            cellX += widthPx
        }

        if (selectionCells != null && !selectionCells.isEmpty()) {
            val x = selectionCells.first * charW
            val widthPx = (selectionCells.last - selectionCells.first + 1) * charW
            drawRect(
                color = selectionColor,
                topLeft = Offset(x, 0f),
                size = Size(widthPx, size.height),
            )
        }

        if (cursorCell != null && cursorCell >= 0) {
            drawRect(
                color = themeFg,
                topLeft = Offset(cursorCell.toFloat() * charW, 0f),
                size = Size(charW, size.height),
            )
        }

        cellX = 0f
        for (glyph in glyphs) {
            // 当 topLeft.x 超过 canvas 宽度时,drawText 会以 `maxWidth(-N) must be >= 0` 崩溃。
            if (cellX >= size.width) break
            val widthPx = charW * glyph.cellWidth
            val drawStyle = termStyle.copy(
                color = if (cursorCell == glyph.cellStart) themeBg else glyph.style.color,
                background = Color.Transparent,
                fontWeight = glyph.style.fontWeight,
                fontStyle = glyph.style.fontStyle,
                textDecoration = glyph.style.textDecoration,
            )
            val cp = Character.codePointAt(glyph.text, 0)
            // cellWidth==1 的守卫:isAmbigWideGlyph 覆盖的箭头/符号/dingbats 范围
            // 也包含部分 Wide 字符(✅ ⭐ 等),它们已经按 2 格画,不需要再裁剪。
            if (glyph.cellWidth == 1 && isAmbigWideGlyph(cp)) {
                clipRect(
                    left = cellX, top = 0f,
                    right = cellX + widthPx, bottom = size.height,
                ) {
                    drawText(
                        textMeasurer = measurer,
                        text = glyph.text,
                        style = drawStyle,
                        topLeft = Offset(cellX - charW * 0.5f, 0f),
                    )
                }
            } else {
                drawText(
                    textMeasurer = measurer,
                    text = glyph.text,
                    style = drawStyle,
                    topLeft = Offset(cellX, 0f),
                )
            }
            cellX += widthPx
        }
    }
}

private data class TerminalGlyph(
    val text: String,
    val cellStart: Int,
    val cellWidth: Int,
    val style: SpanStyle,
)

private fun StyledRow.toTerminalGlyphs(themeFg: Color, themeBg: Color): List<TerminalGlyph> {
    val out = ArrayList<TerminalGlyph>(text.length)
    var utf16 = 0
    var cell = 0
    while (utf16 < text.length) {
        val cp = Character.codePointAt(text, utf16)
        val len = Character.charCount(cp)
        val slice = text.substring(utf16, utf16 + len)
        out += TerminalGlyph(
            text = slice,
            cellStart = cell,
            cellWidth = codePointCellWidth(cp),
            style = styleAt(utf16, themeFg, themeBg),
        )
        utf16 += len
        cell += out.last().cellWidth
    }
    return out
}

private fun StyledRow.styleAt(offset: Int, themeFg: Color, themeBg: Color): SpanStyle {
    val sp = spans.firstOrNull { offset >= it.start.toInt() && offset < (it.start + it.len).toInt() }
    if (sp == null) return SpanStyle(color = themeFg, background = themeBg)
    val fgInt = sp.fg.toInt() and 0xFFFFFF
    val bgInt = sp.bg.toInt() and 0xFFFFFF
    val fg = if (fgInt == RUST_DEFAULT_FG) themeFg else Color(0xFF000000.toInt() or fgInt)
    val bg = if (bgInt == RUST_DEFAULT_BG) themeBg else Color(0xFF000000.toInt() or bgInt)
    val flags = sp.flags.toInt()
    return SpanStyle(
        color = fg,
        background = bg,
        fontWeight = if (flags and 0b0001 != 0) FontWeight.Bold else null,
        fontStyle = if (flags and 0b0010 != 0) FontStyle.Italic else null,
        textDecoration = if (flags and 0b0100 != 0) TextDecoration.Underline else null,
    )
}

internal data class TerminalSelection(
    val anchorRow: Int,
    val anchorCol: Int,
    val endRow: Int,
    val endCol: Int,
)

private data class NormalizedSelection(
    val topRow: Int,
    val topCol: Int,
    val botRow: Int,
    val botCol: Int,
)

private fun TerminalSelection.normalized(): NormalizedSelection {
    val forward = anchorRow < endRow ||
        (anchorRow == endRow && anchorCol <= endCol)
    return if (forward) NormalizedSelection(anchorRow, anchorCol, endRow, endCol)
    else NormalizedSelection(endRow, endCol, anchorRow, anchorCol)
}

private fun rowCellWidth(text: String): Int {
    var utf16 = 0
    var cells = 0
    while (utf16 < text.length) {
        val cp = Character.codePointAt(text, utf16)
        cells += codePointCellWidth(cp).coerceAtLeast(1)
        utf16 += Character.charCount(cp)
    }
    return cells
}

private fun cellToUtf16(text: String, targetCell: Int): Int {
    var utf16 = 0
    var cells = 0
    while (utf16 < text.length && cells < targetCell) {
        val cp = Character.codePointAt(text, utf16)
        cells += codePointCellWidth(cp).coerceAtLeast(1)
        utf16 += Character.charCount(cp)
    }
    return utf16
}

internal fun selectionCellRange(sel: TerminalSelection, rowIdx: Int, lineText: String): IntRange? {
    val (topRow, topCol, botRow, botCol) = sel.normalized()
    if (rowIdx < topRow || rowIdx > botRow) return null
    val start = if (rowIdx == topRow) topCol else 0
    val end = if (rowIdx == botRow) botCol
    else (rowCellWidth(lineText) - 1).coerceAtLeast(0)
    if (end < start) return null
    return start..end
}

internal fun selectionText(rows: List<StyledRow>, sel: TerminalSelection): String {
    val (topRow, topCol, botRow, botCol) = sel.normalized()
    val sb = StringBuilder()
    for (r in topRow..botRow) {
        val line = rows.getOrNull(r)?.text ?: continue
        val startCell = if (r == topRow) topCol else 0
        val startUtf16 = cellToUtf16(line, startCell)
        // 非末行整行都在选中范围内 —— 直接用 UTF-16 长度;以前用
        // `Int.MAX_VALUE + 1` 会溢出成 Int.MIN_VALUE,cellToUtf16 拿到负的
        // 目标值后什么都不追加,导致跨行复制只留下最后一行。
        val endUtf16 = if (r == botRow) {
            cellToUtf16(line, botCol + 1).coerceAtMost(line.length)
        } else {
            line.length
        }
        if (endUtf16 > startUtf16) sb.append(line, startUtf16, endUtf16)
        if (r != botRow) sb.append('\n')
    }
    return sb.toString().trimEnd()
}

internal fun wordCellBoundsAt(rows: List<StyledRow>, rowIdx: Int, cellCol: Int): IntRange? {
    val text = rows.getOrNull(rowIdx)?.text ?: return null
    if (text.isEmpty()) return null
    data class C(val cp: Int, val cellStart: Int, val cellWidth: Int)
    val cells = ArrayList<C>(text.length)
    var utf16 = 0
    var cell = 0
    while (utf16 < text.length) {
        val cp = Character.codePointAt(text, utf16)
        val w = codePointCellWidth(cp).coerceAtLeast(1)
        cells.add(C(cp, cell, w))
        utf16 += Character.charCount(cp)
        cell += w
    }
    val hit = cells.indexOfFirst { cellCol < it.cellStart + it.cellWidth }
    if (hit < 0) return null
    if (Character.isWhitespace(cells[hit].cp)) return null
    var lo = hit; while (lo > 0 && !Character.isWhitespace(cells[lo - 1].cp)) lo--
    var hi = hit; while (hi < cells.size - 1 && !Character.isWhitespace(cells[hi + 1].cp)) hi++
    return cells[lo].cellStart..(cells[hi].cellStart + cells[hi].cellWidth - 1)
}

internal fun pasteBytes(tab: TabState, text: String): ByteArray {
    val raw = text.toByteArray(Charsets.UTF_8)
    return if (tab.term?.isBracketedPaste() == true) {
        BRACKETED_PASTE_START + raw + BRACKETED_PASTE_END
    } else raw
}

@Composable
private fun SelectionHandle(
    cellCol: Int,
    cellRow: Int,
    charW: Float,
    charH: Float,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragTo: (row: Int, col: Int) -> Unit,
) {
    val touchSizeDp = 40.dp
    val visualRadiusDp = 7.dp
    val density = LocalDensity.current
    val touchSizePx = with(density) { touchSizeDp.toPx() }
    val visualRadiusPx = with(density) { visualRadiusDp.toPx() }
    val anchorX = cellCol * charW
    val anchorY = (cellRow + 1) * charH
    val offsetX = (anchorX - touchSizePx / 2).toInt()
    val offsetY = (anchorY - touchSizePx / 4).toInt()
    val color = MaterialTheme.colorScheme.primary
    val latestStart by androidx.compose.runtime.rememberUpdatedState(onDragStart)
    val latestEnd by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    // rememberUpdatedState 给协程持续的 live 访问:onDragTo 提交新 cell 后 handle 用新 offset 重组,
    // 但这个 pointerInput 协程以 (charW, charH) 为 key,不会重启,仍在跑。
    // 如果直接捕获 offsetX/offsetY,下一次事件会把手指映射回旧 cell,选区在两 cell 之间反复抖动。
    val liveOffsetX by androidx.compose.runtime.rememberUpdatedState(offsetX)
    val liveOffsetY by androidx.compose.runtime.rememberUpdatedState(offsetY)
    Canvas(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(touchSizeDp)
            .pointerInput(charW, charH) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    latestStart()
                    var lastCol = -1
                    var lastRow = -1
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull() ?: break
                            p.consume()
                            val tx = liveOffsetX + p.position.x
                            val ty = liveOffsetY + p.position.y - charH / 2f
                            val newCol = cellWithHysteresis(tx / charW, lastCol)
                            val newRow = cellWithHysteresis(ty / charH, lastRow, ROW_HYSTERESIS_THRESHOLD)
                            if (newCol != lastCol || newRow != lastRow) {
                                lastCol = newCol
                                lastRow = newRow
                                onDragTo(newRow, newCol)
                            }
                            if (!p.pressed) break
                        }
                    } finally {
                        latestEnd()
                    }
                }
            },
    ) {
        drawCircle(
            color = color,
            radius = visualRadiusPx,
            center = Offset(size.width / 2f, size.height / 4f),
        )
    }
}

// 用 `Popup` 让浮条单独走一个 window:避免 barSize 反馈式重组循环,
// 不和 LazyColumn 抢 z-order,也没有 offset lambda 的抖动。
@Composable
private fun SelectionFloatingBar(
    selection: TerminalSelection,
    charW: Float,
    charH: Float,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.toPx() }.toInt()
    val (topRow, topCol, botRow, botCol) = selection.normalized()
    val selTop = (topRow * charH).toInt()
    val selBot = ((botRow + 1) * charH).toInt()
    val selMidX = ((topCol * charW + (botCol + 1) * charW) / 2f).toInt()

    val provider = remember(selTop, selBot, selMidX, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val preferredY = selTop - popupContentSize.height - gapPx
                val yOffsetInAnchor = if (preferredY >= 0) preferredY else selBot + gapPx
                val xOffsetInAnchor = selMidX - popupContentSize.width / 2
                val absX = (anchorBounds.left + xOffsetInAnchor)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val absY = (anchorBounds.top + yOffsetInAnchor)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(absX, absY)
            }
        }
    }

    Popup(popupPositionProvider = provider) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionActionChip("复制", onCopy)
                SelectionActionChip("粘贴", onPaste)
            }
        }
    }
}

@Composable
private fun SelectionActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun textOffsetToCellIndex(text: String, utf16Offset: Int): Int {
    var offset = 0
    var cells = 0
    while (offset < text.length && offset < utf16Offset) {
        val cp = Character.codePointAt(text, offset)
        val len = Character.charCount(cp)
        cells += codePointCellWidth(cp)
        offset += len
    }
    return cells
}

private fun codePointCellWidth(codePoint: Int): Int {
    // 热路径:ASCII + Latin-1 (< 0x0300) 都是 1-cell 且没有组合记号,直接返回。
    // 0x0300 以下覆盖拉丁字母、数字、常用标点,是终端输出的绝大多数。
    if (codePoint < 0x0300) return 1
    if (Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
        Character.getType(codePoint) == Character.ENCLOSING_MARK.toInt() ||
        Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt() ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xE0100..0xE01EF
    ) {
        return 0
    }
    return when {
        Character.isISOControl(codePoint) -> 1
        // 宽度以 alacritty 的网格为准,而非 Sarasa 的 ink 宽度。
        // Sarasa 画得偏宽的 EAW-Ambiguous 字形(em-dash、省略号等)在这里仍然算 1 cell
        // ——渲染器会把它们居中并裁剪到 1-cell 槽位里。
        // 下一行的 two/three-em dashes 属于 EAW Wide,因此占 2 cell。
        codePoint in 0x2E3A..0x2E3B -> 2
        // Misc Technical / Dingbats / Misc Symbols / Misc Symbols & Arrows 里
        // 按 EAW Wide 的散点(✅ ⭐ ❓ ❗ ⚠️ 等 emoji-like 符号)。漏掉会导致
        // alacritty 给 2 cell 我画 1 cell,整行排版错位。
        codePoint in 0x231A..0x231B ||
        codePoint in 0x23E9..0x23EC ||
        codePoint == 0x23F0 ||
        codePoint == 0x23F3 ||
        codePoint in 0x25FD..0x25FE ||
        codePoint in 0x2614..0x2615 ||
        codePoint in 0x2648..0x2653 ||
        codePoint == 0x267F ||
        codePoint == 0x2693 ||
        codePoint == 0x26A1 ||
        codePoint in 0x26AA..0x26AB ||
        codePoint in 0x26BD..0x26BE ||
        codePoint in 0x26C4..0x26C5 ||
        codePoint == 0x26CE ||
        codePoint == 0x26D4 ||
        codePoint == 0x26EA ||
        codePoint in 0x26F2..0x26F3 ||
        codePoint == 0x26F5 ||
        codePoint == 0x26FA ||
        codePoint == 0x26FD ||
        codePoint == 0x2705 ||
        codePoint in 0x270A..0x270B ||
        codePoint == 0x2728 ||
        codePoint == 0x274C ||
        codePoint == 0x274E ||
        codePoint in 0x2753..0x2755 ||
        codePoint == 0x2757 ||
        codePoint in 0x2795..0x2797 ||
        codePoint == 0x27B0 ||
        codePoint == 0x27BF ||
        codePoint in 0x2B1B..0x2B1C ||
        codePoint == 0x2B50 ||
        codePoint == 0x2B55 -> 2
        codePoint in 0x1100..0x115F ||
        codePoint in 0x2329..0x232A ||
        codePoint in 0x2E80..0xA4CF ||
        codePoint in 0xA960..0xA97F ||
        codePoint in 0xAC00..0xD7A3 ||
        codePoint in 0xD7B0..0xD7FF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0xFE10..0xFE19 ||
        codePoint in 0xFE30..0xFE6F ||
        codePoint in 0xFF01..0xFF60 ||
        codePoint in 0xFFE0..0xFFE6 ||
        codePoint in 0x16FE0..0x16FE4 ||
        codePoint in 0x17000..0x187F7 ||
        codePoint in 0x18800..0x18CD5 ||
        codePoint in 0x18D00..0x18D08 ||
        codePoint in 0x1B000..0x1B2FB ||
        codePoint in 0x1F200..0x1F251 ||
        codePoint in 0x1F300..0x1FAFF ||
        codePoint in 0x20000..0x3FFFD -> 2
        else -> 1
    }
}

// 一次滑动写一次(把 N 步字节拼接起来),避免逐步路径带来的 N 次协程启动 + N 次 `resetScroll` 互斥锁获取。
private fun dispatchWheel(
    tab: TabState,
    manager: SessionManager,
    onSendBytes: (ByteArray) -> Unit,
    col: Int,
    row: Int,
    steps: Int,
) {
    if (steps == 0) return
    val term = tab.term
    val up = steps < 0
    val count = kotlin.math.abs(steps)
    when {
        tab.mouseMode && term?.isMouseReporting() == true -> {
            val btn = if (up) MouseBtn.WHEEL_UP else MouseBtn.WHEEL_DOWN
            onSendBytes(sgrMouseBytes(btn, col, row, press = true).repeated(count))
        }
        term?.isAltScreen() == true -> {
            onSendBytes(keyBytes(if (up) "ArrowUp" else "ArrowDown").repeated(count))
        }
        else -> manager.scrollTerminal(tab.id, steps)
    }
}

private fun ByteArray.repeated(n: Int): ByteArray {
    if (n == 1) return this
    val out = ByteArray(size * n)
    repeat(n) { System.arraycopy(this, 0, out, it * size, size) }
    return out
}

private fun keyBytes(id: String): ByteArray =
    checkNotNull(keyById(id)?.bytes) { "key $id has no bytes" }

private fun specialKeyBytes(ev: KeyEvent): ByteArray? {
    return when (ev.key) {
        Key.DirectionUp    -> "\u001B[A".toByteArray(StandardCharsets.US_ASCII)
        Key.DirectionDown  -> "\u001B[B".toByteArray(StandardCharsets.US_ASCII)
        Key.DirectionRight -> "\u001B[C".toByteArray(StandardCharsets.US_ASCII)
        Key.DirectionLeft  -> "\u001B[D".toByteArray(StandardCharsets.US_ASCII)
        Key.Escape         -> byteArrayOf(0x1B)
        Key.Tab            -> byteArrayOf(0x09)
        Key.Backspace      -> byteArrayOf(0x7F)
        Key.MoveHome       -> "\u001B[H".toByteArray(StandardCharsets.US_ASCII)
        Key.MoveEnd        -> "\u001B[F".toByteArray(StandardCharsets.US_ASCII)
        Key.Delete         -> "\u001B[3~".toByteArray(StandardCharsets.US_ASCII)
        Key.PageUp         -> "\u001B[5~".toByteArray(StandardCharsets.US_ASCII)
        Key.PageDown       -> "\u001B[6~".toByteArray(StandardCharsets.US_ASCII)
        Key.F1             -> "\u001BOP".toByteArray(StandardCharsets.US_ASCII)
        Key.F2             -> "\u001BOQ".toByteArray(StandardCharsets.US_ASCII)
        Key.F3             -> "\u001BOR".toByteArray(StandardCharsets.US_ASCII)
        Key.F4             -> "\u001BOS".toByteArray(StandardCharsets.US_ASCII)
        Key.F5             -> "\u001B[15~".toByteArray(StandardCharsets.US_ASCII)
        Key.F6             -> "\u001B[17~".toByteArray(StandardCharsets.US_ASCII)
        Key.F7             -> "\u001B[18~".toByteArray(StandardCharsets.US_ASCII)
        Key.F8             -> "\u001B[19~".toByteArray(StandardCharsets.US_ASCII)
        Key.F9             -> "\u001B[20~".toByteArray(StandardCharsets.US_ASCII)
        Key.F10            -> "\u001B[21~".toByteArray(StandardCharsets.US_ASCII)
        Key.F11            -> "\u001B[23~".toByteArray(StandardCharsets.US_ASCII)
        Key.F12            -> "\u001B[24~".toByteArray(StandardCharsets.US_ASCII)
        else -> {
            if (ev.isCtrlPressed) {
                val cp = ev.utf16CodePoint
                when (cp) {
                    in 'a'.code..'z'.code -> byteArrayOf((cp - 'a'.code + 1).toByte())
                    in 'A'.code..'Z'.code -> byteArrayOf((cp - 'A'.code + 1).toByte())
                    '['.code -> byteArrayOf(0x1B)             // Ctrl+[ 等价于 Esc
                    '\\'.code -> byteArrayOf(0x1C)
                    ']'.code -> byteArrayOf(0x1D)
                    else -> null
                }
            } else null
        }
    }
}
