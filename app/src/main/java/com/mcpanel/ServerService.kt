package com.mcpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Own foreground service: runs mc_manager.sh inside the embedded prefix.
 * Replaces the old Termux RUN_COMMAND intent entirely.
 */
class ServerService : Service() {
    private lateinit var wake: PowerManager.WakeLock
    private val jobs = mutableListOf<Thread>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Servidor MCPanel", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("MCPanel listo"))
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: return START_NOT_STICKY
        val script = File(Embed.home(this), "mcpanel/mc_manager.sh")
        if (!script.exists()) {
            Embed.installScript(this, R.raw.mc_manager, script)
        }
        val t = Thread {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCPanel:cmd").apply { acquire(15 * 60 * 1000L) }
            try {
                Embed.exec(this, "bash '${script.absolutePath}' $cmd", emptyList()) { line ->
                    // progress lines go to install.log anyway; nothing in-memory needed
                }
            } finally {
                try { wake.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
        jobs.add(t)
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MCPanel").setContentText(text).setOngoing(true)
            .setContentIntent(pi).setSilent(true).build()
    }

    override fun onDestroy() { jobs.forEach { it.interrupt() }; super.onDestroy() }

    companion object {
        const val CHANNEL = "server"
        const val NOTIF_ID = 10
        const val EXTRA_CMD = "cmd"
    }
}
