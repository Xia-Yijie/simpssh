package com.simpssh.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.simpssh_core.DirEntry

private sealed class TreeRow {
    data class Entry(val entry: DirEntry, val depth: Int) : TreeRow()
    data class Placeholder(val text: String, val depth: Int, val key: String) : TreeRow()
}

private val CHEVRON_COL_SIZE = 20.dp
private val CHEVRON_ICON_GAP = 8.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesBody(tab: TabState, manager: SessionManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<PreviewKind?>(null) }
    var renameTarget by remember { mutableStateOf<DirEntry?>(null) }
    var mkdirInDir by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<DirEntry?>(null) }
    var pendingUploadDir by remember { mutableStateOf<String?>(null) }
    var gotoOpen by remember { mutableStateOf(false) }
    var entryInfo by remember { mutableStateOf<DirEntry?>(null) }
    var rootInfo by remember { mutableStateOf<DirEntry?>(null) }
    var rootInfoLoading by remember { mutableStateOf(false) }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val target = pendingDownload
        pendingDownload = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        streamWithProgress(
            manager = manager,
            tab = tab,
            entry = target,
            initialStatus = "正在下载 ${target.name}",
            cancelledStatus = "已取消下载",
            errorLabel = "下载",
            openOutput = {
                ctx.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("openOutputStream returned null")
            },
        ) { total ->
            withContext(Dispatchers.Main) { tab.filesStatus = "已下载 ${target.name} (${humanBytes(total)})" }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val destDir = pendingUploadDir ?: tab.rootPath
        pendingUploadDir = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            } ?: return@launch
            val name = queryDisplayName(ctx, uri) ?: "uploaded.bin"
            val dest = joinPath(destDir, name)
            val err = manager.writeFileBytes(tab, dest, bytes)
            withContext(Dispatchers.Main) {
                tab.filesStatus = if (err == null) "已上传 → $dest" else formatError("上传", err)
            }
            manager.loadChildren(tab, destDir)
        }
    }

    // flattenTree 读取 SnapshotState,Compose 依赖此读取触发重组;用 remember 包裹会破坏该订阅。
    val rows = flattenTree(tab, tab.rootPath, depth = 0)

    val clipboard = LocalClipboardManager.current
    var rootMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NerdIcon(
                NerdGlyphs.FOLDER, null,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tab.rootPath,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
            Box {
                IconButton(onClick = { rootMenuOpen = true }) {
                    NerdIcon(NerdGlyphs.ELLIPSIS_V, "更多", size = 20.dp)
                }
                DropdownMenu(expanded = rootMenuOpen, onDismissRequest = { rootMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("复制路径") },
                        onClick = {
                            rootMenuOpen = false
                            clipboard.setText(AnnotatedString(tab.rootPath))
                        },
                        leadingIcon = { NerdIcon(NerdGlyphs.CODE, null, size = 18.dp) },
                    )
                    DropdownMenuItem(
                        text = { Text("文件信息") },
                        onClick = {
                            rootMenuOpen = false
                            rootInfoLoading = true
                            scope.launch {
                                rootInfo = manager.statEntry(tab, tab.rootPath)
                                rootInfoLoading = false
                            }
                        },
                        leadingIcon = { NerdIcon(NerdGlyphs.INFO, null, size = 18.dp) },
                    )
                    DropdownMenuItem(
                        text = { Text("切换根路径") },
                        onClick = {
                            rootMenuOpen = false
                            gotoOpen = true
                        },
                        leadingIcon = { NerdIcon(NerdGlyphs.HOME, null, size = 18.dp) },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        onClick = {
                            rootMenuOpen = false
                            scope.launch { manager.refreshDir(tab, tab.rootPath) }
                        },
                        leadingIcon = { NerdIcon(NerdGlyphs.REFRESH, null, size = 18.dp) },
                    )
                    DropdownMenuItem(
                        text = { Text("在此处新建目录") },
                        onClick = {
                            rootMenuOpen = false
                            mkdirInDir = tab.rootPath
                        },
                        leadingIcon = { NerdIcon(NerdGlyphs.FOLDER_PLUS, null, size = 18.dp) },
                    )
                    DropdownMenuItem(
                        text = { Text("上传文件到此处") },
                        onClick = {
                            rootMenuOpen = false
                            pendingUploadDir = tab.rootPath
                            uploadLauncher.launch(arrayOf("*/*"))
                        },
                        leadingIcon = { NerdIcon(NerdGlyphs.UPLOAD, null, size = 18.dp) },
                    )
                }
            }
        }

        FilesStatusBanner(
            text = tab.filesStatus,
            busy = tab.filesBusy,
            progressDone = tab.filesProgressDone,
            progressTotal = tab.filesProgressTotal,
            onCancel = { manager.cancelFileOp(tab) },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(rows, key = { rowKey(it) }) { row ->
                when (row) {
                    is TreeRow.Placeholder -> PlaceholderRow(row.text, row.depth)
                    is TreeRow.Entry -> TreeRowView(
                        entry = row.entry,
                        depth = row.depth,
                        isExpanded = tab.expanded.contains(row.entry.path),
                        onClick = {
                            when {
                                row.entry.isDir -> scope.launch { manager.toggleExpand(tab, row.entry.path) }
                                row.entry.name.endsWith(".apk", ignoreCase = true) ->
                                    stageAndInstallApk(ctx, manager, tab, row.entry)
                                else -> scope.launch {
                                    preview = openFilePreview(manager, tab, row.entry) ?: return@launch
                                }
                            }
                        },
                        onDownload = if (!row.entry.isDir) {
                            {
                                pendingDownload = row.entry
                                downloadLauncher.launch(row.entry.name)
                            }
                        } else null,
                        onMkdirHere = if (row.entry.isDir) {
                            { mkdirInDir = row.entry.path }
                        } else null,
                        onUploadHere = if (row.entry.isDir) {
                            {
                                pendingUploadDir = row.entry.path
                                uploadLauncher.launch(arrayOf("*/*"))
                            }
                        } else null,
                        onRefresh = if (row.entry.isDir) {
                            { scope.launch { manager.refreshDir(tab, row.entry.path) } }
                        } else null,
                        onRename = { renameTarget = row.entry },
                        onDelete = {
                            scope.launch {
                                val err = manager.delete(tab, row.entry)
                                if (err == null) manager.loadChildren(tab, parentOf(row.entry.path))
                                else reportFilesError(tab, "删除", err)
                            }
                        },
                        onInfo = { entryInfo = row.entry },
                    )
                }
            }
        }
    }

    PreviewDialogs(preview = preview, sftp = tab.sftp) { preview = null }

    entryInfo?.let { FileInfoDialog(it) { entryInfo = null } }
    rootInfo?.let { FileInfoDialog(it) { rootInfo = null } }
    if (rootInfoLoading && rootInfo == null) {
        AlertDialog(
            onDismissRequest = { rootInfoLoading = false },
            confirmButton = { TextButton(onClick = { rootInfoLoading = false }) { Text("取消") } },
            title = { Text("读取中…") },
            text = { Text(tab.rootPath, fontFamily = FontFamily.Monospace) },
        )
    }

    mkdirInDir?.let { parent ->
        TextInputDialog(
            title = "在 ${parent.substringAfterLast('/').ifBlank { "/" }} 中新建目录",
            label = "名称",
            confirm = "创建",
            onCancel = { mkdirInDir = null },
            onConfirm = { name ->
                mkdirInDir = null
                if (name.isBlank()) return@TextInputDialog
                scope.launch {
                    val err = manager.mkdir(tab, joinPath(parent, name))
                    if (err == null) manager.loadChildren(tab, parent)
                    else reportFilesError(tab, "创建", err)
                }
            },
        )
    }

    renameTarget?.let { entry ->
        TextInputDialog(
            title = "重命名",
            label = "新名称",
            initial = entry.name,
            confirm = "确定",
            onCancel = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                if (newName.isBlank() || newName == entry.name) return@TextInputDialog
                val parent = parentOf(entry.path)
                val to = joinPath(parent, newName)
                scope.launch {
                    val err = manager.rename(tab, entry.path, to)
                    if (err == null) manager.loadChildren(tab, parent)
                    else reportFilesError(tab, "重命名", err)
                }
            },
        )
    }

    if (gotoOpen) {
        TextInputDialog(
            title = "切换根目录",
            label = "绝对路径",
            initial = tab.rootPath,
            confirm = "前往",
            onCancel = { gotoOpen = false },
            onConfirm = { p ->
                gotoOpen = false
                if (p.isBlank()) return@TextInputDialog
                scope.launch { manager.setRoot(tab, p) }
            },
        )
    }
}

