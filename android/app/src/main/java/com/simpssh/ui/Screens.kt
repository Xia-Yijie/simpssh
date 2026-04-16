package com.simpssh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.simpssh.data.InitScript
import com.simpssh.data.Server

// ---------------------------------------------------------------------------
// Server list
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    servers: List<Server>,
    openSessionCount: Int,
    onAdd: () -> Unit,
    onEdit: (Server) -> Unit,
    onConnect: (server: Server, script: InitScript?) -> Unit,
    onShowSessions: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var showGuide by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("simpssh", fontWeight = FontWeight.Bold) },
                actions = {
                    if (openSessionCount > 0) {
                        TextButton(onClick = onShowSessions) {
                            NerdIcon(NerdGlyphs.TERMINAL, null, size = 16.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("会话 ($openSessionCount)")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // IntrinsicSize.Max sizes the column to its widest child
            // (添加服务器). All three FABs use start-aligned content + the
            // shorter ones fillMaxWidth, so icons line up at the same X
            // and the column doesn't stretch beyond what 添加服务器 needs.
            Column(
                modifier = Modifier.width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlignedFab(
                    glyph = NerdGlyphs.PLUS,
                    label = "添加服务器",
                    onClick = onAdd,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                AlignedFab(
                    glyph = NerdGlyphs.HELP,
                    label = "操作指南",
                    onClick = { showGuide = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                AlignedFab(
                    glyph = NerdGlyphs.COG,
                    label = "设置",
                    onClick = onShowSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { s ->
                    ServerCard(
                        server = s,
                        expanded = expanded[s.id] == true,
                        onToggle = { expanded[s.id] = !(expanded[s.id] ?: false) },
                        onLongPress = { onEdit(s) },
                        onConnect = { script -> onConnect(s, script) },
                    )
                }
            }
        }
    }

    if (showGuide) GuideDialog(onDismiss = { showGuide = false })
}

/// FAB with start-aligned (icon, label) content so multiple FABs stacked
/// vertically share the same icon X column. Width is whatever the parent
/// gives (e.g. fillMaxWidth inside an IntrinsicSize.Max column to match
/// the widest sibling); otherwise it sizes to its content.
@Composable
private fun AlignedFab(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = modifier.height(56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NerdIcon(glyph, null, size = 18.dp, tint = contentColor)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NerdIcon(
                NerdGlyphs.CLOUD,
                contentDescription = null,
                size = 72.dp,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(20.dp))
            Text("还没有服务器", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "右下角 + 添加你的第一个 SSH 主机",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: Server,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onConnect: (InitScript?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    NerdIcon(
                        NerdGlyphs.TERMINAL,
                        null,
                        size = 22.dp,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        server.name.ifBlank { server.host },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${server.user}@${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
                NerdIcon(
                    if (expanded) NerdGlyphs.CHEVRON_UP else NerdGlyphs.CHEVRON_DOWN,
                    null,
                    size = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    ScriptOption(label = "默认", onConnect = { onConnect(null) })
                    server.initScripts.forEach { script ->
                        ScriptOption(
                            label = script.name.ifBlank { "未命名脚本" },
                            onConnect = { onConnect(script) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptOption(label: String, onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
            .padding(start = 28.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FilledTonalIconButton(
            onClick = onConnect,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.size(36.dp),
        ) {
            NerdIcon(NerdGlyphs.PLAY, "连接", size = 16.dp)
        }
    }
}

// ---------------------------------------------------------------------------
// Operation guide dialog
// ---------------------------------------------------------------------------

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("操作指南") },
        text = {
            Column {
                GuideRow("单击主机", "展开/收起 启动方式")
                GuideRow("▶ 按钮", "用该启动方式连接（自动开新会话）")
                GuideRow("长按主机", "进入编辑页（含删除）")
                GuideRow("会话 (N)", "切回正在运行的多个会话标签")
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "作者：夏义杰",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    "github.com/Xia-Yijie/simpssh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun GuideRow(action: String, desc: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            action,
            modifier = Modifier.width(108.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

// ---------------------------------------------------------------------------
// Server edit
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    initial: Server?,
    onCancel: () -> Unit,
    onSave: (Server) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    val scripts = remember { (initial?.initScripts ?: emptyList()).toMutableStateList() }

    val canSave = host.isNotBlank() && user.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建服务器" else "编辑服务器") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { NerdIcon(NerdGlyphs.ARROW_LEFT, null, size = 20.dp) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("IP 或域名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            SshCommandPreview(host = host, port = port, user = user)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("初始化脚本", style = MaterialTheme.typography.titleSmall)
            Text(
                "每条 = 名称 + 工作目录 + 命令。连接时 shell 会先 cd 到工作目录，文件浏览器也以此为根。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            scripts.forEachIndexed { idx, sc ->
                ScriptEditor(
                    script = sc,
                    onChange = { scripts[idx] = it },
                    onDelete = { scripts.removeAt(idx) },
                )
            }

            OutlinedButton(
                onClick = { scripts += InitScript(name = "新脚本", content = "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                NerdIcon(NerdGlyphs.PLUS, null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("添加一条脚本")
            }

            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    onSave(
                        Server(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                            user = user.trim(),
                            password = password,
                            initScripts = scripts.toList(),
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("保存", style = MaterialTheme.typography.titleMedium)
            }

            if (onDelete != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    NerdIcon(NerdGlyphs.TRASH, null, size = 18.dp, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("删除此服务器")
                }
            }
        }
    }
}

@Composable
private fun ScriptEditor(
    script: InitScript,
    onChange: (InitScript) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = script.name,
                    onValueChange = { onChange(script.copy(name = it)) },
                    label = { Text("脚本名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    NerdIcon(NerdGlyphs.TRASH, "删除", size = 20.dp, tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = script.workingDir,
                onValueChange = { onChange(script.copy(workingDir = it)) },
                label = { Text("工作目录（可选）") },
                placeholder = { Text("~/work 或 /opt/app；空 = home") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = script.content,
                onValueChange = { onChange(script.copy(content = it)) },
                label = { Text("命令") },
                placeholder = { Text("# 一行一条\nsource venv/bin/activate\nnpm run dev") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
    }
}

@Composable
private fun SshCommandPreview(host: String, port: String, user: String) {
    val cmd = remember(host, port, user) {
        val u = user.trim().ifBlank { "用户" }
        val h = host.trim().ifBlank { "主机" }
        val p = port.toIntOrNull()
        val portFlag = if (p != null && p != 22) "-p $p " else ""
        "ssh $portFlag$u@$h"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "等效命令",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$ $cmd",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
