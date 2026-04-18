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

// 只要还有一个 SSH 会话活着就必须跑前台服务:否则 Android 会激进地
// 挂起后台进程,TCP 连接随之被断。生命周期由 SessionManager 管理
// (首个会话打开时 start,最后一个关闭时 stop)。
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("simpssh")
            .setContentText("SSH 会话保持中")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // PARTIAL_WAKE_LOCK:保证 CPU 不休眠,tokio 的读循环才能继续轮询;
        // 不会点亮屏幕。不设超时,因为生命周期已由最后一个会话关闭时
        // stopService + onDestroy 释放锁来界定。
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "simpssh:session")
            .apply { acquire() }

        // WiFi 锁:强制射频保持高性能模式,避免熄屏后 WiFi 芯片进入省电/
        // 睡眠导致 TCP 套接字被静默断开。WIFI_MODE_FULL_HIGH_PERF 在新 API
        // 已标记 deprecated,但官方没有等价替代,继续使用并压制警告。
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "simpssh:session")
            .apply { acquire() }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
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

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}
