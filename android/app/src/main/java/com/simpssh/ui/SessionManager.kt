package com.simpssh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
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

private const val DEFAULT_COLS: UShort = 80u
private const val DEFAULT_ROWS: UShort = 24u

/// Sealed because shell and files tabs hold very different state.
sealed class TabState {
    abstract val id: String
    abstract val server: Server
    abstract val title: String
    abstract var status: String

    class Shell(
        override val id: String,
        override val server: Server,
        val script: InitScript?,
    ) : TabState() {
        override var status by mutableStateOf("连接中…")
        var session: SshSession? = null
        var term: TerminalView? = null
        val rows: SnapshotStateList<String> = mutableStateListOf()
        var cursor by mutableStateOf("(0,0)")
        var readerJob: Job? = null

        override val title: String
            get() = server.name.ifBlank { server.host } + " · " + (script?.name ?: "默认")
    }

    class Files(
        override val id: String,
        override val server: Server,
    ) : TabState() {
        override var status by mutableStateOf("连接中…")
        var sftp: SftpSession? = null
        /// Top of the tree the user sees. Children of this path are rendered
        /// at depth 0; the root itself is not shown as a row.
        var rootPath by mutableStateOf("/")
        /// dirPath -> its entries, cached after first listDir.
        val childrenByPath: SnapshotStateMap<String, List<DirEntry>> = mutableStateMapOf()
        /// Currently expanded folders.
        val expanded: SnapshotStateList<String> = mutableStateListOf()

        override val title: String
            get() = server.name.ifBlank { server.host } + " · 文件"
    }
}

class SessionManager(private val scope: CoroutineScope) {
    val tabs: SnapshotStateList<TabState> = mutableStateListOf()
    var activeId by mutableStateOf<String?>(null)

    // ---- Shell ----------------------------------------------------------

    fun openShell(server: Server, script: InitScript?): String {
        val tab = TabState.Shell(id = UUID.randomUUID().toString(), server = server, script = script)
        tabs.add(tab)
        activeId = tab.id
        scope.launch(Dispatchers.IO) { connectShell(tab) }
        return tab.id
    }

    private suspend fun connectShell(tab: TabState.Shell) {
        try {
            val s = SshSession.connectPassword(
                host = tab.server.host,
                port = tab.server.port.toUShort(),
                user = tab.server.user,
                password = tab.server.password,
                cols = DEFAULT_COLS,
                rows = DEFAULT_ROWS,
            )
            val t = TerminalView(DEFAULT_COLS, DEFAULT_ROWS)
            tab.session = s
            tab.term = t
            withContext(Dispatchers.Main) { tab.status = "已连接" }

            tab.script?.content?.takeIf { it.isNotBlank() }?.let { body ->
                body.split('\n').map { it.trimEnd() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { line -> runCatching { s.write((line + "\r").toByteArray()) } }
            }

            tab.readerJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    val chunk = runCatching { s.read(200u) }.getOrNull() ?: break
                    if (chunk.isNotEmpty()) {
                        t.feed(chunk)
                        val snap = t.snapshot()
                        val cur = t.cursor()
                        withContext(Dispatchers.Main) {
                            tab.rows.clear()
                            tab.rows.addAll(snap)
                            tab.cursor = "(${cur.row},${cur.col})"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.status = "连接失败: ${e.message}" }
        }
    }

    fun send(tabId: String, bytes: ByteArray) {
        val s = (tabs.firstOrNull { it.id == tabId } as? TabState.Shell)?.session ?: return
        scope.launch(Dispatchers.IO) { runCatching { s.write(bytes) } }
    }

    // ---- Files (SFTP) ---------------------------------------------------

    fun openFiles(server: Server): String {
        val tab = TabState.Files(id = UUID.randomUUID().toString(), server = server)
        tabs.add(tab)
        activeId = tab.id
        scope.launch(Dispatchers.IO) { connectFiles(tab) }
        return tab.id
    }

    private suspend fun connectFiles(tab: TabState.Files) {
        try {
            val s = SftpSession.connectPassword(
                host = tab.server.host,
                port = tab.server.port.toUShort(),
                user = tab.server.user,
                password = tab.server.password,
            )
            tab.sftp = s
            val home = runCatching { s.homeDir() }.getOrNull() ?: "/"
            withContext(Dispatchers.Main) {
                tab.status = "已连接"
                tab.rootPath = home
            }
            loadChildren(tab, home)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.status = "连接失败: ${e.message}" }
        }
    }

    /// Lists a directory and caches the result. No-op if the session is dead.
    suspend fun loadChildren(tab: TabState.Files, path: String) {
        val s = tab.sftp ?: return
        try {
            val list = withContext(Dispatchers.IO) { s.listDir(path) }
            withContext(Dispatchers.Main) { tab.childrenByPath[path] = list }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.status = "读取失败: ${e.message}" }
        }
    }

