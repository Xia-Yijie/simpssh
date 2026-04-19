package com.simpssh.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.request.ImageRequest
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import uniffi.simpssh_core.OpenFile
import uniffi.simpssh_core.SftpSession

internal sealed class PreviewKind {
    abstract val path: String
    data class Code(
        override val path: String,
        val text: String,
        val initialLanguage: LangSpec,
    ) : PreviewKind()
    data class Image(override val path: String, val bytes: ByteArray) : PreviewKind() {
        override fun equals(other: Any?) = other is Image && other.path == path && other.bytes === bytes
        override fun hashCode() = path.hashCode() * 31 + System.identityHashCode(bytes)
    }
    data class Media(override val path: String, val size: Long) : PreviewKind()
}

private val IMAGE_EXT = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif",
)

private val MEDIA_EXT = setOf(
    "mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts",
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus",
)

internal suspend fun openFilePreview(
    manager: SessionManager,
    tab: TabState,
    entry: uniffi.simpssh_core.DirEntry,
): PreviewKind? {
    // 目录快照可能过时;重新 stat 避开 SftpCache key 指向旧版本 → 命中旧缓存读老内容。
    val fresh = manager.statEntry(tab, entry.path) ?: entry
    val ext = fresh.name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXT -> {
            // 图片需整文件解码;封顶 IMAGE_MAX_BYTES 防 OOM。
            val cap = minOf(fresh.size.toLong(), IMAGE_MAX_BYTES.toLong()).toInt()
            val bytes = manager.readFileBytesCached(tab, fresh, max = cap) ?: return null
            PreviewKind.Image(fresh.path, bytes)
        }
        ext in MEDIA_EXT -> PreviewKind.Media(fresh.path, fresh.size.toLong())
        else -> {
            val bytes = manager.readFileBytesCached(tab, fresh) ?: return null
            val name = fresh.name.lowercase()
            // 文件名优先于后缀:Makefile / Dockerfile 等无扩展名文件靠后缀永远命不中。
            val lang = CODE_LANGUAGE_BY_FILENAME[name]
                ?: CODE_LANGUAGE_BY_EXT[ext]
                ?: LANG_PLAIN
            PreviewKind.Code(fresh.path, decodeText(bytes), lang)
        }
    }
}

@Composable
internal fun PreviewDialogs(
    preview: PreviewKind?,
    sftp: SftpSession?,
    onDismiss: () -> Unit,
) {
    if (preview == null) return
    when (preview) {
        is PreviewKind.Code -> CodePreviewDialog(preview, onDismiss)
        is PreviewKind.Image -> ImagePreviewDialog(preview, onDismiss)
        is PreviewKind.Media -> {
            if (sftp != null) MediaPreviewDialog(preview, sftp, onDismiss)
            else onDismiss()
        }
    }
}

@Composable
private fun CodePreviewDialog(preview: PreviewKind.Code, onDismiss: () -> Unit) {
    var language by remember(preview.path) { mutableStateOf(preview.initialLanguage) }
    var wordWrap by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val lineCount = remember(preview.text) { preview.text.count { it == '\n' } + 1 }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CodeTitleBar(
                    path = preview.path,
                    language = language,
                    wordWrap = wordWrap,
                    onLanguageChange = { language = it },
                    onToggleWordWrap = { wordWrap = !wordWrap },
                    onCopyAll = {
                        clipboard.setText(AnnotatedString(preview.text))
                        android.widget.Toast.makeText(
                            ctx, "已复制 ${preview.text.length} 字符",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onDismiss = onDismiss,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CodeBody(
                    text = preview.text,
                    language = language,
                    wordWrap = wordWrap,
                    lineCount = lineCount,
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CodeStatusBar(
                    lineCount = lineCount,
                    charCount = preview.text.length,
                    language = language,
                )
            }
        }
    }
}

@Composable
private fun CodeTitleBar(
    path: String,
    language: LangSpec,
    wordWrap: Boolean,
    onLanguageChange: (LangSpec) -> Unit,
    onToggleWordWrap: () -> Unit,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val fileName = remember(path) { path.substringAfterLast('/') }
    val parentDir = remember(path) { path.substringBeforeLast('/', "").ifEmpty { "/" } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            NerdIcon(NerdGlyphs.ARROW_LEFT, "返回", size = 16.dp)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                fileName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                parentDir,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SarasaFontFamily),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
        Box {
            TextButton(
                onClick = { menuOpen = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text(language.label, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                NerdIcon(NerdGlyphs.CHEVRON_DOWN, null, size = 10.dp)
            }
            LanguagePickerMenu(
                expanded = menuOpen,
                current = language,
                onPick = { onLanguageChange(it); menuOpen = false },
                onDismiss = { menuOpen = false },
            )
        }
        ToolbarToggle(
            glyph = NerdGlyphs.WRAP_TEXT,
            active = wordWrap,
            contentDescription = "自动换行",
            onClick = onToggleWordWrap,
        )
        IconButton(onClick = onCopyAll, modifier = Modifier.size(40.dp)) {
            NerdIcon(NerdGlyphs.COPY, "复制全部", size = 16.dp)
        }
    }
}

@Composable
private fun LanguagePickerMenu(
    expanded: Boolean,
    current: LangSpec,
    onPick: (LangSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(expanded) { mutableStateOf("") }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 420.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索语言", style = MaterialTheme.typography.labelMedium) },
            singleLine = true,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).width(220.dp),
            textStyle = MaterialTheme.typography.labelMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
        val filtered = remember(query) {
            if (query.isBlank()) PICKER_LANGUAGES
            else PICKER_LANGUAGES.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }
        if (filtered.isEmpty()) {
            Text(
                "无匹配",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(16.dp),
            )
        }
        filtered.forEach { lang ->
            DropdownMenuItem(
                text = {
                    Text(
                        lang.label,
                        fontWeight = if (lang == current) FontWeight.SemiBold else null,
                    )
                },
                onClick = { onPick(lang) },
            )
        }
    }
}

@Composable
private fun ToolbarToggle(
    glyph: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .padding(4.dp)
            .background(bg, MaterialTheme.shapes.small),
    ) {
        NerdIcon(glyph, contentDescription, size = 14.dp, tint = fg)
    }
}