private fun rowKey(row: TreeRow): String = when (row) {
    is TreeRow.Entry -> "${row.depth}@${row.entry.path}"
    is TreeRow.Placeholder -> row.key
}

@Composable
private fun FilesStatusBanner(
    text: String,
    busy: Boolean,
    progressDone: Long,
    progressTotal: Long,
    onCancel: () -> Unit,
) {
    if (busy) {
        val knownSize = progressTotal > 0L
        val fraction = if (knownSize) {
            (progressDone.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val subtitle = if (knownSize) {
            "${humanBytes(progressDone)} / ${humanBytes(progressTotal)}  ${(fraction * 100).toInt()}%"
        } else null

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    NerdIcon(
                        NerdGlyphs.TIMES,
                        "取消",
                        size = 14.dp,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (knownSize) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

internal fun humanBytes(n: Long): String {
    if (n < 1024L) return "$n B"
    val kb = n / 1024L
    if (kb < 1024L) return "$kb KB"
    val mb = kb / 1024L
    if (mb < 1024L) return "$mb MB"
    return "${mb / 1024L} GB"
}

@Composable
private fun PlaceholderRow(text: String, depth: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (8 + depth * TREE_INDENT_DP).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(CHEVRON_COL_SIZE))
        Spacer(Modifier.width(CHEVRON_ICON_GAP))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeRowView(
    entry: DirEntry,
    depth: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
    onMkdirHere: (() -> Unit)?,
    onUploadHere: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = (8 + depth * TREE_INDENT_DP).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(CHEVRON_COL_SIZE), contentAlignment = Alignment.Center) {
            if (entry.isDir) {
                NerdIcon(
                    if (isExpanded) NerdGlyphs.CHEVRON_DOWN else NerdGlyphs.CHEVRON_RIGHT,
                    null,
                    size = 14.dp,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        NerdIcon(
            glyph = when {
                entry.isDir && isExpanded -> NerdGlyphs.FOLDER_OPEN
                entry.isDir -> NerdGlyphs.FOLDER
                else -> glyphForFile(entry.name)
            },
            contentDescription = null,
            size = 18.dp,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(CHEVRON_ICON_GAP))
        Text(
            entry.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                NerdIcon(NerdGlyphs.ELLIPSIS_V, "更多", size = 16.dp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("复制路径") },
                    onClick = {
                        menu = false
                        clipboard.setText(AnnotatedString(entry.path))
                    },
                    leadingIcon = { NerdIcon(NerdGlyphs.CODE, null, size = 18.dp) },
                )
                DropdownMenuItem(
                    text = { Text("文件信息") },
                    onClick = { menu = false; onInfo() },
                    leadingIcon = { NerdIcon(NerdGlyphs.INFO, null, size = 18.dp) },
                )
                onMkdirHere?.let {
                    DropdownMenuItem(
                        text = { Text("在此处新建目录") },
                        onClick = { menu = false; it() },
                        leadingIcon = { NerdIcon(NerdGlyphs.FOLDER_PLUS, null, size = 18.dp) },
                    )
                }
                onUploadHere?.let {
                    DropdownMenuItem(
                        text = { Text("上传文件到此处") },
                        onClick = { menu = false; it() },
                        leadingIcon = { NerdIcon(NerdGlyphs.UPLOAD, null, size = 18.dp) },
                    )
                }
                onRefresh?.let {
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        onClick = { menu = false; it() },
                        leadingIcon = { NerdIcon(NerdGlyphs.REFRESH, null, size = 18.dp) },
                    )
                }
                onDownload?.let {
                    DropdownMenuItem(
                        text = { Text("下载到本机") },
                        onClick = { menu = false; it() },
                        leadingIcon = { NerdIcon(NerdGlyphs.DOWNLOAD, null, size = 18.dp) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menu = false; onRename() },
                    leadingIcon = { NerdIcon(NerdGlyphs.PENCIL, null, size = 18.dp) },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = { menu = false; onDelete() },
                    leadingIcon = {
                        NerdIcon(NerdGlyphs.TRASH, null, size = 18.dp, tint = MaterialTheme.colorScheme.error)
                    },
                )
            }
        }
    }
}

@Composable
private fun FileInfoDialog(entry: DirEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(entry.name, maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("类型", describeKind(entry))
                InfoRow("路径", entry.path, monospace = true)
                InfoRow("大小", "${humanBytes(entry.size.toLong())}  (${entry.size} 字节)")
                InfoRow("修改时间", formatMtime(entry.mtime.toLong()))
                InfoRow("权限", formatPermissions(entry.mode.toInt(), entry.isDir, entry.isLink))
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
        )
    }
}

private fun describeKind(entry: DirEntry): String = when {
    entry.isLink -> "符号链接"
    entry.isDir -> "目录"
    else -> "文件"
}

// SimpleDateFormat 非线程安全,但 formatMtime 只在 Main 用。
private val MTIME_FORMATTER by lazy {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
}

private fun formatMtime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "未知"
    return MTIME_FORMATTER.format(java.util.Date(epochSeconds * 1000L))
}

private fun formatPermissions(mode: Int, isDir: Boolean, isLink: Boolean): String {
    val type = when {
        isLink -> 'l'
        isDir -> 'd'
        else -> '-'
    }
    val bits = mode and 0xFFF
    fun rwx(shift: Int): String {
        val r = if (bits and (4 shl shift) != 0) 'r' else '-'
        val w = if (bits and (2 shl shift) != 0) 'w' else '-'
        val x = if (bits and (1 shl shift) != 0) 'x' else '-'
        return "$r$w$x"
    }
    val symbolic = "$type${rwx(6)}${rwx(3)}${rwx(0)}"
    val octal = String.format("%04o", bits)
    return "$symbolic   $octal"
}

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    placeholder: String? = null,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                label = if (label.isNotEmpty()) {
                    { Text(label) }
                } else null,
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(v.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

private fun flattenTree(tab: TabState, path: String, depth: Int): List<TreeRow> {
    val children = tab.childrenByPath[path]
    if (children == null) return emptyList()
    val out = mutableListOf<TreeRow>()
    for (c in children) {
        out.add(TreeRow.Entry(c, depth))
        if (c.isDir && tab.expanded.contains(c.path)) {
            val nested = tab.childrenByPath[c.path]
            when {
                nested == null -> out.add(TreeRow.Placeholder("(尚未加载)", depth + 1, "ph-load:${c.path}"))
                nested.isEmpty() -> out.add(TreeRow.Placeholder("(空目录)", depth + 1, "ph-empty:${c.path}"))
                else -> out.addAll(flattenTree(tab, c.path, depth + 1))
            }
        }
    }
    return out
}

internal fun decodeText(bytes: ByteArray): String {
    return try {
        val s = bytes.toString(Charsets.UTF_8)
        val bad = s.count { it == '\uFFFD' }
        if (bad > s.length / 50) "（二进制文件，长度 ${bytes.size} 字节）" else s
    } catch (_: Exception) {
        "（二进制文件，长度 ${bytes.size} 字节）"
    }
}

private fun queryDisplayName(ctx: android.content.Context, uri: Uri): String? {
    return runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    }.getOrNull()
}

private fun stageAndInstallApk(
    ctx: Context,
    manager: SessionManager,
    tab: TabState,
    entry: DirEntry,
) {
    val stagingDir = java.io.File(ctx.cacheDir, APK_STAGING_DIR).apply { mkdirs() }
    val apkFile = java.io.File(stagingDir, entry.name)
    stagingDir.listFiles()?.forEach { if (it.name != entry.name) it.delete() }
    streamWithProgress(
        manager = manager,
        tab = tab,
        entry = entry,
        initialStatus = "正在下载 ${entry.name}",
        cancelledStatus = "已取消安装",
        errorLabel = "下载",
        openOutput = { java.io.FileOutputStream(apkFile) },
        onFailure = { apkFile.delete() },
    ) { total ->
        withContext(Dispatchers.Main) {
            tab.filesStatus = "已下载 ${entry.name} (${humanBytes(total)})，启动安装…"
        }
        val uri = FileProvider.getUriForFile(
            ctx, ctx.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX, apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }.onFailure { e ->
            withContext(Dispatchers.Main) { tab.filesStatus = formatError("启动安装器", e) }
        }
    }
}

private fun streamWithProgress(
    manager: SessionManager,
    tab: TabState,
    entry: DirEntry,
    initialStatus: String,
    cancelledStatus: String,
    errorLabel: String,
    openOutput: () -> java.io.OutputStream,
    onFailure: () -> Unit = {},
    onComplete: suspend (Long) -> Unit,
) {
    val notifier = manager.newDownloadNotifier()
    val notifId = DownloadNotifier.nextId()
    val total0 = entry.size.toLong()
    manager.launchFileOp(tab) {
        withContext(Dispatchers.Main) {
            tab.filesStatus = initialStatus
            tab.filesProgressTotal = total0
            tab.filesProgressDone = 0L
        }
        notifier.progress(notifId, entry.name, 0L, total0)
        // 500 ms 同时限流 UI 与系统通知;大文件按块下发会压爆 Main + NotificationManager。
        var lastTick = System.currentTimeMillis()
        val total = try {
            openOutput().use { out ->
                manager.streamFileTo(tab, entry, out) { done ->
                    val now = System.currentTimeMillis()
                    val final = done == total0
                    if (final || now - lastTick >= 500L) {
                        lastTick = now
                        withContext(Dispatchers.Main) { tab.filesProgressDone = done }
                        notifier.progress(notifId, entry.name, done, total0)
                    }
                }
            }
        } catch (ce: CancellationException) {
            onFailure()
            withContext(NonCancellable + Dispatchers.Main) { tab.filesStatus = cancelledStatus }
            notifier.cancel(notifId)
            throw ce
        } catch (e: Throwable) {
            onFailure()
            reportFilesError(tab, errorLabel, e)
            notifier.error(notifId, entry.name, e.message ?: e.javaClass.simpleName)
            return@launchFileOp
        }
        notifier.done(notifId, entry.name, total)
        onComplete(total)
    }
}

// 必须与 `res/xml/file_paths.xml` 中声明的子目录名一致,否则 FileProvider 无法暴露该路径。
private const val APK_STAGING_DIR = "apks"

// 必须与 `AndroidManifest.xml` 中 FileProvider 的 `android:authorities` 后缀一致,否则 getUriForFile 会抛 IllegalArgumentException。
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
