package com.mcpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground download service: partial wakelock only during download,
 * progress notification, retries handled by Apis.downloadToFile.
 */
class DownloadService : Service() {
    private lateinit var wake: PowerManager.WakeLock
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: url.substringAfterLast('/')
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCPanel:download").apply { acquire(10 * 60 * 1000L) }
        startForeground(NOTIF_ID, buildNotification(name, 0, 0))
        thread?.interrupt()
        thread = Thread {
            val inbox = File(Environment.getExternalStorageDirectory(), "MCPanel/inbox")
            val dest = File(inbox, name)
            val ok = Apis.downloadToFile(url, dest)
            wake.release()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(DONE_ID, buildNotification(if (ok) "Descarga completa: $name" else "Error de descarga: $name", 100, 100))
            stopSelf()
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MCPanel").setContentText(text).setOngoing(max > 0 && progress < max)
            .setContentIntent(pi).setSilent(true)
        if (max > 0) b.setProgress(max, progress, progress == 0)
        return b.build()
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() { thread?.interrupt(); super.onDestroy() }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 1
        const val DONE_ID = 2
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
    }
}
