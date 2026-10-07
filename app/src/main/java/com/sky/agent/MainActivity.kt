package com.sky.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skyai.app.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var agentSwitch: SwitchMaterial
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    companion object {
        private const val REQUEST_SPEECH = 1001
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
        val status = findViewById<TextView>(R.id.status)
        agentSwitch = findViewById(R.id.aiSwitch)
        val tradeSwitch = findViewById<SwitchMaterial>(R.id.tradeSwitch)

        val runButton = findViewById<Button>(R.id.runButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val micButton = findViewById<Button>(R.id.micButton)

        runButton.setOnClickListener {
            val command = commandInput.text.toString().trim()
            if (!agentSwitch.isChecked) {
                updateStatus(status, "Sky is off")
                return@setOnClickListener
            }
            if (command.isEmpty()) {
                commandInput.error = "Enter a command"
                return@setOnClickListener
            }
            processCommand(command, status)
        }

        micButton.setOnClickListener {
            if (!agentSwitch.isChecked) {
                updateStatus(status, "Sky is off")
                return@setOnClickListener
            }
            startVoiceInput()
        }

        stopButton.setOnClickListener {
            agentSwitch.isChecked = false
            try { tts?.stop() } catch (_: Exception) {}
            updateStatus(status, "Emergency stop. Sky is disabled.")
        }

        tradeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false
                updateStatus(status, "MT5 execution is locked for now.")
            }
        }

        updateStatus(status, "Sky is ready.")
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }

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
                val status = findViewById<TextView>(R.id.status)
                commandInput.setText(spoken)
                processCommand(spoken, status)
            }
        }
    }

    private fun updateStatus(status: TextView, message: String) {
        status.text = message
        if (ttsReady) {
            try {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
            } catch (_: Exception) {}
        }
    }

    private fun normalize(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun hasAny(text: String, vararg phrases: String): Boolean {
        return phrases.any { text.contains(it) }
    }

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

    private fun answerAppCount(status: TextView) {
        try {
            val apps = getUserInstalledApps()
            updateStatus(status, "You have ${apps.size} installed apps.")
        } catch (e: Exception) {
            updateStatus(status, "Could not read installed apps.")
        }
    }

    private fun answerAppList(status: TextView) {
        try {
            val apps = getUserInstalledApps()
            if (apps.isEmpty()) {
                updateStatus(status, "I could not find any installed apps.")
                return
            }
            val names = apps.joinToString(", ") { it.first }
            updateStatus(status, "You have ${apps.size} apps: $names")
        } catch (e: Exception) {
            updateStatus(status, "Could not read installed apps.")
        }
    }

    private fun processCommand(command: String, status: TextView) {

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
                answerAppCount(status)
            }

            text.contains("list my apps") ||
            text.contains("list apps") ||
            text.contains("what apps do i have") ||
            text.contains("which apps do i have") ||
            text.contains("show my apps") ||
            text.contains("my installed apps") -> {
                answerAppList(status)
            }

            hasAny(text, "test backend", "test sky", "check backend", "check sky") -> {
                testSkyBackend(status)
            }

            text == "help" ||
            text.contains("what can you do") ||
            text.contains("what can i say") -> {
                updateStatus(
                    status,
                    "You can say: open whatsapp, open chrome, open settings, " +
                    "go back, go home, read the screen, tap a word, type text, " +
                    "how many apps do I have, list my apps, test backend, or stop. " +
                    "You can also just talk to me normally."
                )
            }

            text.contains("accessibility") -> {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    updateStatus(status, "Opening accessibility settings.")
                } catch (e: Exception) {
                    updateStatus(status, "Could not open accessibility settings.")
                }
            }

            wantsOpen && text.contains("whatsapp") -> {
                openApp("com.whatsapp", "WhatsApp", status)
            }
            wantsOpen && (text.contains("chrome") || text.contains("browser")) -> {
                openApp("com.android.chrome", "Chrome", status)
            }
            wantsOpen && text.contains("telegram") -> {
                openApp("org.telegram.messenger", "Telegram", status)
            }
            wantsOpen && text.contains("youtube") -> {
                openApp("com.google.android.youtube", "YouTube", status)
            }
            wantsOpen && text.contains("camera") -> {
                openApp("com.sec.android.app.camera", "Camera", status)
            }
            wantsOpen && text.contains("instagram") -> {
                openApp("com.instagram.android", "Instagram", status)
            }
            wantsOpen && text.contains("facebook") -> {
                openApp("com.facebook.katana", "Facebook", status)
            }
            wantsOpen && text.contains("spotify") -> {
                openApp("com.spotify.music", "Spotify", status)
            }
            wantsOpen && text.contains("gmail") -> {
                openApp("com.google.android.gm", "Gmail", status)
            }
            wantsOpen && text.contains("setting") -> {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    updateStatus(status, "Opening settings.")
                } catch (e: Exception) {
                    updateStatus(status, "Could not open settings.")
                }
            }

            text == "go back" || text == "back" ||
            text == "press back" || text.contains("go back") -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    service.goBack()
                    updateStatus(status, "Going back.")
                } else {
                    updateStatus(status, "Accessibility service is off.")
                }
            }

            text == "go home" || text == "home" ||
            text == "press home" || text.contains("go home") ||
            text.contains("home screen") -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    service.goHome()
                    updateStatus(status, "Going home.")
                } else {
                    updateStatus(status, "Accessibility service is off.")
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
                        updateStatus(status, "No readable text on screen.")
                    } else {
                        updateStatus(status, "The screen says: $screenText")
                    }
                } else {
                    updateStatus(status, "Accessibility service is off.")
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
                    updateStatus(status, "Tell me what to tap.")
                } else {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        val success = service.tapText(target)
                        updateStatus(
                            status,
                            if (success) "Tapped $target."
                            else "Could not find $target on screen."
                        )
                    } else {
                        updateStatus(status, "Accessibility service is off.")
                    }
                }
            }

            text.startsWith("type ") || text.contains(" type ") -> {
                val value = text
                    .replaceFirst("type ", "")
                    .substringAfter(" type ", "")
                    .trim()
                if (value.isEmpty()) {
                    updateStatus(status, "Tell me what to type.")
                } else {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        val success = service.typeText(value)
                        updateStatus(
                            status,
                            if (success) "Typed it."
                            else "Could not type."
                        )
                    } else {
                        updateStatus(status, "Accessibility service is off.")
                    }
                }
            }

            text == "stop" || text == "stop sky" ||
            text == "emergency stop" || text.contains("stop sky") -> {
                agentSwitch.isChecked = false
                try { tts?.stop() } catch (_: Exception) {}
                updateStatus(status, "Emergency stop. Sky is disabled.")
            }

            else -> {
                chatWithBackend(command, status)
            }
        }
    }

    private fun chatWithBackend(message: String, status: TextView) {
        updateStatus(status, "Thinking...")
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
                                updateStatus(status, "No reply from Sky.")
                            } else {
                                updateStatus(status, reply)
                            }
                        } catch (e: Exception) {
                            updateStatus(status, "Bad reply from backend.")
                        }
                    } else {
                        val detail = try {
                            JSONObject(responseText)
                                .optString("error", "HTTP $responseCode")
                        } catch (_: Exception) {
                            "HTTP $responseCode"
                        }
                        updateStatus(status, "Backend error: $detail")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus(status, "Network error: ${e.message}")
                }
            }
        }
    }

    private fun testSkyBackend(status: TextView) {
        updateStatus(status, "Connecting to backend.")
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
                        updateStatus(status, "Backend connected. $response")
                    } else {
                        updateStatus(status, "Backend error $responseCode.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus(status, "Connection failed. ${e.message}")
                }
            }
        }
    }

    private fun openApp(packageName: String, appName: String, status: TextView) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                updateStatus(status, "Opening $appName.")
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
                    updateStatus(status, "Opening $appName.")
                    return
                } catch (_: Exception) {
                }
            }

            updateStatus(status, "$appName is not installed.")

        } catch (e: Exception) {
            updateStatus(status, "Could not open $appName.")
        }
    }
}
