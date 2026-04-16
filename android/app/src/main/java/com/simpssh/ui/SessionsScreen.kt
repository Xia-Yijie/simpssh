package com.simpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(manager: SessionManager, onHome: () -> Unit) {
    val activeId = manager.activeId
    val active = manager.tabs.firstOrNull { it.id == activeId }

    LaunchedEffect(manager.tabs.size) {
        if (manager.tabs.isEmpty()) onHome()
    }

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
                TabState.View.Terminal -> ShellBody(tab, onSend = { line ->
                    manager.send(tab.id, line.toByteArray())
                })
                TabState.View.Files -> FilesBody(tab, manager)
            }
        }
    }
}

@Composable
private fun ShellBody(tab: TabState, onSend: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(tab.rows.size) {
        if (tab.rows.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(tab.rows.lastIndex) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
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
        Box(
            modifier = Modifier
                .fillMaxWidth().weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            ) {
                items(tab.rows) { line ->
                    Text(
                        line,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            var input by remember(tab.id) { mutableStateOf("") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("命令行（Enter 发送）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        onSend(input + "\r")
                        input = ""
                    },
                ),
            )
        }
        Text(
            "光标 ${tab.cursor}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}
