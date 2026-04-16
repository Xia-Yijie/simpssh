package com.simpssh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.simpssh.data.InitScript
import com.simpssh.data.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.simpssh_core.DirEntry
import uniffi.simpssh_core.SftpSession
import uniffi.simpssh_core.SshSession
import uniffi.simpssh_core.TerminalView
import java.util.UUID

/// One tab = one session = one connection to a server, holding both a
/// shell channel and (lazily) an SFTP channel. The UI switches between
/// the two views without opening a new tab.
class TabState(
    val id: String = UUID.randomUUID().toString(),
    val server: Server,
    val script: InitScript?,
) {
    enum class View { Terminal, Files }

    var view by mutableStateOf(View.Terminal)

    // ---- Shell --------------------------------------------------------------
    var shellStatus by mutableStateOf("连接中…")
    var sshSession: SshSession? = null
    var term: TerminalView? = null
    val rows: SnapshotStateList<String> = mutableStateListOf()
    var cursor by mutableStateOf("(0,0)")
    var readerJob: Job? = null

    // ---- Files (lazy) -------------------------------------------------------
    var filesStatus by mutableStateOf("未连接")
    var sftp: SftpSession? = null
    /// Guards against a second SFTP handshake while one is already in flight.
    /// Avoids relying on a string compare against `filesStatus`.
    var filesConnecting: Boolean = false
    var rootPath by mutableStateOf("/")
    val childrenByPath: SnapshotStateMap<String, List<DirEntry>> = mutableStateMapOf()
    val expanded: SnapshotStateList<String> = mutableStateListOf()

    val title: String
        get() = server.name.ifBlank { server.host } + " · " + (script?.name ?: "默认")
}

class SessionManager(private val scope: CoroutineScope) {
    val tabs: SnapshotStateList<TabState> = mutableStateListOf()
    var activeId by mutableStateOf<String?>(null)

    fun open(server: Server, script: InitScript?): String {
        val tab = TabState(server = server, script = script)
        tabs.add(tab)
        activeId = tab.id
        scope.launch(Dispatchers.IO) { connectShell(tab) }
        // SFTP lazy-connects on first switch to Files view.
        return tab.id
    }

    // ---- Shell --------------------------------------------------------------

    private suspend fun connectShell(tab: TabState) {
        try {
            val s = SshSession.connectPassword(
                host = tab.server.host,
                port = tab.server.port.toUShort(),
                user = tab.server.user,
                password = tab.server.password,
                cols = DEFAULT_TERM_COLS,
                rows = DEFAULT_TERM_ROWS,
            )
            val t = TerminalView(DEFAULT_TERM_COLS, DEFAULT_TERM_ROWS)
            tab.sshSession = s
            tab.term = t
            withContext(Dispatchers.Main) { tab.shellStatus = "已连接" }

            // Working directory first, then commands. Both are optional.
            tab.script?.workingDir?.trim()?.takeIf { it.isNotEmpty() }?.let { wd ->
                runCatching { s.write(("cd $wd\r").toByteArray()) }
            }
            tab.script?.content?.takeIf { it.isNotBlank() }?.let { body ->
                body.split('\n').map { it.trimEnd() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { line -> runCatching { s.write((line + "\r").toByteArray()) } }
            }

            tab.readerJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    val chunk = runCatching { s.read(SHELL_READ_TIMEOUT_MS) }.getOrNull() ?: break
                    if (chunk.isNotEmpty()) {
                        t.feed(chunk)
                        val snap = t.snapshot()
                        val cur = t.cursor()
                        val cursorStr = "(${cur.row},${cur.col})"
                        withContext(Dispatchers.Main) {
                            tab.rows.clear()
                            tab.rows.addAll(snap)
                            // Skip the assignment when nothing changed so Compose
                            // doesn't recompose the cursor display.
                            if (cursorStr != tab.cursor) tab.cursor = cursorStr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.shellStatus = formatError("连接", e) }
        }
    }

    fun send(tabId: String, bytes: ByteArray) {
        val s = tabs.firstOrNull { it.id == tabId }?.sshSession ?: return
        scope.launch(Dispatchers.IO) { runCatching { s.write(bytes) } }
    }

    // ---- Files --------------------------------------------------------------

    /// First-time switch to Files view triggers the SFTP handshake. Re-entry
    /// while already connected is a no-op.
    fun ensureFilesConnected(tab: TabState) {
        if (tab.sftp != null || tab.filesConnecting) return
        tab.filesConnecting = true
        tab.filesStatus = "连接中…"
        scope.launch(Dispatchers.IO) { connectFiles(tab) }
    }

    private suspend fun connectFiles(tab: TabState) {
        try {
            val s = SftpSession.connectPassword(
                host = tab.server.host,
                port = tab.server.port.toUShort(),
                user = tab.server.user,
                password = tab.server.password,
            )
            tab.sftp = s
            val home = runCatching { s.homeDir() }.getOrNull() ?: "/"
            val raw = tab.script?.workingDir?.trim().orEmpty()
            val root = when {
                raw.isEmpty() || raw == "~" -> home
                raw.startsWith("~/") -> joinPath(home, raw.removePrefix("~/"))
                raw.startsWith("/") -> raw
                else -> joinPath(home, raw)  // relative → join with home
            }
            withContext(Dispatchers.Main) {
                tab.filesStatus = "已连接"
                tab.rootPath = root
            }
            // If the chosen root fails to list, fall back to home.
            try {
                val listed = withContext(Dispatchers.IO) { s.listDir(root) }
                withContext(Dispatchers.Main) { tab.childrenByPath[root] = listed }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    tab.filesStatus = "「$root」不可读，回退到 home"
                    tab.rootPath = home
                }
                loadChildren(tab, home)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.filesStatus = formatError("连接", e) }
        } finally {
            tab.filesConnecting = false
        }
    }

    suspend fun loadChildren(tab: TabState, path: String) {
        val s = tab.sftp ?: return
        try {
            val list = withContext(Dispatchers.IO) { s.listDir(path) }
            withContext(Dispatchers.Main) { tab.childrenByPath[path] = list }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.filesStatus = formatError("读取", e) }
        }
    }

