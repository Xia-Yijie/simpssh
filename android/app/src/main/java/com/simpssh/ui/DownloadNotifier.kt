package com.simpssh.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/// API 33+ 未授 POST_NOTIFICATIONS 时所有调用静默失败,不阻塞下载本身。
internal class DownloadNotifier(private val ctx: Context) {
    private val manager = ctx.getSystemService(NotificationManager::class.java)
    // 复用同一个 builder,每 500 ms 一次 progress 调用时只改 progress/contentText,
    // 避免反复 allocate 整套 Notification 对象。
    private var progressBuilder: NotificationCompat.Builder? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "文件传输", NotificationManager.IMPORTANCE_LOW).apply {
                description = "SFTP 下载进度"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(ch)
        }
    }

    fun progress(id: Int, name: String, done: Long, total: Long) {
        val pct = if (total > 0) (done * 100L / total).toInt().coerceIn(0, 100) else 0
        val builder = progressBuilder ?: NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .also { progressBuilder = it }
        builder
            .setContentText(if (total > 0) "${humanBytes(done)} / ${humanBytes(total)}" else humanBytes(done))
            .setProgress(100, pct, total <= 0)
        runCatching { manager.notify(id, builder.build()) }
    }

    fun done(id: Int, name: String, total: Long) {
        progressBuilder = null
        val nb = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("已下载")
            .setContentText("$name  ${humanBytes(total)}")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(id, nb) }
    }

    fun error(id: Int, name: String, msg: String) {
        progressBuilder = null
        val nb = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText("$name: $msg")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(id, nb) }
    }

    fun cancel(id: Int) {
        progressBuilder = null
        runCatching { manager.cancel(id) }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private val idSeq = java.util.concurrent.atomic.AtomicInteger(1)
        fun nextId(): Int = idSeq.incrementAndGet()
    }
}
