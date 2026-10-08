package com.sky.agent

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skyai.app.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var agentSwitch: SwitchMaterial
    private lateinit var wakeSwitch: SwitchMaterial
    private lateinit var statusView: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val actionTimestamps = mutableListOf<Long>()
    private val maxActionsPerHour = 200

    companion object {
        private const val REQUEST_SPEECH = 1001
        private const val REQUEST_MIC_PERMISSION = 2001
        private const val BACKEND_BASE = "https://sky-ai-backend.i96463073.workers.dev"
        private const val TAP_CHANNEL_ID = "sky_taps"
        private const val TAP_NOTIF_ID = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createTapChannel()

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                try {
                    val result = tts?.setLanguage(Locale.getDefault())
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        ttsReady = false
                    } else {
                        ttsReady = true
                    }
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.0f)
                } catch (e: Exception) {
                    ttsReady = false
                }
            }
        }

        val commandInput = findViewById<EditText>(R.id.commandInput)
        statusView = findViewById(R.id.status)
        agentSwitch = findViewById(R.id.aiSwitch)
        wakeSwitch = findViewById(R.id.wakeSwitch)
        val tradeSwitch = findViewById<SwitchMaterial>(R.id.tradeSwitch)

        val runButton = findViewById<Button>(R.id.runButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val micButton = findViewById<Button>(R.id.micButton)

        runButton.setOnClickListener {
            val command = commandInput.text.toString().trim()
            if (!agentSwitch.isChecked) {
                updateStatus("Sky is off")
                return@setOnClickListener
            }
            if (command.isEmpty()) {
                commandInput.error = "Enter a command"
                return@setOnClickListener
            }
            dispatch(command)
        }

        micButton.setOnClickListener {
            if (!agentSwitch.isChecked) {
                updateStatus("Sky is off")
                return@setOnClickListener
            }
            startVoiceInput()
        }

        stopButton.setOnClickListener {
            agentSwitch.isChecked = false
            wakeSwitch.isChecked = false
            stopWakeService()
            try {
                tts?.stop()
            } catch (e: Exception) {
            }
            updateStatus("Emergency stop. Sky is disabled.")
        }

        wakeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                requestPermissionsThenStartWake()
            } else {
                stopWakeService()
            }
        }

        tradeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false
                updateStatus("MT5 execution is locked for now.")
            }
        }

        updateStatus("Sky is ready.")
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent == null) {
            return
        }
        if (intent.getBooleanExtra(SkyWakeService.ACTION_WAKE_DETECTED, false)) {
            intent.removeExtra(SkyWakeService.ACTION_WAKE_DETECTED)
            updateStatus("Wake word heard. Listening...")
            Handler(Looper.getMainLooper()).postDelayed({
                startVoiceInput()
            }, 600)
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        super.onDestroy()
    }

    private fun requestPermissionsThenStartWake() {
        val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        val needMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED

        val reqs = mutableListOf<String>()
        if (needMic) {
            reqs.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needNotif) {
            reqs.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (reqs.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, reqs.toTypedArray(), REQUEST_MIC_PERMISSION
            )
        } else {
            startWakeService()
        }
    }

    private fun startWakeService() {
        val i = Intent(this, SkyWakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        updateStatus("Wake word is on. Say \"Hey Jarvis\" to wake me.")
    }

    private fun stopWakeService() {
        try {
            stopService(Intent(this, SkyWakeService::class.java))
        } catch (e: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION) {
            val micOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micOk) {
                startWakeService()
            } else {
                wakeSwitch.isChecked = false
                updateStatus("Microphone permission required for wake word.")
            }
        }
    }

    private fun startVoiceInput() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQUEST_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(
                this, "Voice input not available", Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH && resultCode == Activity.RESULT_OK) {
            val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = r?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                findViewById<EditText>(R.id.commandInput).setText(spoken)
                dispatch(spoken)
            }
        }
    }

    private fun dispatch(command: String) {
        val t = command.lowercase().trim()
        if (t == "stop" || t == "stop sky" || t == "emergency stop") {
            agentSwitch.isChecked = false
            wakeSwitch.isChecked = false
            stopWakeService()
            try {
                tts?.stop()
            } catch (e: Exception) {
            }
            updateStatus("Emergency stop. Sky is disabled.")
            return
        }
        sendToAI(command)
    }

    private fun updateStatus(msg: String) {
        statusView.text = msg
        if (ttsReady) {
            try {
                tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
            } catch (e: Exception) {
            }
        }
    }

    private fun sendToAI(message: String) {
        updateStatus("Thinking...")
        thread {
            try {
                val url = URL("$BACKEND_BASE/chat")
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val body = JSONObject().put("message", message).toString()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val txt = s?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()

                runOnUiThread {
                    if (code !in 200..299) {
                        updateStatus("Backend error: " + code)
                        return@runOnUiThread
                    }
                    try {
                        val obj = JSONObject(txt)
                        val reply = obj.optString("reply", "").trim()

                        val actionList = mutableListOf<JSONObject>()

                        if (!obj.isNull("actions")) {
                            val arr = obj.optJSONArray("actions")
                            if (arr != null) {
                                var i = 0
                                while (i < arr.length()) {
                                    val a = arr.optJSONObject(i)
                                    if (a != null) {
                                        actionList.add(a)
                                    }
                                    i++
                                }
                            }
                        }
                        if (actionList.isEmpty() && !obj.isNull("action")) {
                            val single = obj.optJSONObject("action")
                            if (single != null) {
                                actionList.add(single)
                            }
                        }

                        if (reply.isNotEmpty()) {
                            updateStatus(reply)
                        }

                        if (actionList.isNotEmpty()) {
                            executeActionsSequentially(actionList, 0)
                        }
                    } catch (e: Exception) {
                        updateStatus("Bad reply from backend.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus("Network error: " + e.message)
                }
            }
        }
    }

    private fun executeActionsSequentially(actions: List<JSONObject>, index: Int) {
        if (index >= actions.size) {
            return
        }

        val action = actions[index]
        val type = action.optString("type", "").trim()
        if (type.isEmpty()) {
            executeActionsSequentially(actions, index + 1)
            return
        }

        val now = System.currentTimeMillis()
        actionTimestamps.removeAll { now - it > 3600_000L }
        if (actionTimestamps.size >= maxActionsPerHour) {
            updateStatus("Action limit reached for this hour.")
            return
        }
        actionTimestamps.add(now)
        logAction(type, action.toString())

        var delayMs = 500L
        try {
            delayMs = runAction(action)
        } catch (e: Exception) {
            updateStatus("Action failed: " + e.message)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            executeActionsSequentially(actions, index + 1)
        }, delayMs)
    }

    private fun runAction(action: JSONObject): Long {
        val type = action.optString("type", "")

        if (type == "OPEN_APP") {
            val q = action.optString("query", "").trim()
            if (q.isEmpty()) {
                updateStatus("No app specified.")
                return 300L
            }
            notifyTap("Opening " + q)
            val pkg = resolvePackage(q)
            if (pkg != null) {
                openApp(pkg, q)
            } else {
                openAppByQuery(q)
            }
            return 2500L
        }

        if (type == "SEARCH_WEB") {
            val q = action.optString("query", "").trim()
            if (q.isEmpty()) {
                updateStatus("Nothing to search.")
                return 300L
            }
            notifyTap("Searching: " + q)
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/search?q=" +
                    URLEncoder.encode(q, "UTF-8")
                )
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return 2200L
        }

        if (type == "WHATSAPP_MESSAGE") {
            notifyTap("WhatsApp draft")
            openWhatsAppMessage(
                action.optString("to", "").trim(),
                action.optString("message", "").trim()
            )
            return 2000L
        }

        if (type == "SEND_SMS") {
            notifyTap("SMS draft")
            openSmsMessage(
                action.optString("to", "").trim(),
                action.optString("message", "").trim()
            )
            return 2000L
        }

        if (type == "READ_SCREEN") {
            val svc = SkyAccessibilityService.instance
            if (svc == null) {
                updateStatus("Accessibility service is off.")
                return 300L
            }
            val text = svc.readScreen()
            if (text.isBlank()) {
                updateStatus("No readable text on screen.")
            } else {
                updateStatus("The screen says: " + text)
            }
            return 1000L
        }

        if (type == "GO_BACK") {
            notifyTap("Back")
            SkyAccessibilityService.instance?.goBack()
            updateStatus("Going back.")
            return 1200L
        }

        if (type == "GO_HOME") {
            notifyTap("Home")
            SkyAccessibilityService.instance?.goHome()
            updateStatus("Going home.")
            return 1200L
        }

        if (type == "TAP") {
            val target = action.optString("target", "").trim()
            val svc = SkyAccessibilityService.instance
            if (svc == null) {
                updateStatus("Accessibility service is off.")
                return 300L
            }
            if (target.isEmpty()) {
                updateStatus("Nothing to tap.")
                return 300L
            }
            notifyTap("Tap: " + target)
            val ok = svc.tapText(target)
            if (ok) {
                updateStatus("Tapped " + target + ".")
            } else {
                updateStatus("Could not find " + target + ".")
            }
            return 2000L
        }

        if (type == "TYPE") {
            val txt = action.optString("text", "").trim()
            val svc = SkyAccessibilityService.instance
            if (svc == null) {
                updateStatus("Accessibility service is off.")
                return 300L
            }
            if (txt.isEmpty()) {
                updateStatus("Nothing to type.")
                return 300L
            }
            notifyTap("Type: " + txt.take(40))
            val ok = svc.typeText(txt)
            if (ok) {
                updateStatus("Typed.")
            } else {
                updateStatus("Could not type.")
            }
            return 1200L
        }

        if (type == "SCROLL_DOWN") {
            notifyTap("Scroll down")
            SkyAccessibilityService.instance?.scrollDown()
            updateStatus("Scrolling down.")
            return 1200L
        }

        if (type == "SCROLL_UP") {
            notifyTap("Scroll up")
            SkyAccessibilityService.instance?.scrollUp()
            updateStatus("Scrolling up.")
            return 1200L
        }

        if (type == "WAIT") {
            val ms = action.optLong("ms", 1500L)
            updateStatus("Waiting...")
            return ms
        }

        updateStatus("Unknown action: " + type)
        return 300L
    }

    private fun resolvePackage(friendly: String): String? {
        val map = mapOf(
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "settings" to "com.android.settings",
            "telegram" to "org.telegram.messenger",
            "youtube" to "com.google.android.youtube",
            "camera" to "com.sec.android.app.camera",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm"
        )
        val k = friendly.lowercase().trim()
        val direct = map[k]
        if (direct != null) {
            return direct
        }
        for ((name, pkg) in map) {
            if (k.contains(name)) {
                return pkg
            }
        }
        return null
    }

    private fun openAppByQuery(query: String) {
        try {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0)
            val lower = query.lowercase()
            for (pkg in packages) {
                val info = pkg.applicationInfo ?: continue
                val label = pm.getApplicationLabel(info).toString().lowercase()
                if (label == lower || label.contains(lower)) {
                    val launch = pm.getLaunchIntentForPackage(pkg.packageName) ?: continue
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    updateStatus("Opening " + query + ".")
                    return
                }
            }
            updateStatus("Could not find " + query + ".")
        } catch (e: Exception) {
            updateStatus("Could not open " + query + ".")
        }
    }

    private fun openApp(packageName: String, appName: String) {
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                updateStatus("Opening " + appName + ".")
            } else {
                updateStatus(appName + " is not installed.")
            }
        } catch (e: Exception) {
            updateStatus("Could not open " + appName + ".")
        }
    }

    private fun openWhatsAppMessage(to: String, message: String) {
        val msg = URLEncoder.encode(message, "UTF-8")
        val num = to.replace(Regex("[^0-9+]"), "")
        val url: String
        if (num.isNotEmpty() && num.length >= 7) {
            url = "https://wa.me/$num?text=$msg"
        } else {
            url = "https://wa.me/?text=$msg"
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            updateStatus("WhatsApp ready. Tap Send.")
        } catch (e: Exception) {
            try {
                val f = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("whatsapp://send?text=$msg")
                )
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(f)
                updateStatus("WhatsApp ready.")
            } catch (e2: Exception) {
                updateStatus("WhatsApp is not installed.")
            }
        }
    }

    private fun openSmsMessage(to: String, message: String) {
        val msg = URLEncoder.encode(message, "UTF-8")
        try {
            val i = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:$to?body=$msg")
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            updateStatus("SMS ready. Tap Send.")
        } catch (e: Exception) {
            updateStatus("Could not open SMS.")
        }
    }

    // ---------- TAP NOTIFICATIONS ----------
    private fun createTapChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                TAP_CHANNEL_ID,
                "Sky Actions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows what Sky is doing"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun notifyTap(label: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, TAP_CHANNEL_ID)
                .setContentTitle("Sky: " + label)
                .setContentText("Tap STOP SKY NOW to halt.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(TAP_NOTIF_ID, notif)
        } catch (e: Exception) {
        }
    }

    private fun logAction(type: String, detail: String) {
        try {
            val f = File(filesDir, "sky_action_log.txt")
            val ts = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(Date())
            f.appendText("[$ts] $type — $detail\n")
        } catch (e: Exception) {
        }
    }
}