private fun sliceLines(
    annotated: AnnotatedString,
    text: String,
    lineStarts: IntArray,
    lineCount: Int,
): List<AnnotatedString> = List(lineCount) { i ->
    val start = lineStarts[i]
    val end = if (i + 1 < lineCount) lineStarts[i + 1] - 1 else text.length
    if (end > start) annotated.subSequence(start, end) else AnnotatedString("")
}

@Composable
private fun CodeBody(
    text: String,
    language: LangSpec,
    wordWrap: Boolean,
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    val isDark = !MaterialTheme.colorScheme.background.isLight()
    val lineStarts = remember(text, lineCount) {
        val starts = IntArray(lineCount)
        var pos = 0
        for (i in 0 until lineCount) {
            starts[i] = pos
            val nl = text.indexOf('\n', pos)
            pos = if (nl == -1) text.length + 1 else nl + 1
        }
        starts
    }
    // 预切 per-line AnnotatedString,避免每个可见行都对整串 subSequence(O(spans))。
    val plainSlices = remember(text, lineCount) {
        sliceLines(AnnotatedString(text), text, lineStarts, lineCount)
    }
    var lines by remember(text, language, isDark) { mutableStateOf(plainSlices) }
    LaunchedEffect(text, language, isDark) {
        lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val annotated = language.highlight(text, isDark)
            sliceLines(annotated, text, lineStarts, lineCount)
        }
    }
    val gutterDigits = lineCount.toString().length
    val gutterWidth = (gutterDigits * 9 + 16).dp
    val hState = rememberScrollState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gutterColor = onSurface.copy(alpha = 0.38f)
    val gutterBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(lineCount) { i ->
                CodeLineRow(
                    lineNumber = i + 1,
                    code = lines.getOrNull(i) ?: AnnotatedString(""),
                    wordWrap = wordWrap,
                    hState = hState,
                    gutterWidth = gutterWidth,
                    gutterColor = gutterColor,
                    gutterBg = gutterBg,
                    dividerColor = dividerColor,
                    onSurface = onSurface,
                )
            }
        }
    }
}

@Composable
private fun CodeLineRow(
    lineNumber: Int,
    code: AnnotatedString,
    wordWrap: Boolean,
    hState: androidx.compose.foundation.ScrollState,
    gutterWidth: androidx.compose.ui.unit.Dp,
    gutterColor: Color,
    gutterBg: Color,
    dividerColor: Color,
    onSurface: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = lineNumber.toString(),
            style = codeStyle.copy(color = gutterColor),
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(gutterWidth)
                .background(gutterBg)
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        )
        VerticalDivider(color = dividerColor, modifier = Modifier.fillMaxHeight())
        Text(
            text = code,
            style = codeStyle.copy(color = onSurface),
            softWrap = wordWrap,
            modifier = Modifier
                .weight(1f)
                .let { if (wordWrap) it else it.horizontalScroll(hState) }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CodeStatusBar(lineCount: Int, charCount: Int, language: LangSpec) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelStyle = MaterialTheme.typography.labelSmall.copy(
            fontFamily = SarasaFontFamily,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text("行 $lineCount", style = labelStyle)
        Text("字符 $charCount", style = labelStyle)
        Spacer(Modifier.weight(1f))
        Text(language.label, style = labelStyle)
    }
}

