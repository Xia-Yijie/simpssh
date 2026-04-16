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
import androidx.compose.ui.platform.LocalContext
import com.simpssh.data.ServerRepository

private sealed class Screen {
    data object List : Screen()
    data class Edit(val id: String?) : Screen()
    data object Sessions : Screen()
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val repo = remember { ServerRepository(ctx) }
    val scope = rememberCoroutineScope()
    val sessions = remember { SessionManager(scope) }
    var servers by remember { mutableStateOf(repo.load()) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }

    fun reload() { servers = repo.load() }

    DisposableEffect(Unit) {
        onDispose { sessions.disposeAll() }
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
                    sessions.openShell(server, script)
                    screen = Screen.Sessions
                },
                onOpenFiles = { server ->
                    sessions.openFiles(server)
                    screen = Screen.Sessions
                },
                onShowSessions = { screen = Screen.Sessions },
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
                onHome = { screen = Screen.List },
            )
        }
    }
}