    suspend fun toggleExpand(tab: TabState.Files, path: String) {
        if (tab.expanded.contains(path)) {
            withContext(Dispatchers.Main) { tab.expanded.remove(path) }
        } else {
            if (!tab.childrenByPath.containsKey(path)) loadChildren(tab, path)
            withContext(Dispatchers.Main) { tab.expanded.add(path) }
        }
    }

    suspend fun setRoot(tab: TabState.Files, path: String) {
        withContext(Dispatchers.Main) {
            tab.rootPath = path
            tab.expanded.clear()
            tab.childrenByPath.clear()
        }
        loadChildren(tab, path)
    }

    suspend fun readFileBytes(tab: TabState.Files, path: String, max: Int = 256 * 1024): ByteArray? {
        val s = tab.sftp ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { s.readFile(path, 0u, max.toUInt()) }.getOrNull()
        }
    }

    suspend fun writeFileBytes(tab: TabState.Files, path: String, bytes: ByteArray): Throwable? {
        val s = tab.sftp ?: return IllegalStateException("not connected")
        return withContext(Dispatchers.IO) {
            runCatching { s.writeFile(path, bytes) }.exceptionOrNull()
        }
    }

    suspend fun mkdir(tab: TabState.Files, path: String): Throwable? {
        val s = tab.sftp ?: return IllegalStateException("not connected")
        return withContext(Dispatchers.IO) {
            runCatching { s.mkdir(path) }.exceptionOrNull()
        }
    }

    suspend fun rename(tab: TabState.Files, from: String, to: String): Throwable? {
        val s = tab.sftp ?: return IllegalStateException("not connected")
        return withContext(Dispatchers.IO) {
            runCatching { s.rename(from, to) }.exceptionOrNull()
        }
    }

    suspend fun delete(tab: TabState.Files, entry: DirEntry): Throwable? {
        val s = tab.sftp ?: return IllegalStateException("not connected")
        return withContext(Dispatchers.IO) {
            runCatching {
                if (entry.isDir) s.deleteDir(entry.path) else s.deleteFile(entry.path)
            }.exceptionOrNull()
        }
    }

    // ---- Tab plumbing ---------------------------------------------------

    fun close(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        when (tab) {
            is TabState.Shell -> {
                tab.readerJob?.cancel()
                runCatching { tab.session?.disconnect() }
            }
            is TabState.Files -> runCatching { tab.sftp?.disconnect() }
        }
        tabs.remove(tab)
        if (activeId == id) activeId = tabs.lastOrNull()?.id
    }

    fun activate(id: String) {
        if (tabs.any { it.id == id }) activeId = id
    }

    fun disposeAll() {
        tabs.forEach { tab ->
            when (tab) {
                is TabState.Shell -> {
                    tab.readerJob?.cancel()
                    runCatching { tab.session?.disconnect() }
                }
                is TabState.Files -> runCatching { tab.sftp?.disconnect() }
            }
        }
        tabs.clear()
        activeId = null
    }
}