private val codeStyle = TextStyle(
    fontFamily = SarasaFontFamily,
    fontSize = 13.sp,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private fun Color.isLight(): Boolean {
    val r = red; val g = green; val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b > 0.5f
}

@Composable
private fun ImagePreviewDialog(preview: PreviewKind.Image, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PreviewTitleBar(preview.path, onDismiss, light = true)
                val ctx = LocalContext.current
                val request = remember(preview.bytes) {
                    ImageRequest.Builder(ctx).data(preview.bytes).build()
                }
                ZoomableAsyncImage(
                    model = request,
                    contentDescription = preview.path,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// DefaultMediaSourceFactory 不介入即可,内容由 SftpDataSource 喂。
private val MEDIA_PLACEHOLDER_URI: Uri = Uri.parse("sftp://simpssh/media")

// 防环形 cause 链。
private fun rootCauseOf(e: Throwable, maxDepth: Int = 16): Throwable {
    val seen = java.util.IdentityHashMap<Throwable, Unit>()
    var cur = e
    repeat(maxDepth) {
        val next = cur.cause ?: return cur
        if (seen.put(cur, Unit) != null) return cur
        cur = next
    }
    return cur
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPreviewDialog(
    preview: PreviewKind.Media,
    sftp: SftpSession,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val ctx = LocalContext.current
        val showStats = LocalShowMediaStats.current
        var isFullscreen by remember { mutableStateOf(false) }
        // FULL_SENSOR 无视系统旋转锁;依赖 MainActivity 的 configChanges=orientation 不重建 Activity。
        DisposableEffect(preview.path) {
            val activity = ctx as? android.app.Activity
            val prev = activity?.requestedOrientation
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            onDispose {
                activity?.requestedOrientation = prev
                    ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        LaunchedEffect(isFullscreen) {
            val activity = ctx as? android.app.Activity ?: return@LaunchedEffect
            activity.requestedOrientation = if (isFullscreen) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        }
        DisposableEffect(isFullscreen) {
            val activity = ctx as? android.app.Activity
            val window = activity?.window
            val controller = window?.let {
                androidx.core.view.WindowInsetsControllerCompat(it, it.decorView)
            }
            if (isFullscreen) {
                controller?.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isFullscreen) PreviewTitleBar(preview.path, onDismiss, light = true)
                var error by remember(preview.path) { mutableStateOf<String?>(null) }
                var stateLabel by remember(preview.path) { mutableStateOf("IDLE") }
                var playing by remember(preview.path) { mutableStateOf(false) }
                var curMs by remember(preview.path) { mutableStateOf(0L) }
                var durMs by remember(preview.path) { mutableStateOf(0L) }
                var buffered by remember(preview.path) { mutableStateOf(0L) }
                var statsLine by remember(preview.path) { mutableStateOf("") }
                LaunchedEffect(preview.path) { SftpDataSourceStats.reset() }
                val player = remember(preview.path, preview.size) {
                    val factory = DataSource.Factory { SftpDataSource(sftp, preview.path, preview.size) }
                    // ExoPlayer 默认 50s 才起播,SFTP 慢时卡 BUFFERING;激进缓冲 1.5s 起播,上限 15s。
                    val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5_000, 15_000, 1_500, 3_000)
                        .build()
                    ExoPlayer.Builder(ctx)
                        .setLoadControl(loadControl)
                        .build()
                        .apply {
                            // ProgressiveMediaSource 从内容嗅探;换 setMediaItem 遇无后缀 URI 会永卡 IDLE。
                            val source = ProgressiveMediaSource.Factory(factory)
                                .createMediaSource(MediaItem.fromUri(MEDIA_PLACEHOLDER_URI))
                            setMediaSource(source)
                            prepare()
                            playWhenReady = true
                        }
                }
                DisposableEffect(player) {
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                            val root = rootCauseOf(e)
                            error = buildString {
                                append(e.errorCodeName).append(": ").append(e.message ?: "")
                                if (root !== e) {
                                    append("\n→ ").append(root.javaClass.simpleName)
                                        .append(": ").append(root.message ?: "")
                                }
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (!showStats) return
                            stateLabel = when (state) {
                                androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                                androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                                androidx.media3.common.Player.STATE_READY -> "READY"
                                androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                                else -> "?"
                            }
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }
                // currentPosition / bufferedPosition 没有 listener 推送,只能轮询。
                LaunchedEffect(player, showStats) {
                    while (true) {
                        val nc = player.currentPosition
                        if (nc != curMs) curMs = nc
                        val nd = player.duration
                        if (nd != durMs) durMs = nd
                        val nb = player.bufferedPosition
                        if (nb != buffered) buffered = nb
                        if (showStats) {
                            val s = SftpDataSourceStats
                            val line = "DS: open=${s.opens} read=${s.reads} close=${s.closes} bytes=${humanBytes(s.bytes)}"
                            if (line != statsLine) statsLine = line
                        }
                        kotlinx.coroutines.delay(250)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                this.player = player
                                useController = true
                                controllerShowTimeoutMs = 3_000
                                controllerHideOnTouch = true
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                setShowPreviousButton(false)
                                setShowNextButton(false)
                                setShowShuffleButton(false)
                                setShowSubtitleButton(false)
                                setFullscreenButtonClickListener { next ->
                                    isFullscreen = next
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (showStats) {
                        Surface(
                            color = Color(0xCC000000),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    text = buildString {
                                        append(stateLabel)
                                        if (playing) append(" · PLAYING")
                                        if (durMs > 0) {
                                            append("   ")
                                            append(formatMs(curMs)).append(" / ").append(formatMs(durMs))
                                            append("   缓冲 ").append(formatMs(buffered))
                                        }
                                    },
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    text = statsLine,
                                    color = Color(0xFFBBBBBB),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                SftpDataSourceStats.lastError?.let {
                                    Text(
                                        text = "SFTP 错误: $it",
                                        color = Color(0xFFFF8888),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    error?.let { msg ->
                        Surface(
                            color = Color(0xCC800000),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Text(
                                msg,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms < 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun PreviewTitleBar(path: String, onDismiss: () -> Unit, light: Boolean = false) {
    val bg = if (light) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface
    val fg = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            path.substringAfterLast('/'),
            color = fg,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            NerdIcon(NerdGlyphs.TIMES, "关闭", size = 16.dp, tint = fg)
        }
    }
}

// MP4 extractor 会做几千次 8~4 KB 小读;open() 拿句柄,read() 从 PREFETCH_BYTES
// 的本地缓冲吃,miss 才打一次 SFTP READ —— 把几千个小 RTT 合成几百个 1 MB RTT。
@UnstableApi
private class SftpDataSource(
    private val sftp: SftpSession,
    private val remotePath: String,
    private val totalSize: Long,
) : BaseDataSource(/* isNetwork = */ true) {
    private var position: Long = 0
    private var remaining: Long = 0
    private var openedUri: Uri? = null
    private var handle: OpenFile? = null
    private val prefetch = ByteArray(PREFETCH_BYTES)
    private var prefetchStart: Long = 0
    private var prefetchLen: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) totalSize - position
        else dataSpec.length
        prefetchStart = 0
        prefetchLen = 0
        handle = try {
            sftp.openFile(remotePath)
        } catch (e: Throwable) {
            SftpDataSourceStats.lastError = "open: ${e.javaClass.simpleName}: ${e.message}"
            throw java.io.IOException("sftp openFile failed: ${e.message}", e)
        }
        transferStarted(dataSpec)
        SftpDataSourceStats.opens++
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val h = handle ?: throw java.io.IOException("sftp data source not opened")

        if (position < prefetchStart || position >= prefetchStart + prefetchLen) {
            val want = minOf(PREFETCH_BYTES.toLong(), remaining).toInt()
            SftpDataSourceStats.reads++
            val fetched = try {
                h.readAt(position.toULong(), want.toUInt())
            } catch (e: Throwable) {
                SftpDataSourceStats.lastError = "${e.javaClass.simpleName}: ${e.message}"
                throw java.io.IOException("sftp readAt failed: ${e.message}", e)
            }
            if (fetched.isEmpty()) return C.RESULT_END_OF_INPUT
            System.arraycopy(fetched, 0, prefetch, 0, fetched.size)
            prefetchStart = position
            prefetchLen = fetched.size
            SftpDataSourceStats.bytes += fetched.size
        }

        val bufIdx = (position - prefetchStart).toInt()
        val avail = prefetchLen - bufIdx
        val toCopy = minOf(length, avail).coerceAtMost(remaining.toInt())
        System.arraycopy(prefetch, bufIdx, buffer, offset, toCopy)
        position += toCopy
        remaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        handle?.let {
            runCatching { it.release() }
            runCatching { it.close() }
            handle = null
            prefetchLen = 0
            transferEnded()
            SftpDataSourceStats.closes++
        }
    }

    companion object {
        private const val PREFETCH_BYTES = 1024 * 1024
    }
}

internal object SftpDataSourceStats {
    @Volatile var opens: Int = 0
    @Volatile var reads: Int = 0
    @Volatile var closes: Int = 0
    @Volatile var bytes: Long = 0
    @Volatile var lastError: String? = null
    fun reset() {
        opens = 0; reads = 0; closes = 0; bytes = 0; lastError = null
    }
}
