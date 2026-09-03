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
 * Foreground service running mc_manager.sh inside the embedded prefix.
 * Holds a partial wakelock for the duration of each command and captures
 * the full output (stdout+stderr+exit code) into MCPanel/last_run.log so
 * the VER REGISTRO screen always shows what happened.
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
            val lines = mutableListOf<String>()
            var rc = -1
            try {
                rc = Embed.runManager(this, cmd, args) { line ->
                    synchronized(lines) {
                        lines.add(line)
                        if (lines.size > 400) lines.removeAt(0)
                    }
                }
            } finally {
                try {
                    val log = Embed.lastRunLog(this)
                    log.parentFile?.mkdirs()
                    // bounded log: keep the tail (last ~256 KB), never grows forever
                    try {
                        if (log.length() > 512 * 1024) {
                            val txt = log.readText()
                            log.writeText(txt.substring(txt.length - 256 * 1024))
                        }
                    } catch (_: Exception) {}
                    val head = "── " + java.util.Date().toString() + "  mc_manager " + cmd +
                            (if (args.isNotEmpty()) " " + args.joinToString(" ") else "") +
                            "  →  exit " + rc + "\n"
                    val body = synchronized(lines) { lines.joinToString("\n") }
                    log.appendText(head + body + "\n\n")
                } catch (_: Exception) {}
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
