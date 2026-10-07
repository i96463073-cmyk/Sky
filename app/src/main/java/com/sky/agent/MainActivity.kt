package com.sky.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skyai.app.R
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val commandInput = findViewById<EditText>(R.id.commandInput)
        val status = findViewById<TextView>(R.id.status)
        val agentSwitch = findViewById<SwitchMaterial>(R.id.aiSwitch)
        val tradeSwitch = findViewById<SwitchMaterial>(R.id.tradeSwitch)

        val runButton = findViewById<Button>(R.id.runButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        runButton.setOnClickListener {

            val command = commandInput.text.toString().trim()

            if (!agentSwitch.isChecked) {
                status.text = "Status: Sky is OFF"
                return@setOnClickListener
            }

            if (command.isEmpty()) {
                commandInput.error = "Enter a command"
                return@setOnClickListener
            }

            processCommand(command, status)
        }

        stopButton.setOnClickListener {
            agentSwitch.isChecked = false
            status.text = "Status: EMERGENCY STOP — Sky disabled"

            Toast.makeText(
                this,
                "Sky has been stopped",
                Toast.LENGTH_SHORT
            ).show()
        }

        // MT5 execution remains locked.
        tradeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false

                Toast.makeText(
                    this,
                    "MT5 execution is locked for now",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun processCommand(
        command: String,
        status: TextView
    ) {

        val text = command.lowercase().trim()

        when {

            // --------------------------------
            // TEST CLOUDFLARE BACKEND
            // --------------------------------

            text == "test backend" ||
            text == "test sky" ||
            text == "check backend" -> {

                testSkyBackend(status)
            }

            // --------------------------------
            // OPEN WHATSAPP
            // --------------------------------

            text.contains("open whatsapp") ||
            text.contains("launch whatsapp") -> {

                openApp(
                    "com.whatsapp",
                    "WhatsApp",
                    status
                )
            }

            // --------------------------------
            // OPEN CHROME
            // --------------------------------

            text.contains("open chrome") ||
            text.contains("launch chrome") -> {

                openApp(
                    "com.android.chrome",
                    "Chrome",
                    status
                )
            }

            // --------------------------------
            // OPEN SETTINGS
            // --------------------------------

            text.contains("open settings") ||
            text.contains("launch settings") -> {

                try {

                    startActivity(
                        Intent(Settings.ACTION_SETTINGS)
                    )

                    status.text =
                        "Status: Opening Settings"

                } catch (e: Exception) {

                    status.text =
                        "Status: Could not open Settings"
                }
            }

            // --------------------------------
            // ACCESSIBILITY SETTINGS
            // --------------------------------

            text.contains("accessibility settings") -> {

                try {

                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )

                    status.text =
                        "Status: Opening Accessibility Settings"

                } catch (e: Exception) {

                    status.text =
                        "Status: Could not open Accessibility Settings"
                }
            }

            // --------------------------------
            // GO BACK
            // --------------------------------

            text == "go back" ||
            text == "back" ||
            text == "press back" -> {

                val service =
                    SkyAccessibilityService.instance

                if (service != null) {

                    service.goBack()

                    status.text =
                        "Status: Going back"

                } else {

                    status.text =
                        "Status: Accessibility Service is OFF"
                }
            }

            // --------------------------------
            // GO HOME
            // --------------------------------

            text == "go home" ||
            text == "home" ||
            text == "press home" -> {

                val service =
                    SkyAccessibilityService.instance

                if (service != null) {

                    service.goHome()

                    status.text =
                        "Status: Going Home"

                } else {

                    status.text =
                        "Status: Accessibility Service is OFF"
                }
            }

            // --------------------------------
            // READ SCREEN
            // --------------------------------

            text == "read screen" ||
            text == "read the screen" -> {

                val service =
                    SkyAccessibilityService.instance

                if (service != null) {

                    val screenText =
                        service.readScreen()

                    if (screenText.isBlank()) {

                        status.text =
                            "Status: No readable text found"

                    } else {

                        status.text =
                            "Screen:\n$screenText"
                    }

                } else {

                    status.text =
                        "Status: Accessibility Service is OFF"
                }
            }

            // --------------------------------
            // TAP TEXT
            // Example: "tap search"
            // --------------------------------

            text.startsWith("tap ") -> {

                val target =
                    command.substringAfter(
                        "tap ",
                        ""
                    ).trim()

                if (target.isEmpty()) {

                    status.text =
                        "Status: Tell Sky what to tap"

                } else {

                    val service =
                        SkyAccessibilityService.instance

                    if (service != null) {

                        val success =
                            service.tapText(target)

                        status.text =
                            if (success) {
                                "Status: Tapped \"$target\""
                            } else {
                                "Status: Could not find \"$target\""
                            }

                    } else {

                        status.text =
                            "Status: Accessibility Service is OFF"
                    }
                }
            }

            // --------------------------------
            // TYPE TEXT
            // Example: "type hello"
            // --------------------------------

            text.startsWith("type ") -> {

                val value =
                    command.substringAfter(
                        "type ",
                        ""
                    ).trim()

                if (value.isEmpty()) {

                    status.text =
                        "Status: Tell Sky what to type"

                } else {

                    val service =
                        SkyAccessibilityService.instance

                    if (service != null) {

                        val success =
                            service.typeText(value)

                        status.text =
                            if (success) {
                                "Status: Typed text"
                            } else {
                                "Status: Could not type text"
                            }

                    } else {

                        status.text =
                            "Status: Accessibility Service is OFF"
                    }
                }
            }

            // --------------------------------
            // STOP SKY
            // --------------------------------

            text == "stop sky" ||
            text == "stop" ||
            text == "emergency stop" -> {

                agentSwitch.isChecked = false

                status.text =
                    "Status: EMERGENCY STOP — Sky disabled"
            }

            // --------------------------------
            // HELP
            // --------------------------------

            text == "help" ||
            text.contains("what can you do") -> {

                status.text =
                    """
                    Sky can currently:

                    • Test Cloudflare backend
                    • Open WhatsApp
                    • Open Chrome
                    • Open Settings
                    • Open Accessibility Settings
                    • Go Back
                    • Go Home
                    • Read the screen
                    • Tap visible text
                    • Type text
                    • Emergency Stop

                    More abilities are coming.
                    """.trimIndent()
            }

            // --------------------------------
            // UNKNOWN COMMAND
            // --------------------------------

            else -> {

                status.text =
                    "Status: I don't understand that command yet."

                Toast.makeText(
                    this,
                    "Sky received: $command",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // --------------------------------
    // CLOUDFLARE BACKEND CONNECTION
    // --------------------------------

    private fun testSkyBackend(status: TextView) {

        status.text =
            "Status: Connecting to Sky backend..."

        thread {

            try {

                val url = URL(
                    "https://sky-ai-backend.i96463073.workers.dev/health"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode =
                    connection.responseCode

                val response =
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                connection.disconnect()

                runOnUiThread {

                    if (responseCode == 200) {

                        status.text =
                            "Status: Sky backend connected ✅\n$response"

                    } else {

                        status.text =
                            "Status: Backend error ($responseCode)"
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    status.text =
                        "Status: Connection failed ❌\n${e.message}"
                }
            }
        }
    }

    // --------------------------------
    // OPEN APP
    // --------------------------------

    private fun openApp(
        packageName: String,
        appName: String,
        status: TextView
    ) {

        try {

            val launchIntent =
                packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {

                startActivity(launchIntent)

                status.text =
                    "Status: Opening $appName"

            } else {

                status.text =
                    "Status: $appName is not installed"
            }

        } catch (e: Exception) {

            status.text =
                "Status: Could not open $appName"
        }
    }
}
