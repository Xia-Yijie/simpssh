package com.simpssh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import android.content.Context
import com.simpssh.SessionService
import com.simpssh.data.InitScript
import com.simpssh.data.Server
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.simpssh_core.CursorPos
import uniffi.simpssh_core.DirEntry
import uniffi.simpssh_core.SftpSession
import uniffi.simpssh_core.SshSession
import uniffi.simpssh_core.StyledRow
import uniffi.simpssh_core.TerminalView
import java.util.UUID
import java.nio.charset.StandardCharsets

class TabState(
    val id: String = UUID.randomUUID().toString(),
    val server: Server,
    val script: InitScript?,
) {
    enum class View { Terminal, Files }

    var view by mutableStateOf(View.Terminal)

    var shellStatus by mutableStateOf("连接中…")
    var sshSession: SshSession? = null
    var term: TerminalView? = null
    val rows: SnapshotStateList<StyledRow> = mutableStateListOf()
    var cursorRow: Int by mutableStateOf(0)
    var cursorCol: Int by mutableStateOf(0)
    var readerJob: Job? = null
    var cols: UShort = DEFAULT_TERM_COLS
    var terminalRows: UShort = DEFAULT_TERM_ROWS
    // 首次测量事件可能触发多次，防止重复发起连接。
    var connectStarted: Boolean = false
    var pendingRemoteResize: Job? = null
    var pendingScrollDelta: Int = 0
    var scrollJob: Job? = null
    var mouseMode by mutableStateOf(false)
    internal var selection: TerminalSelection? by mutableStateOf(null)
    // 浮动操作条监听此状态，拖拽期间隐藏，避免 Popup 重定位闪烁。
    internal var selectionDragging: Boolean by mutableStateOf(false)
    // Atomic：reader(IO)、send(IO)、Main 可能同时发起重连，确保只执行一次。
    val reconnecting = AtomicBoolean(false)

    var filesStatus by mutableStateOf("未连接")
    var filesBusy by mutableStateOf(false)
    var filesJob: Job? = null
    var filesProgressDone by mutableStateOf(0L)
    var filesProgressTotal by mutableStateOf(0L)
    var sftp: SftpSession? = null
    var filesConnecting: Boolean = false
    var rootPath by mutableStateOf("/")
    val childrenByPath: SnapshotStateMap<String, List<DirEntry>> = mutableStateMapOf()
    val expanded: SnapshotStateList<String> = mutableStateListOf()

    val title: String = (server.name.ifBlank { server.host }) + " · " + (script?.name ?: "默认")
}

class SessionManager(private val scope: CoroutineScope, private val ctx: Context) {
    val tabs: SnapshotStateList<TabState> = mutableStateListOf()
    var activeId by mutableStateOf<String?>(null)

    fun open(server: Server, script: InitScript?): String {
        val tab = TabState(server = server, script = script)
        tab.shellStatus = "等待终端尺寸…"
        tabs.add(tab)
        activeId = tab.id
        if (tabs.size == 1) SessionService.start(ctx)
        return tab.id
    }

    private suspend fun connectShell(tab: TabState) {
        try {
            val initialCols = tab.cols
            val initialRows = tab.terminalRows
            val s = SshSession.connectPassword(
                host = tab.server.host,
                port = tab.server.port.toUShort(),
                user = tab.server.user,
                password = tab.server.password,
                cols = initialCols,
                rows = initialRows,
            )
            val t = TerminalView(initialCols, initialRows)
            tab.sshSession = s
            tab.term = t
            withContext(Dispatchers.Main) { tab.shellStatus = STATUS_CONNECTED }

            runInitScripts(s, tab.script)
            tab.readerJob = scope.launch(Dispatchers.IO) { runReader(tab, s, t) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tab.shellStatus = formatError("连接", e) }
        }
    }

    private fun runInitScripts(s: SshSession, script: InitScript?) {
        script?.workingDir?.trim()?.takeIf { it.isNotEmpty() }?.let { wd ->
            runCatching { s.write(("cd $wd\r").toByteArray(StandardCharsets.UTF_8)) }
        }
        script?.content?.takeIf { it.isNotBlank() }?.let { body ->
            body.split('\n').map { it.trimEnd() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    runCatching { s.write((line + "\r").toByteArray(StandardCharsets.UTF_8)) }
                }
        }
    }

