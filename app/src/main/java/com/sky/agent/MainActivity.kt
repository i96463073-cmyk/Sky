package com.sky.agent

import android.app.Activity
import android.content.Intent
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
                val status = findViewById<TextView>(R.id.status)
                commandInput.setText(spoken)
                processCommand(spoken, status)
            }
        }
    }

    // --------------------------------
    // STATUS + SPEAK
    // --------------------------------
    private fun updateStatus(status: TextView, message: String) {
        status.text = message
        if (ttsReady) {
            try {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
            } catch (_: Exception) {}
        }
    }

    // --------------------------------
    // NORMALIZE + MATCH HELPERS
    // --------------------------------
    private fun normalize(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun hasWord(text: String, word: String): Boolean {
        return text.split(" ").any { it == word }
    }

    private fun hasAny(text: String, vararg phrases: String): Boolean {
        return phrases.any { text.contains(it) }
    }

    // --------------------------------
    // COMMAND ROUTER
    // --------------------------------
    private fun processCommand(command: String, status: TextView) {

        val text = normalize(command)

        // A "want open" verb used for app-launch commands
        val wantsOpen = hasAny(
            text,
            "open", "launch", "start", "run", "show", "go to"
        )

        when {

            // ---------- Greetings (canned, until AI is live) ----------
            text == "hello" || text == "hi" || text == "hey" ||
            text == "hello sky" || text == "hi sky" || text == "hey sky" -> {
                updateStatus(status, "Hello. I am Sky. Say help to see what I can do.")
            }

            text.contains("thank you") || text == "thanks" -> {
                updateStatus(status, "You are welcome.")
            }

            text.contains("who are you") || text.contains("what are you") -> {
                updateStatus(
                    status,
                    "I am Sky, your controlled phone assistant. " +
                    "I can open apps, read the screen, tap text, and type for you."
                )
            }

            // ---------- Backend ----------
            hasAny(text, "test backend", "test sky", "check backend", "check sky") -> {
                testSkyBackend(status)
            }

            // ---------- Help ----------
            text == "help" ||
            text.contains("what can you do") ||
            text.contains("what can i say") -> {
                updateStatus(
                    status,
                    "You can say: open whatsapp, open chrome, open settings, " +
                    "accessibility settings, go back, go home, read the screen, " +
                    "tap followed by a word on screen, type followed by text, " +
                    "test backend, or stop."
                )
            }

            // ---------- Accessibility settings ----------
            text.contains("accessibility") -> {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    updateStatus(status, "Opening accessibility settings.")
                } catch (e: Exception) {
                    updateStatus(status, "Could not open accessibility settings.")
                }
            }

            // ---------- WhatsApp ----------
            wantsOpen && text.contains("whatsapp") -> {
                openApp("com.whatsapp", "WhatsApp", status)
            }

            // ---------- Chrome ----------
            wantsOpen && (text.contains("chrome") || text.contains("browser")) -> {
                openApp("com.android.chrome", "Chrome", status)
            }

            // ---------- Telegram (bonus) ----------
            wantsOpen && text.contains("telegram") -> {
                openApp("org.telegram.messenger", "Telegram", status)
            }

            // ---------- Phone settings ----------
            wantsOpen && text.contains("setting") -> {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    updateStatus(status, "Opening settings.")
                } catch (e: Exception) {
                    updateStatus(status, "Could not open settings.")
                }
            }

            // ---------- Go back ----------
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

            // ---------- Go home ----------
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

            // ---------- Read screen ----------
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

            // ---------- Tap X ----------
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

            // ---------- Type X ----------
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

            // ---------- Stop ----------
            text == "stop" || text == "stop sky" ||
            text == "emergency stop" || text.contains("stop sky") -> {
                agentSwitch.isChecked = false
                try { tts?.stop() } catch (_: Exception) {}
                updateStatus(status, "Emergency stop. Sky is disabled.")
            }

            // ---------- Fallback ----------
            else -> {
                updateStatus(
                    status,
                    "I do not understand that yet. Say help to see my commands."
                )
                Toast.makeText(
                    this,
                    "Sky heard: $command",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // --------------------------------
    // BACKEND TEST
    // --------------------------------
    private fun testSkyBackend(status: TextView) {
        updateStatus(status, "Connecting to backend.")
        thread {
            try {
                val url = URL("https://sky-ai-backend.i96463073.workers.dev/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val responseCode = connection.responseCode
                val response = connection.inputStream.bufferedReader().use { it.readText() }
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

    // --------------------------------
    // OPEN APP
    // --------------------------------
    private fun openApp(packageName: String, appName: String, status: TextView) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
                updateStatus(status, "Opening $appName.")
            } else {
                updateStatus(status, "$appName is not installed.")
            }
        } catch (e: Exception) {
            updateStatus(status, "Could not open $appName.")
        }
    }
}
