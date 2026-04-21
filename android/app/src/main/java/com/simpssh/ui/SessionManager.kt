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

private const val STARTUP_DRAIN_IDLE_MS: UInt = 80u
private const val STARTUP_DRAIN_MAX_MS = 500L

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
    // 用户在 tab 上长按改的会话名;空则回退 server.name。只存会话内,app 重启不保留。
    var displayName: String by mutableStateOf("")
    val shortTitle: String get() = displayName.ifBlank { server.name.ifBlank { server.host } }
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
            drainStartupOutput(s)
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

    private fun drainStartupOutput(s: SshSession) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val chunk = runCatching { s.read(STARTUP_DRAIN_IDLE_MS) }.getOrDefault(ByteArray(0))
            if (chunk.isEmpty()) break
            if (System.currentTimeMillis() - startedAt >= STARTUP_DRAIN_MAX_MS) break
        }
    }

    // 局部 s/t:重连会替换 tab.sshSession/tab.term,防止旧循环写入新会话缓冲。
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

    // Claude Code (Ink) 等 TUI 会把连续字节整段匹配成 "up"/"tab" 等键名;
    // 首字节后插入 TYPING_GAP_MS 间隔打断解析器合并。
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

    // 一次手势里多帧合并成一次 snapshot:scrollDisplay 返回 false 时跳过渲染。
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

    // 原地 diff:保留未变行的 Compose 身份,避免 LazyColumn 重建。
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
        // IME 滑动动画每帧 ~16 次尺寸事件,合并成一次 grid 调整 + SIGWINCH。
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

    /// 刷新 path 及其已展开的子目录;折叠子目录的缓存清掉,下次展开时重拉。
    /// listDir 并发发起 —— russh-sftp 会在同一 channel 上多路复用。
    suspend fun refreshDir(tab: TabState, path: String) {
        val prefix = if (path == "/") "/" else "$path/"
        val expandedUnder = tab.expanded.filter { it != path && it.startsWith(prefix) }
        val expandedSet = expandedUnder.toSet()
        withContext(Dispatchers.Main) {
            val collapsedCached = tab.childrenByPath.keys
                .filter { it != path && it.startsWith(prefix) && it !in expandedSet }
            collapsedCached.forEach { tab.childrenByPath.remove(it) }
        }
        kotlinx.coroutines.coroutineScope {
            launch { loadChildren(tab, path) }
            for (p in expandedUnder) launch { loadChildren(tab, p) }
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

    suspend fun statEntry(tab: TabState, path: String): DirEntry? {
        val s = tab.sftp ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { s.stat(path) }.getOrNull()
        }
    }

    /// 部分字节不写缓存:key 由 entry.size 决定,半截写进去会在下次完整命中判断里被当整文件。
    suspend fun readFileBytesCached(
        tab: TabState,
        entry: DirEntry,
        max: Int = PREVIEW_MAX_BYTES,
    ): ByteArray? {
        val key = cache.keyFor(tab.server, entry)
        cache.readAll(key, entry.size.toLong())?.let { return it }
        val bytes = readFileBytes(tab, entry.path, max = max) ?: return null
        if (bytes.size.toLong() == entry.size.toLong()) {
            cache.writeAtomic(key, bytes)
            cache.trim(SftpCache.MAX_CACHE_BYTES)
        }
        return bytes
    }

    internal fun newDownloadNotifier(): DownloadNotifier = DownloadNotifier(ctx)

    /// s.readFile 本身不可打断,只能在块边界 ensureActive(),大文件才能按块退出。
    suspend fun streamFileTo(
        tab: TabState,
        entry: DirEntry,
        out: java.io.OutputStream,
        onProgress: (suspend (Long) -> Unit)? = null,
    ): Long {
        val s = tab.sftp ?: throw IllegalStateException("SFTP not connected")
        // entry 可能来自过期的 listDir 快照;重 stat 避开 cache key 指向旧 .dat。
        val live = statEntry(tab, entry.path) ?: entry
        val key = cache.keyFor(tab.server, live)
        val expected = live.size.toLong()

        cache.completeFileOf(key, expected)?.let { cached ->
            return withContext(Dispatchers.IO) {
                cached.inputStream().use { pumpStream(it, out, 0L, onProgress) }
            }
        }

        return withContext(Dispatchers.IO) {
            val tmp = cache.tmpFile(key)
            var total = tmp.length()
            if (total > expected) { tmp.delete(); total = 0L }
            // SAF 目标无法 seek,必须把已缓存段先重放给用户 out。
            if (total > 0L) tmp.inputStream().use { pumpStream(it, out, 0L, onProgress, limit = total) }

            java.io.FileOutputStream(tmp, /* append = */ true).use { cacheOut ->
                while (true) {
                    ensureActive()
                    val chunk = readChunkWithRetry(s, live.path, total)
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    cacheOut.write(chunk)
                    total += chunk.size
                    onProgress?.invoke(total)
                    if (chunk.size < DOWNLOAD_CHUNK_BYTES) break
                }
            }

            if (total == expected) {
                tmp.renameTo(cache.finalFile(key))
                cache.recordWrite(total)
                cache.trim(SftpCache.MAX_CACHE_BYTES)
            }
            total
        }
    }

    private suspend fun pumpStream(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        startedAt: Long,
        onProgress: (suspend (Long) -> Unit)?,
        limit: Long = Long.MAX_VALUE,
    ): Long {
        val buf = ByteArray(DOWNLOAD_CHUNK_BYTES)
        var copied = startedAt
        var remaining = limit
        while (remaining > 0L) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            copied += n
            remaining -= n
            onProgress?.invoke(copied)
        }
        return copied
    }

    private suspend fun readChunkWithRetry(
        s: SftpSession,
        path: String,
        offset: Long,
    ): ByteArray {
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < 3) {
            try {
                return s.readFile(path, offset.toULong(), DOWNLOAD_CHUNK_BYTES.toUInt())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastErr = e
                attempt++
                // russh 重连窗口
                if (attempt < 3) delay(500L shl (attempt - 1))
            }
        }
        throw lastErr ?: java.io.IOException("readFile failed (no error captured)")
    }

    private val cache: SftpCache by lazy { SftpCache(ctx) }

    // scope 不随视图切换取消;block 捕 CancellationException 做清理后必须重抛。
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
