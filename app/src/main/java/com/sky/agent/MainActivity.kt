package com.sky.agent

import android.Manifest
import android.app.Activity
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
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skyai.app.R
import org.json.JSONArray
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
    private val maxActionsPerHour = 60

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
                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                               result != TextToSpeech.LANG_NOT_SUPPORTED
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.0f)
                } catch (_: Exception) { ttsReady = false }
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
                updateStatus("Sky is off"); return@setOnClickListener
            }
            if (command.isEmpty()) {
                commandInput.error = "Enter a command"; return@setOnClickListener
            }
            dispatch(command)
        }

        micButton.setOnClickListener {
            if (!agentSwitch.isChecked) { updateStatus("Sky is off"); return@setOnClickListener }
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
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(SkyWakeService.ACTION_WAKE_DETECTED, false)) {
            intent.removeExtra(SkyWakeService.ACTION_WAKE_DETECTED)
            updateStatus("Wake word heard. Listening...")
            Handler(Looper.getMainLooper()).postDelayed({ startVoiceInput() }, 600)
        }
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }

    // --------------------------------
    // WAKE SERVICE
    // --------------------------------
    private fun requestPermissionsThenStartWake() {
        val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        val needMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED

        val reqs = mutableListOf<String>()
        if (needMic) reqs.add(Manifest.permission.RECORD_AUDIO)
        if (needNotif) reqs.add(Manifest.permission.POST_NOTIFICATIONS)

        if (reqs.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, reqs.toTypedArray(), REQUEST_MIC_PERMISSION)
        } else startWakeService()
    }

    private fun startWakeService() {
        val i = Intent(this, SkyWakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        updateStatus("Wake word is on. Say \"Hey Jarvis\" to wake me.")
    }

    private fun stopWakeService() {
        try { stopService(Intent(this, SkyWakeService::class.java)) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION) {
            val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            if (micOk) startWakeService() else {
                wakeSwitch.isChecked = false
                updateStatus("Microphone permission required for wake word.")
            }
        }
    }

    // --------------------------------
    // VOICE
    // --------------------------------
    private fun startVoiceInput() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQUEST_SPEECH)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
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

    // --------------------------------
    // DISPATCH
    // --------------------------------
    private fun dispatch(command: String) {
        val t = command.lowercase().trim()
        if (t == "stop" || t == "stop sky" || t == "emergency stop") {
            agentSwitch.isChecked = false
            wakeSwitch.isChecked = false
            stopWakeService()
            try { tts?.stop() } catch (_: Exception) {}
            updateStatus("Emergency stop. Sky is disabled.")
            return
        }
        sendToAI(command)
    }

    private fun updateStatus(msg: String) {
        statusView.text = msg
        if (ttsReady) try {
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "sky_msg")
        } catch (_: Exception) {}
    }

    // --------------------------------
    // AI CALL
    // --------------------------------
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
                        updateStatus("Backend error: $code")
                        return@runOnUiThread
                    }
                    try {
                        val obj = JSONObject(txt)
                        val reply = obj.optString("reply", "").trim()

                        // Collect ALL actions (new format) plus single action (old format)
                        val actionList = mutableListOf<JSONObject>()
                        if (!obj.isNull("actions")) {
                            val arr = obj.optJSONArray("actions")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    arr.optJSONObject(i)?.let { actionList.add(it) }
                                }
                            }
                        }
                        if (actionList.isEmpty() && !obj.isNull("action")) {
                            obj.optJSONObject("action")?.let { actionList.add(it) }
                        }

                        if (reply.isNotEmpty()) updateStatus(reply)

                        if (actionList.isNotEmpty()) {
                            executeActionsSequentially(actionList, 0)
                        }
                    } catch (_: Exception) {
                        updateStatus("Bad reply from backend.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Network error: ${e.message}") }
            }
        }
    }

    // --------------------------------
    // MULTI-ACTION EXECUTOR
    // --------------------------------
    private fun executeActionsSequentially(actions: List<JSONObject>, index: Int) {
        if (index >= actions.size) return

        val action = actions[index]
        val type = action.optString("type", "").trim()
        if (type.isEmpty()) {
            executeActionsSequentially(actions, index + 1)
            return
        }

        // Rate limit
        val now = System.currentTimeMillis()
        actionTimestamps.removeAll { now - it > 3600_000 }
        if (actionTimestamps.size >= maxActionsPerHour) {
            updateStatus("Action limit reached for this hour.")
            return
        }
        actionTimestamps.add(now)
        logAction(type, action.toString())

        val delayMs: Long = try {
            runAction(action)
        } catch (e: Exception) {
            updateStatus("Action failed: ${e.message}")
            500L
        }

        // Give Android time to actually open the app before next action
        Handler(Looper.getMainLooper()).postDelayed({
            executeActionsSequentially(actions, index + 1)
        }, delayMs)
    }

    /**
     * Runs one action. Returns how long to wait before running the next one.
     */
    private fun runAction(action: JSONObject): Long {
        val type = action.optString("type", "")

        when (type) {

            "OPEN_APP" -> {
                val q = action.optString("query", "").trim()
                if (q.isEmpty()) { updateStatus("No app specified."); return 300L }
                val pkg = resolvePackage(q)
                if (pkg != null) openApp(pkg, q) else openAppByQuery(q)
                return 1800L   // wait ~1.8s for app to open
            }

            "SEARCH_WEB" -> {
                val q = action.optString("query", "").trim()
                if (q.isEmpty()) { updateStatus("Nothing to search."); return 300L }
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return 1800L
            }

            "WHATSAPP_MESSAGE" -> {
                openWhatsAppMessage(
                    action.optString("to", "").trim(),
                    action.optString("message", "").trim()
                )
                return 1500L
            }

            "SEND_SMS" -> {
                openSmsMessage(
                    action.optString("to", "").trim(),
                    action.optString("message", "").trim()
                )
                return 1500L
            }

            "READ_SCREEN" -> {
                val s = SkyAccessibilityService.instance
                if (s == null) { updateStatus("Accessibility service is off."); return 300L }
                val text = s.readScreen()
                if (text.isBlank()) updateStatus("No readable text on screen.")
                else updateStatus("The screen says: $text")
                return 1000L
            }

            "GO_BACK" -> {
                SkyAccessibilityService.instance?.goBack()
                updateStatus("Going back.")
                return 800L
            }

            "GO_HOME" -> {
                SkyAccessibilityService.instance?.goHome()
                updateStatus("Going home.")
                return 800L
            }

            "TAP" -> {
                val t = action.optString("target", "").trim()
                val s = SkyAccessibilityService.instance
                if (s == null) { updateStatus("Accessibility service is off."); return 300L }
                if (t.isEmpty()) { updateStatus("Nothing to tap."); return 300L }
                val ok = s.tapText(t)
                updateStatus(if (ok) "Tapped $t." else "Could not find $t.")
                return 900L
            }

            "TYPE" -> {
                val txt = action.optString("text", "").trim()
                val s = SkyAccessibilityService.instance
                if (s == null) { updateStatus("Accessibility service is off."); return 300L }
                if (txt.isEmpty()) { updateStatus("Nothing to type."); return 300L }
                val ok = s.typeText(txt)
                updateStatus(if (ok) "Typed." else "Could not type.")
                return 900L
            }

            else -> { updateStatus("Unknown action: $type"); return 300L }
        }
    }

    // --------------------------------
    // APP HELPERS
    // --------------------------------
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
        map[k]?.let { return it }
        for ((n, p) in map) if (k.contains(n)) return p
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
                    updateStatus("Opening $query.")
                    return
                }
            }
            updateStatus("Could not find $query.")
        } catch (_: Exception) {
            updateStatus("Could not open $query.")
        }
    }

    private fun openApp(packageName: String, appName: String) {
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                updateStatus("Opening $appName.")
            } else updateStatus("$appName is not installed.")
        } catch (_: Exception) {
            updateStatus("Could not open $appName.")
        }
    }

    private fun openWhatsAppMessage(to: String, message: String) {
        val msg = URLEncoder.encode(message, "UTF-8")
        val num = to.replace(Regex("[^0-9+]"), "")
        val url = if (num.isNotEmpty() && num.length >= 7)
            "https://wa.me/$num?text=$msg"
        else "https://wa.me/?text=$msg"
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            updateStatus("WhatsApp ready. Tap Send.")
        } catch (_: Exception) {
            try {
                val f = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?text=$msg"))
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(f)
                updateStatus("WhatsApp ready.")
            } catch (_: Exception) { updateStatus("WhatsApp is not installed.") }
        }
    }

    private fun openSmsMessage(to: String, message: String) {
        val msg = URLEncoder.encode(message, "UTF-8")
        try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to?body=$msg"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            updateStatus("SMS ready. Tap Send.")
        } catch (_: Exception) { updateStatus("Could not open SMS.") }
    }

    private fun logAction(type: String, detail: String) {
        try {
            val f = File(filesDir, "sky_action_log.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $type — $detail\n")
        } catch (_: Exception) {}
    }
}
