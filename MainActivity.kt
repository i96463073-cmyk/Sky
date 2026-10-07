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

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val commandInput = findViewById<EditText>(R.id.commandInput)
        val status = findViewById<TextView>(R.id.status)
        val agentSwitch = findViewById<SwitchMaterial>(R.id.agentSwitch)
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

        // MT5 trading remains locked.
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

            // WhatsApp
            text.contains("open whatsapp") ||
            text.contains("launch whatsapp") -> {

                openApp(
                    "com.whatsapp",
                    "WhatsApp",
                    status
                )
            }

            // Chrome
            text.contains("open chrome") ||
            text.contains("launch chrome") -> {

                openApp(
                    "com.android.chrome",
                    "Chrome",
                    status
                )
            }

            // Android Settings
            text.contains("open settings") ||
            text.contains("launch settings") -> {

                try {
                    startActivity(
                        Intent(Settings.ACTION_SETTINGS)
                    )

                    status.text = "Status: Opening Settings"

                } catch (e: Exception) {
                    status.text = "Status: Could not open Settings"
                }
            }

            // Accessibility settings
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

            // Stop command
            text == "stop sky" ||
            text == "stop" ||
            text == "emergency stop" -> {

                findViewById<SwitchMaterial>(
                    R.id.agentSwitch
                ).isChecked = false

                status.text =
                    "Status: EMERGENCY STOP — Sky disabled"
            }

            // Help
            text == "help" ||
            text.contains("what can you do") -> {

                status.text =
                    """
                    Sky can currently:
                    
                    • Open WhatsApp
                    • Open Chrome
                    • Open Settings
                    • Open Accessibility Settings
                    • Stop itself
                    
                    More abilities are coming.
                    """.trimIndent()
            }

            // Unknown command
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
