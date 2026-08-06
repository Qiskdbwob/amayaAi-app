package com.amaya.intelligence.impl.ide.opencode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.amaya.intelligence.R

/**
 * Keeps an opencode chat session alive while the app is backgrounded. Mirrors
 * the Antigravity `RemoteSessionForegroundService` pattern so the WebSocket
 * that carries `agent.*` envelopes isn't killed when the user locks the screen.
 */
class OpencodeSessionForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        promoteToForeground("Opencode session", "Menjaga chat opencode tetap aktif")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Opencode session"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                promoteToForeground(title, text)
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Opencode session"
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Menjaga chat opencode tetap aktif"
                promoteToForeground(title, text)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun promoteToForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "startForeground (typed) failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    android.util.Log.w(TAG, "startForeground fallback failed: ${e2.message}")
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "startForeground failed: ${e.message}")
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Opencode Session",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Menjaga chat opencode tetap aktif di background."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "OpencodeFgService"
        private const val CHANNEL_ID = "opencode_session_channel"
        private const val NOTIFICATION_ID = 44022
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"

        private const val ACTION_STOP = "com.amaya.intelligence.opencode.STOP"
        private const val ACTION_UPDATE = "com.amaya.intelligence.opencode.UPDATE"

        @Volatile private var isRunning = false

        fun start(context: Context, title: String = "Opencode session", text: String = "Menjaga chat opencode tetap aktif") {
            val intent = Intent(context, OpencodeSessionForegroundService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun update(context: Context, title: String, text: String) {
            if (!isRunning) {
                start(context, title, text)
                return
            }
            val intent = Intent(context, OpencodeSessionForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to update foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpencodeSessionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to stop foreground service: ${e.message}")
            }
        }
    }
}
