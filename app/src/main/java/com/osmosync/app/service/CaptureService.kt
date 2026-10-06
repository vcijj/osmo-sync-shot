package com.osmosync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.osmosync.app.App
import com.osmosync.app.shooter.ShooterState
import com.osmosync.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 连拍前台服务：定时连拍运行期间保活（BLE + 相机 + CPU），
 * 通知栏显示进度，屏幕熄灭也能继续拍摄。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        observeProgress()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定时连拍", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification("准备开始连拍...")
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsmoSync:capture").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    private fun observeProgress() {
        scope.launch {
            App.instance.shooter.state.collect { s ->
                val text = when (s) {
                    is ShooterState.Running -> {
                        val waiting = System.currentTimeMillis() < s.actualStartAtMs
                        if (waiting) {
                            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            "定时开始: ${sdf.format(java.util.Date(s.actualStartAtMs))} · 已设张数 ${if (s.totalShots == 0) "不限" else s.totalShots}"
                        } else {
                            "第 ${s.currentShot} 张" + (if (s.totalShots > 0) " / ${s.totalShots}" else "") + " · 间隔 ${s.config.intervalSeconds}s"
                        }
                    }
                    else -> "连拍已结束"
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(text))
                if (s is ShooterState.Idle) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("大疆同步连拍")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
