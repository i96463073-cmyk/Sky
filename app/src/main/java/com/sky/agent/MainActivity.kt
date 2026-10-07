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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var agentSwitch: SwitchMaterial
    private lateinit var wakeSwitch: SwitchMaterial
    private lateinit var statusView: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    companion object {
        private const val REQUEST_SPEECH = 1001
        private const val REQUEST_MIC_PERMISSION = 2001
        private const val REQUEST_NOTIF_PERMISSION = 2002
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
            processCommand(command)
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

    // --------------------------------
    // WAKE SERVICE
    // --------------------------------
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
                this,
                requests.toTypedArray(),
                REQUEST_MIC_PERMISSION
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
            if (micOk) {
                startWakeService()
            } else {
                wakeSwitch.isChecked = false
                updateStatus("Microphone permission is required for the wake word.")
            }
        }
    }

    // --------------------------------
    // VOICE INPUT
    // --------------------------------
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
                this,
                "Voice input is not available on this device",
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
                processCommand(spoken)
            }
        }
    }

    // --------------------------------
    // STATUS + SPEAK
    // --------------------------------
    private fun updateStatus(message: String) {
        statusView.text = message
        if (ttsReady) {
            try {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
            } catch (_: Exception) {}
        }
    }

    // --------------------------------
    // HELPERS
    // --------------------------------
    private fun normalize(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun hasAny(text: String, vararg phrases: String): Boolean {
        return phrases.any { text.contains(it) }
    }

    // --------------------------------
    // INSTALLED APPS INFO
    // --------------------------------
    private fun getUserInstalledApps(): List<Pair<String, String>> {
        val pm = packageManager
        val flags = android.content.pm.PackageManager.GET_META_DATA
        val packages = pm.getInstalledPackages(flags)
        val result = mutableListOf<Pair<String, String>>()
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            if (pkg.packageName == packageName) continue
            val label = pm.getApplicationLabel(appInfo).toString()
            result.add(label to pkg.packageName)
        }
        return result.sortedBy { it.first.lowercase() }
    }

    private fun answerAppCount() {
        try {
            val apps = getUserInstalledApps()
            updateStatus("You have ${apps.size} installed apps.")
        } catch (e: Exception) {
            updateStatus("Could not read installed apps.")
        }
    }

    private fun answerAppList() {
        try {
            val apps = getUserInstalledApps()
            if (apps.isEmpty()) {
                updateStatus("I could not find any installed apps.")
                return
            }
            val names = apps.joinToString(", ") { it.first }
            updateStatus("You have ${apps.size} apps: $names")
        } catch (e: Exception) {
            updateStatus("Could not read installed apps.")
        }
    }

    // --------------------------------
    // COMMAND ROUTER
    // --------------------------------
    private fun processCommand(command: String) {

        val text = normalize(command)

        val wantsOpen = hasAny(
            text,
            "open", "launch", "start", "run", "show", "go to"
        )

        when {

            text.contains("how many apps") ||
            text.contains("number of apps") ||
            text.contains("count my apps") ||
            text.contains("how many applications") -> {
                answerAppCount()
            }

            text.contains("list my apps") ||
            text.contains("list apps") ||
            text.contains("what apps do i have") ||
            text.contains("which apps do i have") ||
            text.contains("show my apps") ||
            text.contains("my installed apps") -> {
                answerAppList()
            }

            hasAny(text, "test backend", "test sky", "check backend", "check sky") -> {
                testSkyBackend()
            }

            text == "help" ||
            text.contains("what can you do") ||
            text.contains("what can i say") -> {
                updateStatus(
                    "You can say: open whatsapp, open chrome, open settings, " +
                    "go back, go home, read the screen, tap a word, type text, " +
                    "how many apps do I have, list my apps, test backend, or stop. " +
                    "You can also just talk to me normally."
                )
            }

            text.contains("accessibility") -> {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    updateStatus("Opening accessibility settings.")
                } catch (e: Exception) {
                    updateStatus("Could not open accessibility settings.")
                }
            }

            wantsOpen && text.contains("whatsapp") -> {
                openApp("com.whatsapp", "WhatsApp")
            }
            wantsOpen && (text.contains("chrome") || text.contains("browser")) -> {
                openApp("com.android.chrome", "Chrome")
            }
            wantsOpen && text.contains("telegram") -> {
                openApp("org.telegram.messenger", "Telegram")
            }
            wantsOpen && text.contains("youtube") -> {
                openApp("com.google.android.youtube", "YouTube")
            }
            wantsOpen && text.contains("camera") -> {
                openApp("com.sec.android.app.camera", "Camera")
            }
            wantsOpen && text.contains("instagram") -> {
                openApp("com.instagram.android", "Instagram")
            }
            wantsOpen && text.contains("facebook") -> {
                openApp("com.facebook.katana", "Facebook")
            }
            wantsOpen && text.contains("spotify") -> {
                openApp("com.spotify.music", "Spotify")
            }
            wantsOpen && text.contains("gmail") -> {
                openApp("com.google.android.gm", "Gmail")
            }
            wantsOpen && text.contains("setting") -> {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    updateStatus("Opening settings.")
                } catch (e: Exception) {
                    updateStatus("Could not open settings.")
                }
            }

            text == "go back" || text == "back" ||
            text == "press back" || text.contains("go back") -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    service.goBack()
                    updateStatus("Going back.")
                } else {
                    updateStatus("Accessibility service is off.")
                }
            }

            text == "go home" || text == "home" ||
            text == "press home" || text.contains("go home") ||
            text.contains("home screen") -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    service.goHome()
                    updateStatus("Going home.")
                } else {
                    updateStatus("Accessibility service is off.")
                }
            }

            text.contains("read screen") ||
            text.contains("read the screen") ||
            text.contains("what is on screen") ||
            text.contains("what's on screen") -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    val screenText = service.readScreen()
                    if (screenText.isBlank()) {
                        updateStatus("No readable text on screen.")
                    } else {
                        updateStatus("The screen says: $screenText")
                    }
                } else {
                    updateStatus("Accessibility service is off.")
                }
            }

            text.startsWith("tap ") || text.startsWith("click ") ||
            text.contains(" tap ") -> {
                val target = text
                    .replaceFirst("tap ", "")
                    .replaceFirst("click ", "")
                    .substringAfter(" tap ", "")
                    .trim()
                if (target.isEmpty()) {
                    updateStatus("Tell me what to tap.")
                } else {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        val success = service.tapText(target)
                        updateStatus(
                            if (success) "Tapped $target."
                            else "Could not find $target on screen."
                        )
                    } else {
                        updateStatus("Accessibility service is off.")
                    }
                }
            }

            text.startsWith("type ") || text.contains(" type ") -> {
                val value = text
                    .replaceFirst("type ", "")
                    .substringAfter(" type ", "")
                    .trim()
                if (value.isEmpty()) {
                    updateStatus("Tell me what to type.")
                } else {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        val success = service.typeText(value)
                        updateStatus(
                            if (success) "Typed it."
                            else "Could not type."
                        )
                    } else {
                        updateStatus("Accessibility service is off.")
                    }
                }
            }

            text == "stop" || text == "stop sky" ||
            text == "emergency stop" || text.contains("stop sky") -> {
                agentSwitch.isChecked = false
                wakeSwitch.isChecked = false
                stopWakeService()
                try { tts?.stop() } catch (_: Exception) {}
                updateStatus("Emergency stop. Sky is disabled.")
            }

            else -> {
                chatWithBackend(command)
            }
        }
    }

    // --------------------------------
    // AI CHAT VIA BACKEND
    // --------------------------------
    private fun chatWithBackend(message: String) {
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
                    "Content-Type",
                    "application/json; charset=utf-8"
                )

                val body = JSONObject().put("message", message).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                val stream =
                    if (responseCode in 200..299) conn.inputStream
                    else conn.errorStream
                val responseText =
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()

                runOnUiThread {
                    if (responseCode in 200..299) {
                        try {
                            val obj = JSONObject(responseText)
                            val reply = obj.optString("reply", "").trim()
                            if (reply.isEmpty()) {
                                updateStatus("No reply from Sky.")
                            } else {
                                updateStatus(reply)
                            }
                        } catch (e: Exception) {
                            updateStatus("Bad reply from backend.")
                        }
                    } else {
                        val detail = try {
                            JSONObject(responseText)
                                .optString("error", "HTTP $responseCode")
                        } catch (_: Exception) {
                            "HTTP $responseCode"
                        }
                        updateStatus("Backend error: $detail")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus("Network error: ${e.message}")
                }
            }
        }
    }

    // --------------------------------
    // BACKEND HEALTH TEST
    // --------------------------------
    private fun testSkyBackend() {
        updateStatus("Connecting to backend.")
        thread {
            try {
                val url = URL("$BACKEND_BASE/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val responseCode = connection.responseCode
                val response =
                    connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                runOnUiThread {
                    if (responseCode == 200) {
                        updateStatus("Backend connected. $response")
                    } else {
                        updateStatus("Backend error $responseCode.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus("Connection failed. ${e.message}")
                }
            }
        }
    }

    // --------------------------------
    // OPEN APP
    // --------------------------------
    private fun openApp(packageName: String, appName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                updateStatus("Opening $appName.")
                return
            }

            val schemeUrl = when (packageName) {
                "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp://send"
                "com.android.chrome" -> "https://www.google.com"
                "org.telegram.messenger" -> "tg://resolve"
                "com.instagram.android" -> "instagram://app"
                "com.facebook.katana" -> "fb://feed"
                "com.spotify.music" -> "spotify://"
                "com.google.android.youtube" -> "vnd.youtube://"
                else -> null
            }

            if (schemeUrl != null) {
                try {
                    val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUrl))
                    schemeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(schemeIntent)
                    updateStatus("Opening $appName.")
                    return
                } catch (_: Exception) {
                }
            }

            updateStatus("$appName is not installed.")

        } catch (e: Exception) {
            updateStatus("Could not open $appName.")
        }
    }
}
