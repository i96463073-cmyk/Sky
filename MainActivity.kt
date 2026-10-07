package com.sky.agent

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val command = findViewById<android.widget.EditText>(R.id.commandInput)
        val status = findViewById<android.widget.TextView>(R.id.status)
        val agent = findViewById<SwitchMaterial>(R.id.agentSwitch)
        findViewById<android.widget.Button>(R.id.runButton).setOnClickListener {
            val text = command.text.toString().trim()
            if (!agent.isChecked) { status.text = "Status: Sky is OFF"; return@setOnClickListener }
            if (text.isEmpty()) { command.error = "Enter a command"; return@setOnClickListener }
            status.text = "Status: Command received — execution engine coming in V1.1"
            Toast.makeText(this, "Sky received: $text", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.widget.Button>(R.id.stopButton).setOnClickListener {
            agent.isChecked = false
            status.text = "Status: EMERGENCY STOP — Sky disabled"
        }
        findViewById<SwitchMaterial>(R.id.tradeSwitch).setOnCheckedChangeListener { button, checked ->
            if (checked) { button.isChecked = false; Toast.makeText(this, "MT5 execution is locked in V1", Toast.LENGTH_SHORT).show() }
        }
    }
}
