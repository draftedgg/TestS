package com.mcpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat


/**
 * Foreground download service: partial wakelock only during download,
 * progress notification, retries handled by Apis.downloadToFile.
 * Optionally chains a mc_manager.sh command after a successful download
 * (e.g. mod-install) via ServerService.
 */
class DownloadService : Service() {
    private lateinit var wake: PowerManager.WakeLock
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: url.substringAfterLast('/')
        val afterCmd = intent.getStringExtra(EXTRA_AFTER_CMD)
        val afterArgs = intent.getStringArrayExtra(EXTRA_AFTER_ARGS)?.toList()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCPanel:download").apply { acquire(30 * 60 * 1000L) }
        startForeground(NOTIF_ID, buildNotification(name, -1))
        thread?.interrupt()
        thread = Thread {
            val inbox = File(Embed.sharedDir(this), "inbox")
            val dest = File(inbox, name)
            val ok = Apis.downloadToFile(url, dest)
            if (ok && afterCmd != null) {
                // hand off to ServerService: install from inbox (runs in parallel;
                // the script waits on the file existing and inbox-first logic)
                val i = Intent(this, ServerService::class.java)
                    .putExtra(ServerService.EXTRA_CMD, afterCmd)
                    .putExtra(ServerService.EXTRA_ARGS, (afterArgs ?: listOf(name)).toTypedArray())
                ContextCompat.startForegroundService(this, i)
            }
            ui {
                try { wake.release() } catch (_: Exception) {}
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(DONE_ID, buildNotification(if (ok) "Descarga completa: $name" else "Error de descarga: $name", 100))
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun ui(block: () -> Unit) { android.os.Handler(mainLooper).post(block) }

    private fun buildNotification(text: String, progress: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MCPanel").setContentText(text).setOngoing(progress < 0)
            .setContentIntent(pi).setSilent(true)
        if (progress < 0) b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() { thread?.interrupt(); super.onDestroy() }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 1
        const val DONE_ID = 2
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_AFTER_CMD = "after_cmd"
        const val EXTRA_AFTER_ARGS = "after_args"
    }
}