    suspend fun toggleExpand(tab: TabState, path: String) {
        if (tab.expanded.contains(path)) {
            withContext(Dispatchers.Main) { tab.expanded.remove(path) }
        } else {
            if (!tab.childrenByPath.containsKey(path)) loadChildren(tab, path)
            withContext(Dispatchers.Main) { tab.expanded.add(path) }
        }
    }

    suspend fun setRoot(tab: TabState, path: String) {
        withContext(Dispatchers.Main) {
            tab.rootPath = path
            tab.expanded.clear()
            tab.childrenByPath.clear()
        }
        loadChildren(tab, path)
    }

    suspend fun readFileBytes(tab: TabState, path: String, max: Int = PREVIEW_MAX_BYTES): ByteArray? {
        val s = tab.sftp ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { s.readFile(path, 0u, max.toUInt()) }.getOrNull()
        }
    }

    suspend fun writeFileBytes(tab: TabState, path: String, bytes: ByteArray): Throwable? =
        sftpOp(tab) { it.writeFile(path, bytes) }

    suspend fun mkdir(tab: TabState, path: String): Throwable? =
        sftpOp(tab) { it.mkdir(path) }

    suspend fun rename(tab: TabState, from: String, to: String): Throwable? =
        sftpOp(tab) { it.rename(from, to) }

    suspend fun delete(tab: TabState, entry: DirEntry): Throwable? =
        sftpOp(tab) { if (entry.isDir) it.deleteDir(entry.path) else it.deleteFile(entry.path) }

    /// Run a block on IO with the tab's SFTP session, returning a non-null
    /// `Throwable` only when the call failed (or no session was open).
    private suspend inline fun sftpOp(tab: TabState, crossinline block: (SftpSession) -> Unit): Throwable? {
        val s = tab.sftp ?: return IllegalStateException("not connected")
        return withContext(Dispatchers.IO) { runCatching { block(s) }.exceptionOrNull() }
    }

    // ---- Tab plumbing -------------------------------------------------------

    fun close(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.readerJob?.cancel()
        runCatching { tab.sshSession?.disconnect() }
        runCatching { tab.sftp?.disconnect() }
        tabs.remove(tab)
        if (activeId == id) activeId = tabs.lastOrNull()?.id
    }

    fun activate(id: String) {
        if (tabs.any { it.id == id }) activeId = id
    }

    fun disposeAll() {
        tabs.forEach { tab ->
            tab.readerJob?.cancel()
            runCatching { tab.sshSession?.disconnect() }
            runCatching { tab.sftp?.disconnect() }
        }
        tabs.clear()
        activeId = null
    }
}
