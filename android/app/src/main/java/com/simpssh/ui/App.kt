package com.simpssh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import com.simpssh.data.PreferencesRepository
import com.simpssh.data.ServerRepository

private sealed class Screen {
    data object List : Screen()
    data class Edit(val id: String?) : Screen()
    data object Sessions : Screen()
    data object Settings : Screen()
    data class CustomPaletteEdit(val name: String?) : Screen()
}

@Composable
fun App(crashReport: String? = null) {
    val ctx = LocalContext.current
    var pendingCrash by remember { mutableStateOf(crashReport) }
    val repo = remember { ServerRepository(ctx) }
    val prefs = remember { PreferencesRepository(ctx) }
    val scope = rememberCoroutineScope()
    val sessions = remember { SessionManager(scope, ctx) }
    var servers by remember { mutableStateOf(repo.load()) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var themeName by remember { mutableStateOf(prefs.themeName) }
    val customPalettes = remember { prefs.loadCustomPalettes().toMutableStateList() }
    val palette = resolvePalette(themeName, customPalettes)
    var toolbarKeyIds by remember { mutableStateOf(prefs.toolbarKeyIds) }
    var terminalFontSize by remember { mutableStateOf(prefs.terminalFontSize) }
    var showModeBadges by remember { mutableStateOf(prefs.showModeBadges) }

    fun reload() { servers = repo.load() }

    DisposableEffect(Unit) {
        onDispose { sessions.disposeAll() }
    }

    SimpsshTheme(palette = palette) {
        pendingCrash?.let { body ->
            CrashReportDialog(body = body, onDismiss = { pendingCrash = null })
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 4 } + fadeOut(tween(220)))
            },
            label = "nav",
        ) { current ->
            when (current) {
                is Screen.List -> ServerListScreen(
                    servers = servers,
                    openSessionCount = sessions.tabs.size,
                    onAdd = { screen = Screen.Edit(id = null) },
                    onEdit = { screen = Screen.Edit(it.id) },
                    onConnect = { server, script ->
                        sessions.open(server, script)
                        screen = Screen.Sessions
                    },
                    onShowSessions = { screen = Screen.Sessions },
                    onShowSettings = { screen = Screen.Settings },
                )
                is Screen.Edit -> {
                    val s = current.id?.let { id -> servers.firstOrNull { it.id == id } }
                    ServerEditScreen(
                        initial = s,
                        onCancel = { screen = Screen.List },
                        onSave = { saved ->
                            repo.upsert(saved)
                            reload()
                            screen = Screen.List
                        },
                        onDelete = if (s != null) {
                            {
                                repo.delete(s.id)
                                reload()
                                screen = Screen.List
                            }
                        } else null,
                    )
                }
                is Screen.Sessions -> SessionsScreen(
                    manager = sessions,
                    toolbarKeyIds = toolbarKeyIds,
                    terminalFontSize = terminalFontSize,
                    onTerminalFontSizeChange = {
                        terminalFontSize = it
                        prefs.terminalFontSize = it
                    },
                    showModeBadges = showModeBadges,
                    onHome = { screen = Screen.List },
                )
                is Screen.Settings -> SettingsScreen(
                    currentTheme = themeName,
                    customPalettes = customPalettes,
                    toolbarKeyIds = toolbarKeyIds,
                    showModeBadges = showModeBadges,
                    onThemeChange = {
                        prefs.themeName = it
                        themeName = it
                    },
                    onToolbarKeysChange = {
                        prefs.toolbarKeyIds = it
                        toolbarKeyIds = it
                    },
                    onShowModeBadgesChange = {
                        prefs.showModeBadges = it
                        showModeBadges = it
                    },
                    onAddCustom = { screen = Screen.CustomPaletteEdit(name = null) },
                    onEditCustom = { screen = Screen.CustomPaletteEdit(name = it.name) },
                    onDeleteCustom = { p ->
                        customPalettes.removeAll { it.name == p.name }
                        prefs.saveCustomPalettes(customPalettes.toList())
                        if (themeName == p.name) {
                            themeName = "default"
                            prefs.themeName = "default"
                        }
                    },
                    onBack = { screen = Screen.List },
                )
                is Screen.CustomPaletteEdit -> {
                    val initial = current.name?.let { id -> customPalettes.firstOrNull { it.name == id } }
                    CustomPaletteEditScreen(
                        initial = initial,
                        onCancel = { screen = Screen.Settings },
                        onSave = { saved ->
                            val idx = customPalettes.indexOfFirst { it.name == saved.name }
                            if (idx >= 0) customPalettes[idx] = saved else customPalettes.add(saved)
                            prefs.saveCustomPalettes(customPalettes.toList())
                            themeName = saved.name
                            prefs.themeName = saved.name
                            screen = Screen.Settings
                        },
                    )
                }
            }
        }
    }
}
