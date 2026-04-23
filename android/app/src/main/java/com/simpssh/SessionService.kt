package com.simpssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.simpssh.ui.SessionManager

// 只要还有一个 SSH 会话活着就必须跑前台服务:否则 Android 会激进地
// 挂起后台进程,TCP 连接随之被断。生命周期由 SessionManager 管理
// (首个会话打开时 start,最后一个关闭时 stop;tab 增删时 refresh 更新通知)。
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()

        // PARTIAL_WAKE_LOCK:保证 CPU 不休眠,tokio 的读循环才能继续轮询;不会点亮屏幕。
        // 不设超时,生命周期由最后一个会话关闭时 stopService + onDestroy 释放锁界定。
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "simpssh:session")
            .apply { acquire() }

        // WiFi 锁:强制射频保持高性能模式,避免熄屏后 WiFi 芯片进入省电/睡眠导致
        // TCP 套接字被静默断开。WIFI_MODE_FULL_HIGH_PERF 在新 API 已 deprecated,
        // 但官方没有等价替代,继续使用并压制警告。
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "simpssh:session")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                // 通知划走 / 点"全部断开" 都走这里。disposeAll 自己会调 SessionService.stop()
                // → onDestroy 释放锁 → 进程休眠。若 Activity 已死 currentRef 为 null,
                // manager 走不到 dispose;stopSelf 兜底,免得锁一直挂着。
                val m = SessionManager.currentOrNull()
                if (m != null) m.disposeAll() else stopSelf()
            }
            else -> postNotification()
        }
        return START_NOT_STICKY
    }

    private fun postNotification() {
        val manager = SessionManager.currentOrNull()
        val tabs = manager?.tabs?.toList().orEmpty()
        val (title, oneLine, bigText) = if (tabs.isEmpty()) {
            Triple("simpssh", "SSH 会话保持中", "SSH 会话保持中")
        } else {
            Triple(
                "simpssh · ${tabs.size} 个会话",
                tabs.joinToString("  ·  ") { it.shortTitle },
                tabs.joinToString("\n") { "· ${it.shortTitle} — ${it.shellStatus}" },
            )
        }

        val tapPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 划走通知 = 主动结束所有会话。Android 13+ 允许用户划 FGS 通知,之前版本划不了
        // 所以 deleteIntent 不会触发(但那时服务照跑不受影响)。
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(oneLine)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setContentIntent(tapPi)
            .setDeleteIntent(stopPi)
            .addAction(Notification.Action.Builder(null, "全部断开", stopPi).build())
            .build()

        if (!started) {
            startForeground(NOTIFICATION_ID, notif)
            started = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        started = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "SSH 会话",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "SSH 连接保持中" }
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "ssh_session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_ALL = "com.simpssh.action.STOP_ALL"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
        }

        // tab 增删时由 SessionManager 调:让通知文字反映最新会话列表。
        fun refresh(ctx: Context) {
            ctx.startService(Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}
