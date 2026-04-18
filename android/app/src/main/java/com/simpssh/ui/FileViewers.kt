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
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXT -> {
            // 图片必须读完整文件才能解码,PREVIEW_MAX_BYTES(256 KB)会把照片截断;
            // 这里用文件实际大小,再用 IMAGE_MAX_BYTES 封顶,防止超大图把解码器 OOM。
            val cap = minOf(entry.size.toLong(), IMAGE_MAX_BYTES.toLong()).toInt()
            val bytes = manager.readFileBytes(tab, entry.path, max = cap) ?: return null
            PreviewKind.Image(entry.path, bytes)
        }
        ext in MEDIA_EXT -> PreviewKind.Media(entry.path, entry.size.toLong())
        else -> {
            val bytes = manager.readFileBytes(tab, entry.path) ?: return null
            val name = entry.name.lowercase()
            // 先按整文件名(如 Makefile、Dockerfile)再按后缀:这类无扩展名的文件
            // 通常不具备可靠后缀,必须优先走文件名表才能命中。
            val lang = CODE_LANGUAGE_BY_FILENAME[name]
                ?: CODE_LANGUAGE_BY_EXT[ext]
                ?: LANG_PLAIN
            PreviewKind.Code(entry.path, decodeText(bytes), lang)
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
    // 以 expanded 作为 remember 的 key:每次菜单重新打开时重建状态,搜索框自动清空。
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

// 以"逻辑行"为粒度交给 LazyColumn 渲染。Alignment.Top 让行号钉在每个逻辑行的
// 第一条可视行上:开启自动换行后,同一逻辑行折成多行时,行号只出现在顶部,右
// 侧折下去的子行不再编号——与 VSCode / IntelliJ 的软换行行为一致。
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
    // 预先切成逐行 AnnotatedString,LazyColumn 绘制时只做一次下标取值;否则每个
    // 可见行都要对整串调用 subSequence,代价是 O(spans)。
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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PreviewTitleBar(preview.path, onDismiss, light = true)
                val ctx = LocalContext.current
                val player = remember(preview.path, preview.size) {
                    val factory = DataSource.Factory { SftpDataSource(sftp, preview.path, preview.size) }
                    ExoPlayer.Builder(ctx)
                        .setMediaSourceFactory(
                            DefaultMediaSourceFactory(ctx)
                                .setDataSourceFactory(factory),
                        )
                        .build()
                        .apply {
                            val uri = Uri.parse("sftp://internal${preview.path}")
                            setMediaSource(
                                ProgressiveMediaSource.Factory(factory)
                                    .createMediaSource(MediaItem.fromUri(uri)),
                            )
                            prepare()
                            playWhenReady = true
                        }
                }
                DisposableEffect(player) {
                    onDispose { player.release() }
                }
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
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

// ExoPlayer 在其 loader 线程上调用 read() 拉字节,所以这里必须是同步接口;
// uniffi 的 SftpSession.readFile(path, offset, len) 本身就是阻塞调用,正好匹配
// DataSource.read 的契约——不需要再包一层协程。
@UnstableApi
private class SftpDataSource(
    private val sftp: SftpSession,
    private val remotePath: String,
    private val totalSize: Long,
) : BaseDataSource(/* isNetwork = */ true) {
    private var position: Long = 0
    private var remaining: Long = 0
    private var openedUri: Uri? = null
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) totalSize - position
        else dataSpec.length
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val chunk = minOf(length.toLong(), remaining).coerceAtMost(CHUNK_BYTES).toInt()
        val fetched = try {
            sftp.readFile(remotePath, position.toULong(), chunk.toUInt())
        } catch (e: Throwable) {
            throw java.io.IOException("sftp readFile failed: ${e.message}", e)
        }
        if (fetched.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(fetched, 0, buffer, offset, fetched.size)
        position += fetched.size
        remaining -= fetched.size
        bytesTransferred(fetched.size)
        return fetched.size
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        private const val CHUNK_BYTES = 256L * 1024L
    }
}
