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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.simpssh.data.InitScript
import com.simpssh.data.Server

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

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("操作指南") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                GuideSection("主机列表") {
                    IconGuideRow(NerdGlyphs.HAND_POINTER, "单击主机", "展开/收起启动方式")
                    IconGuideRow(NerdGlyphs.PLAY, "▶ 按钮", "用该启动方式连接（自动开新会话）")
                    IconGuideRow(NerdGlyphs.EDIT, "长按主机", "进入编辑页（含删除）")
                    IconGuideRow(NerdGlyphs.TERMINAL, "会话 (N)", "切回正在运行的多个会话")
                }

                GuideSection("终端触屏手势") {
                    GestureGuideRow(Gesture.TAP, "单指单击", "鼠标模式下发送左键点击")
                    GestureGuideRow(Gesture.TWO_TAP, "双指同时点", "发送右键点击")
                    GestureGuideRow(Gesture.LONG_PRESS, "长按", "选中单词并进入选择模式;之后拖动手柄调整范围，可复制/粘贴")
                    GestureGuideRow(Gesture.SWIPE_H, "左右滑动", "发送 Tab / Shift+Tab")
                    GestureGuideRow(Gesture.SWIPE_V, "上下拖动", "滚动历史（或 TUI 的上下键 / 鼠标滚轮）")
                    GestureGuideRow(Gesture.PINCH, "双指捏合", "缩放终端字号")
                }

                GuideSection("终端工具栏") {
                    IconGuideRow(NerdGlyphs.MOUSE_POINTER, "鼠标模式", "开启后单击发送鼠标事件")
                    IconGuideRow(NerdGlyphs.HAND_POINTER, "修饰键", "Shift/Ctrl/Alt 吸附到下一次输入")
                    IconGuideRow(NerdGlyphs.KEYBOARD, "键盘按钮", "弹出软键盘；键盘已开时变为更多按键")
                    IconGuideRow(NerdGlyphs.ARROWS_V, "更多按键", "长按 ↑↓ 等方向键可连续发送")
                }

                GuideSection("文件") {
                    IconGuideRow(NerdGlyphs.FOLDER, "点击目录", "展开/收起子目录")
                    IconGuideRow(NerdGlyphs.FILE_TEXT, "点击文本/代码", "代码文件自动识别语言并语法高亮")
                    IconGuideRow(NerdGlyphs.FILE_IMAGE, "点击图片", "内置查看器，双指缩放 / 拖动")
                    IconGuideRow(NerdGlyphs.FILE_VIDEO, "点击音视频", "流式拉取，无需等待完全下载")
                    IconGuideRow(NerdGlyphs.DOWNLOAD, "点击 .apk", "下载后直接调起安装器")
                    IconGuideRow(NerdGlyphs.ELLIPSIS_V, "⋮ 菜单", "下载 / 上传 / 新建目录 / 重命名 / 删除")
                    IconGuideRow(NerdGlyphs.REFRESH, "下载进行时", "顶部有进度条，可点击 × 取消")
                }

                GuideSection("设置") {
                    IconGuideRow(NerdGlyphs.KEYBOARD, "键盘", "定制工具栏按键顺序 / 可见性")
                    IconGuideRow(NerdGlyphs.CODE, "开发者", "显示 BP / ALT / MS 模式徽章等")
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
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
private fun GuideSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    content()
}

@Composable
private fun IconGuideRow(glyph: String, action: String, desc: String) = GuideRow(
    leading = {
        NerdIcon(glyph, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    action = action,
    desc = desc,
)

@Composable
private fun GestureGuideRow(gesture: Gesture, action: String, desc: String) = GuideRow(
    leading = { GestureGlyph(gesture, modifier = Modifier.size(32.dp)) },
    action = action,
    desc = desc,
)

@Composable
private fun GuideRow(leading: @Composable () -> Unit, action: String, desc: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(8.dp))
        Text(
            action,
            modifier = Modifier.width(88.dp),
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

private enum class Gesture { TAP, TWO_TAP, LONG_PRESS, SWIPE_H, SWIPE_V, PINCH }

@Composable
private fun GestureGlyph(gesture: Gesture, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) * 0.14f
        when (gesture) {
            Gesture.TAP -> {
                drawCircle(color = color.copy(alpha = 0.25f), radius = r * 2.2f, center = Offset(cx, cy))
                drawCircle(color = color, radius = r, center = Offset(cx, cy))
            }
            Gesture.TWO_TAP -> {
                drawCircle(color = color, radius = r, center = Offset(w * 0.34f, cy))
                drawCircle(color = color, radius = r, center = Offset(w * 0.66f, cy))
            }
            Gesture.LONG_PRESS -> {
                val stroke = Stroke(width = 2.5f)
                drawCircle(color = color.copy(alpha = 0.18f), radius = r * 3.2f, center = Offset(cx, cy), style = stroke)
                drawCircle(color = color.copy(alpha = 0.35f), radius = r * 2.2f, center = Offset(cx, cy), style = stroke)
                drawCircle(color = color, radius = r, center = Offset(cx, cy))
            }
            Gesture.SWIPE_H -> drawAxisArrow(color, Offset(w * 0.15f, cy), Offset(w * 0.85f, cy))
            Gesture.SWIPE_V -> drawAxisArrow(color, Offset(cx, h * 0.15f), Offset(cx, h * 0.85f))
            Gesture.PINCH -> {
                drawCircle(color, radius = r * 0.9f, center = Offset(w * 0.25f, cy))
                drawCircle(color, radius = r * 0.9f, center = Offset(w * 0.75f, cy))
                drawAxisArrow(color, Offset(w * 0.65f, cy), Offset(w * 0.35f, cy), stroke = 2f, barb = 5f)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisArrow(
    color: Color,
    from: Offset,
    to: Offset,
    stroke: Float = 2.5f,
    barb: Float = 7f,
) {
    drawLine(color, from, to, strokeWidth = stroke)
    val horizontal = kotlin.math.abs(to.x - from.x) > kotlin.math.abs(to.y - from.y)
    val headOffset = barb * 1.8f
    val (h1, h2) = if (horizontal) {
        val sign = if (to.x > from.x) 1f else -1f
        Offset(from.x + headOffset * sign, from.y) to Offset(to.x - headOffset * sign, to.y)
    } else {
        val sign = if (to.y > from.y) 1f else -1f
        Offset(from.x, from.y + headOffset * sign) to Offset(to.x, to.y - headOffset * sign)
    }
    val (perpFrom, perpTo) = if (horizontal) (0f to barb) else (barb to 0f)
    drawLine(color, from, Offset(h1.x + perpFrom, h1.y + perpTo), strokeWidth = stroke)
    drawLine(color, from, Offset(h1.x - perpFrom, h1.y - perpTo), strokeWidth = stroke)
    drawLine(color, to, Offset(h2.x + perpFrom, h2.y + perpTo), strokeWidth = stroke)
    drawLine(color, to, Offset(h2.x - perpFrom, h2.y - perpTo), strokeWidth = stroke)
}

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
                .imePadding()
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
