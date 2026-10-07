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
    private var voiceEnabled = true

    companion object {
        private const val REQUEST_SPEECH = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- Text-to-Speech engine ----
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
                updateStatus(status, "Status: Sky is OFF")
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
                updateStatus(status, "Status: Sky is OFF")
                return@setOnClickListener
            }
            startVoiceInput()
        }

        stopButton.setOnClickListener {
            agentSwitch.isChecked = false
            try { tts?.stop() } catch (_: Exception) {}
            updateStatus(status, "Status: EMERGENCY STOP — Sky disabled")
        }

        // MT5 execution remains locked.
        tradeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false
                updateStatus(status, "Status: MT5 execution is locked for now")
            }
        }

        updateStatus(status, "Status: Ready")
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
        if (voiceEnabled && ttsReady) {
            val spoken = message
                .removePrefix("Status: ")
                .replace("\n", ". ")
            try {
                tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
            } catch (_: Exception) {}
        }
    }

    // --------------------------------
    // COMMAND ROUTER
    // --------------------------------
    private fun processCommand(command: String, status: TextView) {

        val text = command.lowercase().trim()

        when {

            text == "test backend" ||
            text == "test sky" ||
            text == "check backend" -> {
                testSkyBackend(status)
            }

            text.contains("open whatsapp") ||
            text.contains("launch whatsapp") -> {
                openApp("com.whatsapp", "WhatsApp", status)
            }

            text.contains("open chrome") ||
            text.contains("launch chrome") -> {
                openApp("com.android.chrome", "Chrome", status)
            }

            text.contains("open settings") ||
            text.contains("launch settings") -> {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    updateStatus(status, "Status: Opening Settings")
                } catch (e: Exception) {
                    updateStatus(status, "Status: Could not open Settings")
                }
            }

            text.contains("accessibility settings") -> {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    updateStatus(status, "Status: Opening Accessibility Settings")
                } catch (e: Exception) {
                    updateStatus(status, "Status: Could not open Accessibility Settings")
                }
            }

            text == "go back" ||
            text == "back" ||
            text == "press back" -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    service.goBack()
                    updateStatus(status, "Status: Going back")
                } else {
                    updateStatus(status, "Status: Accessibility Service is OFF")
                }
            }

            text == "go home" ||
            text == "home" ||
            text == "press home" -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    service.goHome()
                    updateStatus(status, "Status: Going Home")
                } else {
                    updateStatus(status, "Status: Accessibility Service is OFF")
                }
            }

            text == "read screen" ||
            text == "read the screen" -> {
                val service = SkyAccessibilityService.instance
                if (service != null) {
                    val screenText = service.readScreen()
                    if (screenText.isBlank()) {
                        updateStatus(status, "Status: No readable text found")
                    } else {
                        updateStatus(status, "Screen: $screenText")
                    }
                } else {
                    updateStatus(status, "Status: Accessibility Service is OFF")
                }
            }

            text.startsWith("tap ") -> {
                val target = command.substringAfter("tap ", "").trim()
                if (target.isEmpty()) {
                    updateStatus(status, "Status: Tell Sky what to tap")
                } else {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        val success = service.tapText(target)
                        updateStatus(
                            status,
                            if (success) "Status: Tapped $target"
                            else "Status: Could not find $target"
                        )
                    } else {
                        updateStatus(status, "Status: Accessibility Service is OFF")
                    }
                }
            }

            text.startsWith("type ") -> {
                val value = command.substringAfter("type ", "").trim()
                if (value.isEmpty()) {
                    updateStatus(status, "Status: Tell Sky what to type")
                } else {
                    val service = SkyAccessibilityService.instance
                    if (service != null) {
                        val success = service.typeText(value)
                        updateStatus(
                            status,
                            if (success) "Status: Typed text"
                            else "Status: Could not type text"
                        )
                    } else {
                        updateStatus(status, "Status: Accessibility Service is OFF")
                    }
                }
            }

            text == "stop sky" ||
            text == "stop" ||
            text == "emergency stop" -> {
                agentSwitch.isChecked = false
                try { tts?.stop() } catch (_: Exception) {}
                updateStatus(status, "Status: EMERGENCY STOP — Sky disabled")
            }

            text == "help" ||
            text.contains("what can you do") -> {
                updateStatus(
                    status,
                    "Status: Sky can open apps, read the screen, tap text, " +
                    "type text, go home or back, test the backend, and stop on command. " +
                    "Say or type a command to begin."
                )
            }

            else -> {
                updateStatus(status, "Status: I don't understand that command yet.")
                Toast.makeText(this, "Sky received: $command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --------------------------------
    // BACKEND TEST
    // --------------------------------
    private fun testSkyBackend(status: TextView) {
        updateStatus(status, "Status: Connecting to Sky backend...")
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
                        updateStatus(status, "Status: Sky backend connected. $response")
                    } else {
                        updateStatus(status, "Status: Backend error $responseCode")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus(status, "Status: Connection failed. ${e.message}")
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
                updateStatus(status, "Status: Opening $appName")
            } else {
                updateStatus(status, "Status: $appName is not installed")
            }
        } catch (e: Exception) {
            updateStatus(status, "Status: Could not open $appName")
        }
    }
}
