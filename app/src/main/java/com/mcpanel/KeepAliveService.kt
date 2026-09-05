package com.mcpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * While the Minecraft server (or the playit tunnel) is running, this keeps
 * the phone awake (partial wakelock) and the app process alive (ongoing
 * foreground notification) so the server keeps ticking with the screen off
 * and is not killed when the app is swiped from recents.
 *
 * Every few seconds it runs `mc_manager status` inside the embedded prefix
 * and re-reads state.json: if the server/tunnel actually died (crash, OOM),
 * state.json is corrected to running=false and this service stops itself,
 * releasing the wakelock. Nothing stays locked forever.
 */
class KeepAliveService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Servidor activo", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (wake == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCPanel:server-up").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        }
        if (watchJob?.isActive != true) watchJob = watch()
        return START_STICKY
    }

    /** Loop: reconcile real status; stop ourselves when nothing needs us. */
    private fun watch(): Job = scope.launch {
        while (isActive) {
            delay(12_000)
            try {
                withContext(Dispatchers.IO) {
                    // status refreshes .running/.playit from tmux reality
                    if (Embed.isBootstrapped(this@KeepAliveService)) {
                        Embed.runManager(this@KeepAliveService, "status", emptyList(), null)
                    }
                }
                if (!keepAliveWanted()) {
                    stopSelf()
                    break
                }
            } catch (_: Exception) { }
        }
    }

    private fun keepAliveWanted(): Boolean = try {
        val st = File(Embed.sharedDir(this), "state.json")
        if (!st.exists()) false
        else {
            val o = JSONObject(st.readText())
            o.optBoolean("running") || (o.optJSONObject("playit")?.optBoolean("running") == true)
        }
    } catch (_: Exception) { false }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        try { wake?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MCPanel")
            .setContentText("Servidor encendido. Toca para abrir.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL = "keepalive"
        const val NOTIF_ID = 11

        fun want(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) { }
        }

        fun cancel(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) { }
        }
    }
}