    // 捕获 s/t 局部引用：重连会替换 tab.sshSession/tab.term，
    // 防止本循环把已死会话的快照写进新会话的缓冲区。
    private suspend fun runReader(tab: TabState, s: SshSession, t: TerminalView) {
        while (true) {
            val chunk = runCatching { s.read(SHELL_READ_TIMEOUT_MS) }.getOrNull() ?: break
            if (chunk.isNotEmpty()) {
                t.feed(chunk)
                withContext(Dispatchers.Main) {
                    if (tab.sshSession !== s) return@withContext
                    applySnapshotToTab(tab, t.snapshotStyled())
                    applyCursorToTab(tab, t.cursor())
                }
            } else if (s.isClosed()) {
                triggerReconnect(tab)
                break
            }
        }
    }

    private fun teardownShell(tab: TabState) {
        tab.readerJob?.cancel()
        tab.readerJob = null
        tab.pendingRemoteResize?.cancel()
        tab.pendingRemoteResize = null
        runCatching { tab.sshSession?.disconnect() }
        tab.sshSession = null
        tab.term = null
    }

    private fun teardownSftp(tab: TabState) {
        runCatching { tab.sftp?.disconnect() }
        tab.sftp = null
        tab.filesConnecting = false
    }

    private fun triggerReconnect(tab: TabState) {
        if (!tab.reconnecting.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tab.shellStatus = "连接已断开，正在重连…"
                    tab.rows.clear()
                    tab.cursorRow = 0
                    tab.cursorCol = 0
                }
                teardownShell(tab)
                connectShell(tab)
            } finally {
                tab.reconnecting.set(false)
            }
        }
    }

    fun send(tabId: String, bytes: ByteArray) {
        writeOnIo(tabId) { s -> s.write(bytes) }
    }

    // Claude Code (Ink) 等 TUI 会缓冲连续输入，将其按 "up"/"down"/"tab"
    // 等关键字整体匹配；在首字节后插入 [TYPING_GAP_MS] 间隔打断解析器合并。
    fun sendTyped(tabId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        writeOnIo(tabId) { s ->
            if (bytes.size > 1 && isKeywordTrap(bytes)) {
                s.write(bytes.copyOfRange(0, 1))
                delay(TYPING_GAP_MS)
                s.write(bytes.copyOfRange(1, bytes.size))
            } else {
                s.write(bytes)
            }
        }
    }

    // 写入必须在 IO 线程执行；任何异常触发重连。
    private inline fun writeOnIo(
        tabId: String,
        crossinline block: suspend (SshSession) -> Unit,
    ) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val s = tab.sshSession ?: return
        tab.term?.resetScroll()
        scope.launch(Dispatchers.IO) {
            val err = runCatching { block(s) }.exceptionOrNull()
            if (err != null) triggerReconnect(tab)
        }
    }

    private fun isKeywordTrap(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_KEYWORD_LEN) return false
        for (b in bytes) if (b < 0) return false  // 非 ASCII 不可能匹配键名
        return bytes.toString(Charsets.US_ASCII).lowercase() in KEYWORD_TRAP_SET
    }

    // 批处理滚轮滚动：多次 delta 累积到 pendingScrollDelta，
    // 单 worker 消费；scrollDisplay 返回 false 时跳过 snapshot，
    // 让同一次手势里的多帧合并为一次渲染。
    fun scrollTerminal(tabId: String, delta: Int) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        tab.pendingScrollDelta += delta
        if (tab.scrollJob?.isActive == true) return
        tab.scrollJob = scope.launch {
            while (tab.pendingScrollDelta != 0) {
                val d = tab.pendingScrollDelta
                tab.pendingScrollDelta = 0
                val t = tab.term ?: break
                val moved = withContext(Dispatchers.IO) { t.scrollDisplay(d) }
                if (!moved) continue
                val snap = withContext(Dispatchers.IO) { t.snapshotStyled() }
                val cur = withContext(Dispatchers.IO) { t.cursor() }
                applySnapshotToTab(tab, snap)
                applyCursorToTab(tab, cur)
            }
        }
    }

    // 原地 diff：保持未变行的 Compose 身份，避免 LazyColumn 重建。
    private fun applySnapshotToTab(tab: TabState, snap: List<StyledRow>) {
        while (tab.rows.size > snap.size) {
            tab.rows.removeAt(tab.rows.lastIndex)
        }
        snap.forEachIndexed { i, line ->
            if (i >= tab.rows.size) tab.rows.add(line)
            else if (tab.rows[i] != line) tab.rows[i] = line
        }
    }

    private fun applyCursorToTab(tab: TabState, cur: CursorPos) {
        val newRow = cur.row.toInt()
        val newCol = cur.col.toInt()
        if (tab.cursorRow != newRow) tab.cursorRow = newRow
        if (tab.cursorCol != newCol) tab.cursorCol = newCol
    }

    fun resizeTerminal(tabId: String, cols: UShort, rows: UShort) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.connectStarted) {
            tab.connectStarted = true
            tab.cols = cols
            tab.terminalRows = rows
            scope.launch(Dispatchers.IO) { connectShell(tab) }
            return
        }
        if (tab.cols == cols && tab.terminalRows == rows) return
        tab.cols = cols
        tab.terminalRows = rows
        val s = tab.sshSession ?: return
        // 合并 IME 滑动动画每帧约 16 次尺寸事件，
        // 在末尾统一做一次 grid 调整 + SIGWINCH。
        tab.pendingRemoteResize?.cancel()
        tab.pendingRemoteResize = scope.launch(Dispatchers.IO) {
            delay(REMOTE_RESIZE_COALESCE_MS)
            tab.term?.resize(cols, rows)
            runCatching { s.resize(cols, rows) }
        }
    }

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
                else -> joinPath(home, raw)
            }
            withContext(Dispatchers.Main) {
                tab.filesStatus = STATUS_CONNECTED
                tab.rootPath = root
            }
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
            reportFilesError(tab, "读取", e)
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

    suspend fun readFileBytes(
        tab: TabState,
        path: String,
        offset: ULong = 0u,
        max: Int = PREVIEW_MAX_BYTES,
    ): ByteArray? {
        val s = tab.sftp ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { s.readFile(path, offset, max.toUInt()) }.getOrNull()
        }
    }

    // 协作式取消：s.readFile 无法在调用中途中断，
    // 但块之间的 ensureActive() 能在一个 chunk 边界内退出循环。
    suspend fun streamFileTo(
        tab: TabState,
        path: String,
        out: java.io.OutputStream,
        onProgress: (suspend (Long) -> Unit)? = null,
    ): Long {
        val s = tab.sftp ?: throw IllegalStateException("SFTP not connected")
        return withContext(Dispatchers.IO) {
            var total = 0L
            while (true) {
                ensureActive()
                val chunk = s.readFile(path, total.toULong(), DOWNLOAD_CHUNK_BYTES.toUInt())
                if (chunk.isEmpty()) break
                out.write(chunk)
                total += chunk.size
                onProgress?.invoke(total)
                if (chunk.size < DOWNLOAD_CHUNK_BYTES) break
            }
            total
        }
    }

    // 跑在 manager 的 scope 上，视图切换不打断操作。
    // 先取消正在进行的操作；block 可捕获 CancellationException 做清理，但必须重抛。
    fun launchFileOp(tab: TabState, block: suspend () -> Unit): Job {
        tab.filesJob?.cancel()
        lateinit var newJob: Job
        newJob = scope.launch {
            try {
                withContext(Dispatchers.Main) { tab.filesBusy = true }
                block()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    tab.filesBusy = false
                    tab.filesProgressDone = 0L
                    tab.filesProgressTotal = 0L
                    if (tab.filesJob === newJob) tab.filesJob = null
                }
            }
        }
        tab.filesJob = newJob
        return newJob
    }

    fun cancelFileOp(tab: TabState) {
        tab.filesJob?.cancel()
    }

    suspend fun writeFileBytes(tab: TabState, path: String, bytes: ByteArray): Throwable? =
        sftpOp(tab) { it.writeFile(path, bytes) }

    suspend fun mkdir(tab: TabState, path: String): Throwable? =
        sftpOp(tab) { it.mkdir(path) }

    suspend fun rename(tab: TabState, from: String, to: String): Throwable? =
        sftpOp(tab) { it.rename(from, to) }

    suspend fun delete(tab: TabState, entry: DirEntry): Throwable? =
        sftpOp(tab) { if (entry.isDir) it.deleteDir(entry.path) else it.deleteFile(entry.path) }

    private suspend inline fun sftpOp(tab: TabState, crossinline block: (SftpSession) -> Unit): Throwable? {
        val s = tab.sftp ?: return IllegalStateException("not connected")
        return withContext(Dispatchers.IO) { runCatching { block(s) }.exceptionOrNull() }
    }

    fun close(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        // 强制置位：让任何正在进行的重连尝试立即放弃。
        tab.reconnecting.set(true)
        teardownShell(tab)
        teardownSftp(tab)
        tabs.remove(tab)
        if (activeId == id) activeId = tabs.lastOrNull()?.id
        if (tabs.isEmpty()) SessionService.stop(ctx)
    }

    fun activate(id: String) {
        if (tabs.any { it.id == id }) activeId = id
    }

    fun disposeAll() {
        tabs.forEach { tab ->
            teardownShell(tab)
            teardownSftp(tab)
        }
        tabs.clear()
        activeId = null
        SessionService.stop(ctx)
    }
}
