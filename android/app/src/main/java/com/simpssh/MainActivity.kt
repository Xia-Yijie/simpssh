package com.simpssh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.simpssh.ui.App
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import uniffi.simpssh_core.initNativeLogging

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initNativeLogging()
        installCrashHandler()
        // 关闭 decor fit,Compose 的 WindowInsets.ime 才能拿到真实键盘高度
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val crashReport = readAndClearLastCrash()
        setContent { App(crashReport = crashReport) }
    }

    // 未捕获异常写入 filesDir/last_crash.txt,下次启动读取并清空;
    // 无需 adb 即可在应用内看到崩溃堆栈
    private fun installCrashHandler() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(filesDir, CRASH_FILENAME)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("simpssh", "uncaught on ${t.name}", e)
            runCatching {
                val sw = StringWriter()
                PrintWriter(sw).use { e.printStackTrace(it) }
                crashFile.writeText(
                    "[${java.util.Date()}] thread=${t.name}\n${sw}",
                )
            }
            prior?.uncaughtException(t, e)
        }
    }

    private fun readAndClearLastCrash(): String? {
        val f = File(filesDir, CRASH_FILENAME)
        if (!f.exists()) return null
        val body = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return body
    }

    private companion object {
        const val CRASH_FILENAME = "last_crash.txt"
    }
}
