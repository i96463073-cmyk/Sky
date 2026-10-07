package com.sky.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
    private val maxActionsPerHour = 20

    companion object {
        private const val REQUEST_SPEECH = 1001
        private const val REQUEST_MIC_PERMISSION = 2001
        private const val BACKEND_BASE =
            "https://sky-ai-backend.i96463073.workers.dev"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                try {
                    val result = tts?.setLanguage(Locale.getDefault())
                    ttsReady =
                        result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
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
            try { tts?.stop() } catch (_: Exception) {}
            updateStatus("Emergency stop. Sky is disabled.")
        }

        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) requestPermissionsThenStartWake() else stopWakeService()
        }

        tradeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false
                updateStatus("MT5 execution is locked for now.")
            }
        }

        updateStatus("Sky is ready.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(SkyWakeService.ACTION_WAKE_DETECTED, false)) {
            updateStatus("Wake word heard. Listening...")
            startVoiceInput()
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }

    // ================================
    // WAKE SERVICE
    // ================================
    private fun requestPermissionsThenStartWake() {
        val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        val needMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED

        val requests = mutableListOf<String>()
        if (needMic) requests.add(Manifest.permission.RECORD_AUDIO)
        if (needNotif) requests.add(Manifest.permission.POST_NOTIFICATIONS)

        if (requests.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, requests.toTypedArray(), REQUEST_MIC_PERMISSION
            )
        } else {
            startWakeService()
        }
    }

    private fun startWakeService() {
        val serviceIntent = Intent(this, SkyWakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateStatus("Wake word is on. Say \"Hey Jarvis\" to wake me.")
    }

    private fun stopWakeService() {
        try {
            stopService(Intent(this, SkyWakeService::class.java))
        } catch (_: Exception) {}
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
            if (micOk) startWakeService() else {
                wakeSwitch.isChecked = false
                updateStatus("Microphone permission is required for the wake word.")
            }
        }
    }

    // ================================
    // VOICE INPUT
    // ================================
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(
                this, "Voice input is not available",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH && resultCode == Activity.RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                val commandInput = findViewById<EditText>(R.id.commandInput)
                commandInput.setText(spoken)
                dispatch(spoken)
            }
        }
    }

    // ================================
    // DISPATCH (local emergency first)
    // ================================
    private fun dispatch(command: String) {
        val text = command.lowercase().trim()
        // Emergency stop handled locally — instant, no network
        if (text == "stop" || text == "stop sky" || text == "emergency stop") {
            agentSwitch.isChecked = false
            wakeSwitch.isChecked = false
            stopWakeService()
            try { tts?.stop() } catch (_: Exception) {}
            updateStatus("Emergency stop. Sky is disabled.")
            return
        }
        sendToAI(command)
    }

    // ================================
    // STATUS + SPEAK
    // ================================
    private fun updateStatus(message: String) {
        statusView.text = message
        if (ttsReady) {
            try {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
            } catch (_: Exception) {}
        }
    }

    // ================================
    // AI CALL
    // ================================
    private fun sendToAI(message: String) {
        updateStatus("Thinking...")
        thread {
            try {
                val url = URL("$BACKEND_BASE/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                conn.doOutput = true
                conn.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8"
                )
                val body = JSONObject().put("message", message).toString()
                conn.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream
                             else conn.errorStream
                val responseText =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()

                runOnUiThread {
                    if (responseCode !in 200..299) {
                        val detail = try {
                            JSONObject(responseText)
                                .optString("error", "HTTP $responseCode")
                        } catch (_: Exception) { "HTTP $responseCode" }
                        updateStatus("Backend error: $detail")
                        return@runOnUiThread
                    }

                    try {
                        val obj = JSONObject(responseText)
                        val reply = obj.optString("reply", "").trim()
                        val action = if (obj.isNull("action")) null
                                     else obj.optJSONObject("action")

                        if (reply.isNotEmpty()) updateStatus(reply)
                        else updateStatus("Okay.")

                        if (action != null) {
                            executeAction(action)
                        }
                    } catch (e: Exception) {
                        updateStatus("Bad reply from backend.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Network error: ${e.message}") }
            }
        }
    }

    // ================================
    // ACTION EXECUTOR
    // ================================
    private fun executeAction(action: JSONObject) {
        val type = action.optString("type", "").trim()
        if (type.isEmpty()) return

        // Rate limit
        val now = System.currentTimeMillis()
        actionTimestamps.removeAll { now - it > 60L * 60L * 1000L }
        if (actionTimestamps.size >= maxActionsPerHour) {
            updateStatus("Action limit reached for this hour. Try again later.")
            return
        }
        actionTimestamps.add(now)

        // Log
        logAction(type, action.toString())

        try {
            when (type) {

                "OPEN_APP" -> {
                    val query = action.optString("query", "").trim()
                    if (query.isEmpty()) {
                        updateStatus("No app specified.")
                        return
                    }
                    val pkg = resolvePackage(query)
                    if (pkg != null) openApp(pkg, query)
                    else openAppByQuery(query)
                }

                "SEARCH_WEB" -> {
                    val q = action.optString("query", "").trim()
                    if (q.isEmpty()) { updateStatus("Nothing to search."); return }
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" +
                            URLEncoder.encode(q, "UTF-8"))
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }

                "WHATSAPP_MESSAGE" -> {
                    val to = action.optString("to", "").trim()
                    val msg = action.optString("message", "").trim()
                    openWhatsAppMessage(to, msg)
                }

                "SEND_SMS" -> {
                    val to = action.optString("to", "").trim()
                    val msg = action.optString("message", "").trim()
                    openSmsMessage(to, msg)
                }

                "READ_SCREEN" -> {
                    val service = SkyAccessibilityService.instance
                    if (service == null) {
                        updateStatus("Accessibility service is off.")
                        return
                    }
                    val screenText = service.readScreen()
                    if (screenText.isBlank()) updateStatus("No readable text on screen.")
                    else updateStatus("The screen says: $screenText")
                }

                "GO_BACK" -> {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        service.goBack()
                        updateStatus("Going back.")
                    } else updateStatus("Accessibility service is off.")
                }

                "GO_HOME" -> {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        service.goHome()
                        updateStatus("Going home.")
                    } else updateStatus("Accessibility service is off.")
                }

                "TAP" -> {
                    val target = action.optString("target", "").trim()
                    if (target.isEmpty()) { updateStatus("Nothing to tap."); return }
                    val service = SkyAccessibilityService.instance
                    if (service == null) {
                        updateStatus("Accessibility service is off.")
                        return
                    }
                    val ok = service.tapText(target)
                    updateStatus(if (ok) "Tapped $target."
                                else "Could not find $target on screen.")
                }

                "TYPE" -> {
                    val text = action.optString("text", "").trim()
                    if (text.isEmpty()) { updateStatus("Nothing to type."); return }
                    val service = SkyAccessibilityService.instance
                    if (service == null) {
                        updateStatus("Accessibility service is off.")
                        return
                    }
                    val ok = service.typeText(text)
                    updateStatus(if (ok) "Typed it." else "Could not type.")
                }

                else -> updateStatus("Unknown action: $type")
            }
        } catch (e: Exception) {
            updateStatus("Action failed: ${e.message}")
        }
    }

    // ================================
    // APP RESOLUTION
    // ================================
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
        val key = friendly.lowercase().trim()
        map[key]?.let { return it }
        for ((name, pkg) in map) if (key.contains(name)) return pkg
        return null
    }

    private fun openAppByQuery(query: String) {
        // Try to find an installed app matching the query by label
        try {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0)
            val lower = query.lowercase()
            for (pkg in packages) {
                val info = pkg.applicationInfo ?: continue
                val label = pm.getApplicationLabel(info).toString().lowercase()
                if (label == lower || label.contains(lower)) {
                    val launch = pm.getLaunchIntentForPackage(pkg.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                        updateStatus("Opening $query.")
                        return
                    }
                }
            }
            updateStatus("Could not find $query.")
        } catch (e: Exception) {
            updateStatus("Could not open $query.")
        }
    }

    private fun openApp(packageName: String, appName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                updateStatus("Opening $appName.")
                return
            }
            updateStatus("$appName is not installed.")
        } catch (e: Exception) {
            updateStatus("Could not open $appName.")
        }
    }

    // ================================
    // WHATSAPP / SMS DRAFTS
    // ================================
    private fun openWhatsAppMessage(to: String, message: String) {
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val normalizedTo = to.replace(Regex("[^0-9+]"), "")
        val url = if (normalizedTo.isNotEmpty() && normalizedTo.length >= 7) {
            "https://wa.me/$normalizedTo?text=$encodedMsg"
        } else {
            "https://wa.me/?text=$encodedMsg"
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            updateStatus("WhatsApp ready. Tap Send to deliver.")
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(
                    "whatsapp://send?text=$encodedMsg"
                ))
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
                updateStatus("WhatsApp ready. Choose recipient and tap Send.")
            } catch (e2: Exception) {
                updateStatus("WhatsApp is not installed.")
            }
        }
    }

    private fun openSmsMessage(to: String, message: String) {
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("smsto:$to?body=$encodedMsg")
        try {
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            updateStatus("SMS ready. Tap Send to deliver.")
        } catch (e: Exception) {
            updateStatus("Could not open SMS app.")
        }
    }

    // ================================
    // ACTION LOG
    // ================================
    private fun logAction(type: String, detail: String) {
        try {
            val file = File(filesDir, "sky_action_log.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$ts] $type — $detail\n")
        } catch (_: Exception) {}
    }
}
