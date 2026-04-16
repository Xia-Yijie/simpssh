package com.simpssh.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.simpssh_core.DirEntry

private sealed class TreeRow {
    data class Entry(val entry: DirEntry, val depth: Int) : TreeRow()
    /// Inserted when an expanded folder has no children we know of.
    /// Lets the UI distinguish "(空)" from "尚未加载 / 失败" instead of
    /// just rendering nothing.
    data class Placeholder(val text: String, val depth: Int, val key: String) : TreeRow()
}

// Shared by TreeRowView and PlaceholderRow so leading icons line up under
// each other regardless of which row variant gets rendered.
private val CHEVRON_COL_SIZE = 20.dp
private val CHEVRON_ICON_GAP = 8.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesBody(tab: TabState, manager: SessionManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameTarget by remember { mutableStateOf<DirEntry?>(null) }
    var mkdirInDir by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<DirEntry?>(null) }
    var pendingUploadDir by remember { mutableStateOf<String?>(null) }
    var gotoOpen by remember { mutableStateOf(false) }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val target = pendingDownload
        pendingDownload = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = manager.readFileBytes(tab, target.path, max = DOWNLOAD_MAX_BYTES) ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            }
            withContext(Dispatchers.Main) { tab.filesStatus = "已下载 ${target.name} (${bytes.size} B)" }
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

    // Plain call; flattenTree reads SnapshotState (childrenByPath, expanded),
    // so Compose recomposes us when those change. Wrapping in remember{} with
    // .toList()/.toMap() keys would allocate fresh keys every recompose and
    // defeat the memoization, so we rely on the snapshot tracking instead.
    val rows = flattenTree(tab, tab.rootPath, depth = 0)

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tab.rootPath,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
            IconButton(onClick = { gotoOpen = true }) {
                Icon(Icons.Default.Home, "切换根路径")
            }
            IconButton(onClick = {
                scope.launch { manager.loadChildren(tab, tab.rootPath) }
            }) {
                Icon(Icons.Default.Refresh, "刷新")
            }
        }

        // Status
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                tab.filesStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // Tree
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
                            if (row.entry.isDir) {
                                scope.launch { manager.toggleExpand(tab, row.entry.path) }
                            } else {
                                scope.launch {
                                    val bytes = manager.readFileBytes(tab, row.entry.path) ?: return@launch
                                    preview = row.entry.path to decodeText(bytes)
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
                        onRename = { renameTarget = row.entry },
                        onDelete = {
                            scope.launch {
                                val err = manager.delete(tab, row.entry)
                                if (err == null) manager.loadChildren(tab, parentOf(row.entry.path))
                                else reportFilesError(tab, "删除", err)
                            }
                        },
                    )
                }
            }
        }
    }

    // Preview
    preview?.let { (path, body) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
            title = { Text(path.substringAfterLast('/'), maxLines = 1) },
            text = {
                Box(modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                    )
                }
            },
        )
    }

    // mkdir
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

    // rename
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

    // change root
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
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = (8 + depth * TREE_INDENT_DP).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron column (only for dirs; files get matching width to align)
        Box(modifier = Modifier.size(CHEVRON_COL_SIZE), contentAlignment = Alignment.Center) {
            if (entry.isDir) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandMore
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        // File / folder icon
        Icon(
            when {
                entry.isDir && isExpanded -> Icons.Default.FolderOpen
                entry.isDir -> Icons.Default.Folder
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            null,
            modifier = Modifier.size(18.dp),
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
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
                Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onMkdirHere?.let {
                    DropdownMenuItem(
                        text = { Text("在此处新建目录") },
                        onClick = { menu = false; it() },
                        leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                    )
                }
                onUploadHere?.let {
                    DropdownMenuItem(
                        text = { Text("上传文件到此处") },
                        onClick = { menu = false; it() },
                        leadingIcon = { Icon(Icons.Default.Upload, null) },
                    )
                }
                onDownload?.let {
                    DropdownMenuItem(
                        text = { Text("下载到本机") },
                        onClick = { menu = false; it() },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menu = false; onRename() },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = { menu = false; onDelete() },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                )
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
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
                label = { Text(label) },
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
    if (children == null) return emptyList()  // root not loaded yet — caller renders nothing here
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

private fun decodeText(bytes: ByteArray): String {
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
