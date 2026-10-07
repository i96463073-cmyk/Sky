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
import com.rementia.openwakeword.lib.WakeWordModel
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
        private const val NOTIFICATION_ID = 42
        const val ACTION_WAKE_DETECTED = "com.sky.agent.WAKE_DETECTED"
        const val EXTRA_WAKE_WORD = "wake_word"
        private const val TAG = "SkyWakeService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListening() {
        try {
            // Test model: "hey jarvis". We'll swap for a custom "Sky" model later.
            val models = listOf(
                WakeWordModel(
                    name = "Sky",
                    modelPath = "hey_jarvis.onnx",
                    threshold = 0.5f
                )
            )

            engine = WakeWordEngine(
                context = applicationContext,
                models = models
            )
            engine?.start()

            scope.launch {
                engine?.detections?.collect { detection ->
                    Log.d(TAG, "Wake word detected: ${detection.model.name} score=${detection.score}")
                    onWakeWordDetected()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word engine", e)
        }
    }

    private fun onWakeWordDetected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(ACTION_WAKE_DETECTED, true)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sky Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listens for the wake word \"Sky\""
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sky is listening")
            .setContentText("Say \"Sky\" to wake it up")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
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
