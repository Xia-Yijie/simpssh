package com.simpssh.ui

import android.content.Context
import com.simpssh.data.Server
import java.io.File
import java.security.MessageDigest
import uniffi.simpssh_core.DirEntry

/// Key = sha256(host|user|path|size|mtime);size/mtime 变即作废,避开同名更新脏读。
internal class SftpCache(ctx: Context) {
    private val root = File(ctx.cacheDir, "sftp-cache").apply { mkdirs() }
    @Volatile private var bytesSinceLastTrim: Long = 0

    init {
        cleanWritingOrphans()
        // 启动时先强制 trim 一次:前一次进程死前 bytesSinceLastTrim 没攒够就退出,
        // 缓存目录可能已经超过 cap,不主动修复的话要等下一次写入累计到 TRIM_BUDGET。
        trimNow(MAX_CACHE_BYTES)
    }

    fun keyFor(server: Server, entry: DirEntry): String {
        val raw = "${server.host}|${server.user}|${entry.path}|${entry.size}|${entry.mtime}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun finalFile(key: String): File = File(root, "$key.dat")
    fun tmpFile(key: String): File = File(root, "$key.dat.tmp")

    fun completeFileOf(key: String, expectedSize: Long): File? {
        val f = finalFile(key)
        return if (f.exists() && f.length() == expectedSize) f else null
    }

    fun readAll(key: String, expectedSize: Long): ByteArray? =
        completeFileOf(key, expectedSize)?.readBytes()

    fun writeAtomic(key: String, bytes: ByteArray) {
        val tmp = File(root, "$key.dat.writing")
        tmp.writeBytes(bytes)
        tmp.renameTo(finalFile(key))
        bytesSinceLastTrim += bytes.size
    }

    /// 写入累计过 TRIM_BUDGET 才真跑,避免每张预览都扫目录 + stat。
    fun trim(maxBytes: Long) {
        if (bytesSinceLastTrim < TRIM_BUDGET) return
        trimNow(maxBytes)
    }

    /// 保留最新一条,防单文件 > cap 时把刚下完的自己淘汰。
    private fun trimNow(maxBytes: Long) {
        bytesSinceLastTrim = 0
        data class Entry(val file: File, val size: Long, val mtime: Long)
        val snaps = root.listFiles()
            ?.filter { it.isFile }
            ?.map { Entry(it, it.length(), it.lastModified()) }
            ?.sortedBy { it.mtime }
            ?: return
        if (snaps.isEmpty()) return
        var total = snaps.sumOf { it.size }
        for (i in 0 until snaps.size - 1) {
            if (total <= maxBytes) break
            total -= snaps[i].size
            snaps[i].file.delete()
        }
    }

    fun recordWrite(bytes: Long) {
        bytesSinceLastTrim += bytes
    }

    /// rename 前进程被杀会留下 .dat.writing 孤儿。
    private fun cleanWritingOrphans() {
        root.listFiles()?.forEach {
            if (it.name.endsWith(".dat.writing")) it.delete()
        }
    }

    companion object {
        const val MAX_CACHE_BYTES: Long = 200L * 1024L * 1024L
        private const val TRIM_BUDGET: Long = 10L * 1024L * 1024L
    }
}
