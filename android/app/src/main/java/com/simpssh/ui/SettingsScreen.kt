package com.simpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: String,
    customPalettes: List<ThemePalette>,
    toolbarKeyIds: List<String>,
    showModeBadges: Boolean,
    onThemeChange: (String) -> Unit,
    onToolbarKeysChange: (List<String>) -> Unit,
    onShowModeBadgesChange: (Boolean) -> Unit,
    onAddCustom: () -> Unit,
    onEditCustom: (ThemePalette) -> Unit,
    onDeleteCustom: (ThemePalette) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(SettingsTab.Palette) }
    // 按 tab 提升滚动状态:`when (tab)` 会销毁未选中的 LazyColumn,其内部 state 也会被一起丢掉。
    val paletteScroll = rememberLazyListState()
    val toolbarScroll = rememberLazyListState()
    val devScroll = rememberLazyListState()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { NerdIcon(NerdGlyphs.ARROW_LEFT, null, size = 20.dp) }
                    },
                )
                TabRow(selectedTabIndex = tab.ordinal) {
                    SettingsTabs.forEach { t ->
                        Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            SettingsTab.Palette -> PaletteTab(
                padding = padding,
                listState = paletteScroll,
                currentTheme = currentTheme,
                customPalettes = customPalettes,
                onThemeChange = onThemeChange,
                onAddCustom = onAddCustom,
                onEditCustom = onEditCustom,
                onDeleteCustom = onDeleteCustom,
            )
            SettingsTab.Toolbar -> ToolbarTab(
                padding = padding,
                listState = toolbarScroll,
                toolbarKeyIds = toolbarKeyIds,
                onToolbarKeysChange = onToolbarKeysChange,
            )
            SettingsTab.Developer -> DeveloperTab(
                padding = padding,
                listState = devScroll,
                showModeBadges = showModeBadges,
                onShowModeBadgesChange = onShowModeBadgesChange,
            )
        }
    }
}

private enum class SettingsTab(val label: String) {
    Palette("配色方案"),
    Toolbar("终端工具栏"),
    Developer("开发者选项"),
}

private val SettingsTabs: List<SettingsTab> = SettingsTab.values().toList()

@Composable
private fun PaletteTab(
    padding: PaddingValues,
    listState: LazyListState,
    currentTheme: String,
    customPalettes: List<ThemePalette>,
    onThemeChange: (String) -> Unit,
    onAddCustom: () -> Unit,
    onEditCustom: (ThemePalette) -> Unit,
    onDeleteCustom: (ThemePalette) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("内置") }
        items(BuiltInPalettes, key = { it.name }) { p ->
            ThemeRow(
                palette = p,
                selected = p.name == currentTheme,
                onClick = { onThemeChange(p.name) },
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionHeader("自定义") }
        if (customPalettes.isEmpty()) {
            item {
                Text(
                    "（暂无）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        } else {
            items(customPalettes, key = { it.name }) { p ->
                ThemeRow(
                    palette = p,
                    selected = p.name == currentTheme,
                    onClick = { onThemeChange(p.name) },
                    onEdit = { onEditCustom(p) },
                    onDelete = { onDeleteCustom(p) },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onAddCustom,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                NerdIcon(NerdGlyphs.PLUS, null, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("添加自定义配色")
            }
        }
    }
}

@Composable
private fun ToolbarTab(
    padding: PaddingValues,
    listState: LazyListState,
    toolbarKeyIds: List<String>,
    onToolbarKeysChange: (List<String>) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "勾选要显示在终端底部快捷栏的按键。同组按键之间会自动渲染分隔符。" +
                    "未勾选的按键仍可在键盘弹出时通过省略号按钮访问。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        items(items = KeyGroups, key = { it.name }) { group ->
            ToolbarGroupCard(
                group = group,
                selectedIds = toolbarKeyIds,
                onToggle = { keyId, checked ->
                    val next = if (checked) {
                        // 保持 AllKeys 的标准顺序,否则每次勾选工具栏按键会被打乱。
                        val taken = toolbarKeyIds.toSet() + keyId
                        AllKeys.filter { it.id in taken }.map { it.id }
                    } else {
                        toolbarKeyIds.filterNot { it == keyId }
                    }
                    onToolbarKeysChange(next)
                },
            )
        }
    }
}

@Composable
private fun DeveloperTab(
    padding: PaddingValues,
    listState: LazyListState,
    showModeBadges: Boolean,
    onShowModeBadgesChange: (Boolean) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "开发 / 调试用开关。平时不需要开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onShowModeBadgesChange(!showModeBadges) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示模式徽章", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "在工具栏下方显示 BP / ALT / MS 徽章，指示远端当前启用的 xterm 私有模式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(checked = showModeBadges, onCheckedChange = onShowModeBadgesChange)
                }
            }
        }
    }
}

