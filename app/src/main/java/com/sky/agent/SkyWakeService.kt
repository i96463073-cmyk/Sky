package com.sky.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SkyWakeService : Service() {

    private var engine: WakeWordEngine? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val CHANNEL_ID = "sky_wake_channel"
        private const val CHANNEL_ID_ALERT = "sky_alert_channel"
        private const val NOTIFICATION_ID = 42
        private const val ALERT_NOTIFICATION_ID = 43
        const val ACTION_WAKE_DETECTED = "com.sky.agent.WAKE_DETECTED"
        private const val TAG = "SkyWakeService"
        private var lastTriggerMs = 0L
        private const val COOLDOWN_MS = 2500L
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, buildListenerNotification())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListening() {
        try {
            // Use the Alexa model — far better for non-US accents.
            val models = listOf(
                WakeWordModel(
                    name = "alexa",
                    modelPath = "alexa.onnx",
                    threshold = 0.4f
                )
            )

            val newEngine = WakeWordEngine(
                context = applicationContext,
                models = models
            )
            newEngine.start()
            engine = newEngine

            Log.d(TAG, "Wake word engine started (Alexa)")

            scope.launch {
                try {
                    newEngine.detections.collect { detection ->
                        Log.d(
                            TAG,
                            "Wake detected: ${detection.model.name} score=${detection.score}"
                        )
                        onWakeWordDetected()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Detection collect failed", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word engine", e)
        }
    }

    private fun onWakeWordDetected() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < COOLDOWN_MS) return
        lastTriggerMs = now

        Log.d(TAG, "Wake word triggered — opening MainActivity")

        val wakeIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(ACTION_WAKE_DETECTED, true)
        }

        val pi = PendingIntent.getActivity(
            this,
            1,
            wakeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // High-priority notification with full-screen intent
        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle("Sky is listening")
            .setContentText("Speak your command now")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(ALERT_NOTIFICATION_ID, alertNotification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post alert notification", e)
        }

        // Also try to launch directly (works when app in foreground)
        try {
            startActivity(wakeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Direct startActivity blocked (expected in background)", e)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val listenerChannel = NotificationChannel(
                CHANNEL_ID,
                "Sky Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sky is listening for the wake word"
            }
            nm.createNotificationChannel(listenerChannel)

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "Sky Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sky wake word detected"
                setShowBadge(true)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun buildListenerNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sky is listening")
            .setContentText("Say \"Alexa\" to wake me")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        try {
            engine?.stop()
            engine?.release()
        } catch (_: Exception) {}
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
