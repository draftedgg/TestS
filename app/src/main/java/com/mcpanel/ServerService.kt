package com.mcpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service running mc_manager.sh inside the embedded prefix.
 * Holds a partial wakelock for the duration of each command.
 */
class ServerService : Service() {
    private val locks = mutableListOf<Pair<Thread, PowerManager.WakeLock>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Servidor MCPanel", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(if (isBusy()) "MCPanel: ejecutando…" else "MCPanel"))
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: return START_NOT_STICKY
        val args = intent.getStringArrayExtra(EXTRA_ARGS)?.toList() ?: emptyList()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCPanel:cmd")
        val t = Thread {
            wake.acquire(60 * 60 * 1000L)
            try {
                Embed.runManager(this, cmd, args)
            } finally {
                try { wake.release() } catch (_: Exception) {}
                synchronized(locks) { locks.removeAll { it.first === Thread.currentThread() } }
            }
        }
        synchronized(locks) { locks.add(t to wake) }
        t.start()
        return START_NOT_STICKY
    }

    private fun isBusy(): Boolean = synchronized(locks) { locks.any { it.first.isAlive } }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MCPanel").setContentText(text).setOngoing(true)
            .setContentIntent(pi).setSilent(true).build()
    }

    override fun onDestroy() {
        synchronized(locks) { locks.forEach { it.first.interrupt() } }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "server"
        const val NOTIF_ID = 10
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ARGS = "args"
    }
}