@Composable
private fun ToolbarGroupCard(
    group: KeyGroup,
    selectedIds: List<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    val groupKeys = remember(group) { AllKeys.filter { it.group == group } }
    if (groupKeys.isEmpty()) return
    val taken = selectedIds.toSet()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                groupLabel(group),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            groupKeys.forEach { key ->
                val checked = key.id in taken
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(key.id, !checked) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle(key.id, it) },
                    )
                    Text(
                        key.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column(modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            thickness = 1.dp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    palette.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        NerdIcon(NerdGlyphs.PENCIL, "编辑", size = 18.dp)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        NerdIcon(
                            NerdGlyphs.TRASH, "删除",
                            size = 18.dp,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                RadioButton(selected = selected, onClick = onClick)
            }
            Spacer(Modifier.height(8.dp))
            TerminalPreview(palette)
        }
    }
}

private val MacControlClose    = Color(0xFFFF5F56)
private val MacControlMinimize = Color(0xFFFFBD2E)
private val MacControlMaximize = Color(0xFF27C93F)

@Composable
private fun TerminalPreview(palette: ThemePalette) {
    val fg = bestForeground(palette.darkBackground)
    val dimFg = fg.copy(alpha = 0.65f)
    val termStyle = TerminalTextStyle
    val promptStyle = termStyle.copy(color = palette.primary, fontWeight = FontWeight.Bold)
    val cmdStyle = termStyle.copy(color = fg)
    val dimStyle = termStyle.copy(color = dimFg)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(palette.darkBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.darkSurfaceVariant)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(MacControlClose)
            Spacer(Modifier.width(4.dp))
            Dot(MacControlMinimize)
            Spacer(Modifier.width(4.dp))
            Dot(MacControlMaximize)
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Row {
                Text("$", style = promptStyle)
                Spacer(Modifier.width(6.dp))
                Text("ls -la", style = cmdStyle)
            }
            Text("drwxr-xr-x  src/", style = dimStyle)
            Text("-rw-r--r--  README.md", style = dimStyle)
            Row {
                Text("$", style = promptStyle)
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.size(width = 7.dp, height = 14.dp).background(fg))
            }
        }
    }
}

@Composable
private fun Dot(c: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(c),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPaletteEditScreen(
    initial: ThemePalette?,
    onCancel: () -> Unit,
    onSave: (ThemePalette) -> Unit,
) {
    val isNew = initial == null
    var displayName by remember { mutableStateOf(initial?.displayName ?: "") }
    var primaryHex by remember { mutableStateOf(initial?.primary?.toHex() ?: "7AA2F7") }
    var containerHex by remember { mutableStateOf(initial?.primaryContainer?.toHex() ?: "24283B") }
    var bgHex by remember { mutableStateOf(initial?.darkBackground?.toHex() ?: "1A1B26") }

    val primary = parseHex(primaryHex)
    val container = parseHex(containerHex)
    val background = parseHex(bgHex)
    val canSave = displayName.isNotBlank() && primary != null && container != null && background != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建自定义配色" else "编辑自定义配色") },
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
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ColorField("主色", primaryHex) { primaryHex = it }
            ColorField("容器色", containerHex) { containerHex = it }
            ColorField("终端背景", bgHex) { bgHex = it }

            Text(
                "其余颜色会按对比度和明度自动派生。背景色较亮时整页会自动切到浅色界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            if (primary != null && container != null && background != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = background),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "预览",
                            color = bestForeground(background),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(primary),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                displayName.ifBlank { "未命名" },
                                color = bestForeground(background),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(container)
                                .padding(10.dp),
                        ) {
                            Text(
                                "容器色",
                                color = bestForeground(container),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = {
                    onSave(
                        customPalette(
                            id = initial?.name ?: UUID.randomUUID().toString(),
                            displayName = displayName.trim(),
                            primary = primary!!,
                            primaryContainer = container!!,
                            darkBackground = background!!,
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("保存", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun ColorField(label: String, hex: String, onChange: (String) -> Unit) {
    val parsed = parseHex(hex)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(parsed ?: Color.Transparent),
        )
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = hex,
            onValueChange = { onChange(it.removePrefix("#").uppercase().take(6)) },
            label = { Text(label) },
            placeholder = { Text("六位十六进制，如 7AA2F7") },
            singleLine = true,
            isError = parsed == null && hex.isNotEmpty(),
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun parseHex(s: String): Color? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    if (!t.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
    val v = t.toLongOrNull(16) ?: return null
    return Color((0xFF000000L or v).toInt())
}

private fun Color.toHex(): String {
    val argb = (alpha * 255).toInt() shl 24 or
        ((red * 255).toInt() shl 16) or
        ((green * 255).toInt() shl 8) or
        (blue * 255).toInt()
    return "%06X".format(argb and 0xFFFFFF)
}
