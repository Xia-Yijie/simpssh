package com.simpssh.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.simpssh_core.DirEntry

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesBody(tab: TabState.Files, manager: SessionManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<Pair<String, String>?>(null) } // path, body
    var renameTarget by remember { mutableStateOf<DirEntry?>(null) }
    var mkdirOpen by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<DirEntry?>(null) }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val target = pendingDownload
        pendingDownload = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = manager.readFileBytes(tab, target.path, max = 16 * 1024 * 1024)
                ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            }
            withContext(Dispatchers.Main) {
                tab.status = "已下载 ${target.name} (${bytes.size} B)"
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            } ?: return@launch
            val name = queryDisplayName(ctx, uri) ?: "uploaded.bin"
            val dest = joinPath(tab.cwd, name)
            val err = manager.writeFileBytes(tab, dest, bytes)
            withContext(Dispatchers.Main) {
                tab.status = if (err == null) "已上传 $name (${bytes.size} B)" else "上传失败: ${err.message}"
            }
            manager.refreshFiles(tab)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Path bar + actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Breadcrumbs(tab.cwd) { newPath ->
                    scope.launch { manager.navigateTo(tab, newPath) }
                }
            }
            IconButton(onClick = { scope.launch { manager.refreshFiles(tab) } }) {
                Icon(Icons.Default.Refresh, "刷新")
            }
            IconButton(onClick = { mkdirOpen = true }) {
                Icon(Icons.Default.CreateNewFolder, "新建目录")
            }
            IconButton(onClick = {
                uploadLauncher.launch(arrayOf("*/*"))
            }) {
                Icon(Icons.Default.Upload, "上传")
            }
        }
        // Status
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                tab.status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
        // Listing
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            // Up dir
            if (tab.cwd != "/") {
                item {
                    EntryRow(
                        leading = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                        name = "..",
                        meta = "上级目录",
                        onClick = { scope.launch { manager.navigateTo(tab, parentOf(tab.cwd)) } },
                        trailing = {},
                    )
                }
            }
            items(tab.entries, key = { it.path }) { entry ->
                EntryItem(
                    entry = entry,
                    onOpen = {
                        if (entry.isDir) {
                            scope.launch { manager.navigateTo(tab, entry.path) }
                        } else {
                            scope.launch {
                                val bytes = manager.readFileBytes(tab, entry.path) ?: return@launch
                                val text = decodeText(bytes)
                                preview = entry.path to text
                            }
                        }
                    },
                    onDownload = {
                        pendingDownload = entry
                        downloadLauncher.launch(entry.name)
                    },
                    onRename = { renameTarget = entry },
                    onDelete = {
                        scope.launch {
                            val err = manager.delete(tab, entry)
                            if (err == null) manager.refreshFiles(tab)
                            else withContext(Dispatchers.Main) { tab.status = "删除失败: ${err.message}" }
                        }
                    },
                )
            }
        }
    }

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

    if (mkdirOpen) {
        TextInputDialog(
            title = "新建目录",
            label = "名称",
            confirm = "创建",
            onCancel = { mkdirOpen = false },
            onConfirm = { name ->
                mkdirOpen = false
                if (name.isBlank()) return@TextInputDialog
                scope.launch {
                    val err = manager.mkdir(tab, joinPath(tab.cwd, name))
                    if (err == null) manager.refreshFiles(tab)
                    else withContext(Dispatchers.Main) { tab.status = "创建失败: ${err.message}" }
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
                    if (err == null) manager.refreshFiles(tab)
                    else withContext(Dispatchers.Main) { tab.status = "重命名失败: ${err.message}" }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Breadcrumbs(cwd: String, onJump: (String) -> Unit) {
    val parts = remember(cwd) {
        if (cwd == "/") listOf("/")
        else listOf("/") + cwd.trim('/').split('/')
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            val display = if (p == "/") "/" else p
            val target = if (i == 0) "/" else "/" + parts.subList(1, i + 1).joinToString("/")
            AssistChip(
                onClick = { if (target != cwd) onJump(target) },
                label = { Text(display, maxLines = 1) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (target == cwd)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryItem(
    entry: DirEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val sizeStr = if (entry.isDir) "目录" else humanSize(entry.size.toLong())

    EntryRow(
        leading = {
            Icon(
                if (entry.isDir) Icons.Default.Folder
                else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        },
        name = entry.name,
        meta = sizeStr,
        onClick = onOpen,
        onLongPress = { menu = true },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!entry.isDir) {
                        DropdownMenuItem(
                            text = { Text("下载到本机") },
                            onClick = { menu = false; onDownload() },
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
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    leading: @Composable () -> Unit,
    name: String,
    meta: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit = onClick,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        trailing()
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

private fun parentOf(p: String): String {
    if (p == "/") return "/"
    val trimmed = p.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else trimmed.substring(0, cut)
}

private fun joinPath(base: String, name: String): String =
    if (base.endsWith('/')) "$base$name" else "$base/$name"

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun decodeText(bytes: ByteArray): String {
    return try {
        val s = bytes.toString(Charsets.UTF_8)
        // Heuristic: if too many replacement chars, treat as binary.
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
