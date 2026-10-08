package com.sky.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skyai.app.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private lateinit var voiceSwitch: SwitchMaterial
    private lateinit var statusView: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var commandInput: EditText
    private lateinit var settingsPanel: View

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceMode = false
    private var isListening = false
    private var isThinking = false

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

        commandInput = findViewById(R.id.commandInput)
        statusView = findViewById(R.id.status)
        agentSwitch = findViewById(R.id.aiSwitch)
        wakeSwitch = findViewById(R.id.wakeSwitch)
        voiceSwitch = findViewById(R.id.voiceSwitch)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        settingsPanel = findViewById(R.id.settingsPanel)
        val tradeSwitch = findViewById<SwitchMaterial>(R.id.tradeSwitch)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)
        val micButton = findViewById<ImageButton>(R.id.micButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val clearChatButton = findViewById<Button>(R.id.clearChatButton)

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                try {
                    val r = tts?.setLanguage(Locale.getDefault())
                    ttsReady = !(r == TextToSpeech.LANG_MISSING_DATA ||
                                 r == TextToSpeech.LANG_NOT_SUPPORTED)
                    tts?.setSpeechRate(0.98f)
                    tts?.setPitch(1.0f)
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                if (voiceMode && !isThinking) {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (voiceMode && !isThinking) startVoiceInput()
                                    }, 500)
                                }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                if (voiceMode) {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (voiceMode && !isThinking) startVoiceInput()
                                    }, 500)
                                }
                            }
                        }
                    )
                } catch (e: Exception) { ttsReady = false }
            }
        }

        sendButton.setOnClickListener {
            val cmd = commandInput.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            commandInput.setText("")
            dispatch(cmd)
        }

        micButton.setOnClickListener {
            if (isListening) return@setOnClickListener
            startVoiceInput()
        }

        settingsButton.setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE
                else View.VISIBLE
        }

        stopButton.setOnClickListener {
            voiceMode = false
            voiceSwitch.isChecked = false
            agentSwitch.isChecked = false
            wakeSwitch.isChecked = false
            stopWakeService()
            try { tts?.stop() } catch (e: Exception) {}
            addMessage("Emergency stop. Sky is disabled.", false)
            updateStatus("Stopped")
        }

        clearChatButton.setOnClickListener {
            chatContainer.removeAllViews()
            addMessage("Hi, I'm Sky. Ask me anything or say a command.", false)
        }

        wakeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) requestPermissionsThenStartWake() else stopWakeService()
        }

        tradeSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false
                updateStatus("MT5 execution is locked.")
            }
        }

        voiceSwitch.setOnCheckedChangeListener { button, checked ->
            voiceMode = checked
            if (checked) {
                if (!agentSwitch.isChecked) agentSwitch.isChecked = true
                addMessage("Voice mode ON. Listening…", false)
                Handler(Looper.getMainLooper()).postDelayed({ startVoiceInput() }, 400)
            } else {
                try { tts?.stop() } catch (e: Exception) {}
                isListening = false
                updateStatus("Voice mode off")
            }
        }

        addMessage("Hi, I'm Sky. Ask me anything, or say: \"Sky, add a feature\".", false)
        updateStatus("Ready")
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
            updateStatus("Wake word heard")
            Handler(Looper.getMainLooper()).postDelayed({ startVoiceInput() }, 500)
        }
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        tts = null
        super.onDestroy()
    }

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
        updateStatus("Wake word on — say Alexa")
    }

    private fun stopWakeService() {
        try { stopService(Intent(this, SkyWakeService::class.java)) } catch (e: Exception) {}
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
                updateStatus("Mic permission required")
            }
        }
    }

    private fun startVoiceInput() {
        if (isListening || isThinking) return
        isListening = true
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening…")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQUEST_SPEECH)
        } catch (e: Exception) {
            isListening = false
            Toast.makeText(this, "Voice not available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH) {
            isListening = false
            if (resultCode == Activity.RESULT_OK) {
                val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spoken = r?.firstOrNull()?.trim()
                if (!spoken.isNullOrEmpty()) {
                    dispatch(spoken)
                    return
                }
            }
            if (voiceMode && !isThinking) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (voiceMode && !isThinking) startVoiceInput()
                }, 700)
            }
        }
    }

    private fun dispatch(command: String) {
        val t = command.lowercase().trim()
        if (t == "stop" || t == "stop sky" || t == "emergency stop") {
            voiceMode = false
            voiceSwitch.isChecked = false
            agentSwitch.isChecked = false
            wakeSwitch.isChecked = false
            stopWakeService()
            try { tts?.stop() } catch (e: Exception) {}
            addMessage("Emergency stop. Sky is disabled.", false)
            updateStatus("Stopped")
            return
        }
        addMessage(command, true)
        sendToAI(command)
    }

    private fun updateStatus(msg: String) {
        statusView.text = msg
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val d = resources.displayMetrics.density
        val bubble = TextView(this)
        bubble.text = text
        bubble.setTextColor(if (isUser) Color.parseColor("#0A0E18") else Color.parseColor("#E8ECF5"))
        bubble.textSize = 15f
        bubble.setLineSpacing(0f, 1.15f)
        bubble.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        bubble.maxWidth = (280 * d).toInt()

        val bg = GradientDrawable()
        bg.cornerRadius = 18f * d
        bg.setColor(if (isUser) Color.parseColor("#7CB7FF") else Color.parseColor("#1E2638"))
        bubble.background = bg

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (8 * d).toInt()
        lp.leftMargin = (6 * d).toInt()
        lp.rightMargin = (6 * d).toInt()
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        bubble.layoutParams = lp

        chatContainer.addView(bubble)
        scrollChatToBottom()
    }

    private fun addCodeProposalCard(filePath: String, summary: String, content: String) {
        val d = resources.displayMetrics.density
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())

        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1A2030"))
        bg.cornerRadius = 14f * d
        bg.setStroke((1 * d).toInt(), Color.parseColor("#7CB7FF"))
        card.background = bg

        val title = TextView(this)
        title.text = "📝 Proposed code change"
        title.setTextColor(Color.parseColor("#7CB7FF"))
        title.textSize = 14f
        title.setPadding(0, 0, 0, (6 * d).toInt())

        val fileLabel = TextView(this)
        fileLabel.text = "File: " + filePath
        fileLabel.setTextColor(Color.parseColor("#B0B0B0"))
        fileLabel.textSize = 11f
        fileLabel.setPadding(0, 0, 0, (6 * d).toInt())

        val summaryView = TextView(this)
        summaryView.text = summary
        summaryView.setTextColor(Color.parseColor("#E8ECF5"))
        summaryView.textSize = 14f
        summaryView.setPadding(0, 0, 0, (10 * d).toInt())

        val previewButton = Button(this)
        previewButton.text = "👁 View code (" + content.length + " chars)"
        previewButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Proposed: " + filePath.substringAfterLast("/"))
                .setMessage(content)
                .setPositiveButton("Close", null)
                .show()
        }

        val approveButton = Button(this)
        approveButton.text = "✅ APPROVE & DEPLOY"
        approveButton.setTextColor(Color.WHITE)
        approveButton.setBackgroundColor(Color.parseColor("#1E7A34"))
        approveButton.setOnClickListener {
            approveButton.isEnabled = false
            approveButton.text = "Deploying…"
            deployCode(filePath, content, summary)
        }

        val cancelButton = Button(this)
        cancelButton.text = "❌ Cancel"
        cancelButton.setOnClickListener {
            addMessage("Cancelled.", false)
        }

        card.addView(title)
        card.addView(fileLabel)
        card.addView(summaryView)
        card.addView(previewButton)
        card.addView(approveButton)
        card.addView(cancelButton)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (12 * d).toInt()
        lp.leftMargin = (6 * d).toInt()
        lp.rightMargin = (6 * d).toInt()
        card.layoutParams = lp

        chatContainer.addView(card)
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        Handler(Looper.getMainLooper()).postDelayed({
            chatScroll.fullScroll(View.FOCUS_DOWN)
        }, 80)
    }

    private fun sendToAI(message: String) {
        isThinking = true
        updateStatus("Thinking…")
        thread {
            try {
                val url = URL("$BACKEND_BASE/chat")
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 20000
                c.readTimeout = 60000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val body = JSONObject().put("message", message).toString()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val txt = s?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()

                runOnUiThread {
                    isThinking = false
                    if (code !in 200..299) {
                        addMessage("Backend error: $code", false)
                        updateStatus("Error")
                        if (voiceMode) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (voiceMode) startVoiceInput()
                            }, 800)
                        }
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
                                    arr.optJSONObject(i)?.let { actionList.add(it) }
                                    i++
                                }
                            }
                        }
                        if (actionList.isEmpty() && !obj.isNull("action")) {
                            obj.optJSONObject("action")?.let { actionList.add(it) }
                        }

                        if (reply.isNotEmpty()) {
                            addMessage(reply, false)
                            updateStatus("Ready")
                            speak(reply)
                        }

                        if (!obj.isNull("code_proposal")) {
                            val cp = obj.optJSONObject("code_proposal")
                            if (cp != null) {
                                addCodeProposalCard(
                                    cp.optString("file_path"),
                                    cp.optString("summary"),
                                    cp.optString("new_content")
                                )
                            }
                        } else {
                            if (voiceMode && reply.isEmpty()) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (voiceMode && !isThinking) startVoiceInput()
                                }, 600)
                            }
                        }

                        if (actionList.isNotEmpty()) {
                            executeActionsSequentially(actionList, 0)
                        }
                    } catch (e: Exception) {
                        addMessage("Bad reply from backend.", false)
                        updateStatus("Error")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isThinking = false
                    addMessage("Network error: ${e.message}", false)
                    updateStatus("Error")
                    if (voiceMode) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (voiceMode) startVoiceInput()
                        }, 800)
                    }
                }
            }
        }
    }

    private fun deployCode(filePath: String, content: String, summary: String) {
        updateStatus("Deploying…")
        thread {
            try {
                val url = URL("$BACKEND_BASE/deploy")
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 20000
                c.readTimeout = 45000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val body = JSONObject()
                    .put("file_path", filePath)
                    .put("new_content", content)
                    .put("commit_message", "SkyAI: " + summary.take(60))
                    .toString()

                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val txt = s?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()

                runOnUiThread {
                    if (code in 200..299) {
                        addMessage("✅ Deployed to GitHub. New APK will be built in ~1 minute. Check Actions → Artifacts.", false)
                        updateStatus("Deployed")
                    } else {
                        addMessage("❌ Deploy failed: $txt", false)
                        updateStatus("Deploy failed")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addMessage("❌ Deploy error: ${e.message}", false)
                    updateStatus("Deploy error")
                }
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sky_msg") } catch (e: Exception) {}
        } else if (voiceMode) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (voiceMode && !isThinking) startVoiceInput()
            }, 500)
        }
    }

    private fun executeActionsSequentially(actions: List<JSONObject>, index: Int) {
        if (index >= actions.size) return
        val action = actions[index]
        val type = action.optString("type", "").trim()
        if (type.isEmpty()) { executeActionsSequentially(actions, index + 1); return }

        val now = System.currentTimeMillis()
        actionTimestamps.removeAll { now - it > 3600_000L }
        if (actionTimestamps.size >= maxActionsPerHour) {
            updateStatus("Action limit reached")
            return
        }
        actionTimestamps.add(now)
        logAction(type, action.toString())

        var delayMs = 500L
        try { delayMs = runAction(action) } catch (e: Exception) {
            updateStatus("Action failed: ${e.message}")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            executeActionsSequentially(actions, index + 1)
        }, delayMs)
    }

    private fun runAction(action: JSONObject): Long {
        val type = action.optString("type", "")

        if (type == "OPEN_APP") {
            val q = action.optString("query", "").trim()
            if (q.isEmpty()) return 300L
            notifyTap("Opening $q")
            val pkg = resolvePackage(q)
            if (pkg != null) openApp(pkg, q) else openAppByQuery(q)
            return 2500L
        }
        if (type == "SEARCH_WEB") {
            val q = action.optString("query", "").trim()
            if (q.isEmpty()) return 300L
            notifyTap("Searching: $q")
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
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
            if (svc == null) { updateStatus("Accessibility off"); return 300L }
            val text = svc.readScreen()
            updateStatus(if (text.isBlank()) "Nothing on screen" else "Screen read")
            return 1000L
        }
        if (type == "READ_SCREEN_VISION") {
            val svc = SkyAccessibilityService.instance
            if (svc == null) { updateStatus("Accessibility off"); return 300L }
            val text = svc.readScreen()
            updateStatus(if (text.isBlank()) "Nothing on screen" else "Screen read")
            return 1000L
        }
        if (type == "GO_BACK") {
            notifyTap("Back")
            SkyAccessibilityService.instance?.goBack()
            return 1200L
        }
        if (type == "GO_HOME") {
            notifyTap("Home")
            SkyAccessibilityService.instance?.goHome()
            return 1200L
        }
        if (type == "TAP") {
            val target = action.optString("target", "").trim()
            val svc = SkyAccessibilityService.instance
            if (svc == null) { updateStatus("Accessibility off"); return 300L }
            if (target.isEmpty()) return 300L
            notifyTap("Tap: $target")
            val ok = svc.tapText(target)
            updateStatus(if (ok) "Tapped $target" else "Not found: $target")
            return 2000L
        }
        if (type == "VISION_TAP") {
            val target = action.optString("target", "").trim()
            if (target.isEmpty()) return 300L
            notifyTap("Vision: $target")
            doVisionTap(target)
            return 4500L
        }
        if (type == "TYPE") {
            val txt = action.optString("text", "").trim()
            val svc = SkyAccessibilityService.instance
            if (svc == null) { updateStatus("Accessibility off"); return 300L }
            if (txt.isEmpty()) return 300L
            notifyTap("Type: ${txt.take(40)}")
            svc.typeText(txt)
            return 1200L
        }
        if (type == "SCROLL_DOWN") {
            notifyTap("Scroll down")
            SkyAccessibilityService.instance?.scrollDown()
            return 1200L
        }
        if (type == "SCROLL_UP") {
            notifyTap("Scroll up")
            SkyAccessibilityService.instance?.scrollUp()
            return 1200L
        }
        if (type == "WAIT") return action.optLong("ms", 1500L)

        updateStatus("Unknown action: $type")
        return 300L
    }

    private fun doVisionTap(target: String) {
        val svc = SkyAccessibilityService.instance
        if (svc == null) { updateStatus("Accessibility off"); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updateStatus("Vision needs Android 11+"); return
        }
        svc.takeScreenshot { bitmap ->
            if (bitmap == null) { updateStatus("Screenshot failed"); return@takeScreenshot }
            thread {
                try {
                    val scale = 0.5f
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true
                    )
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                    val url = URL("$BACKEND_BASE/vision")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 25000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                    val body = JSONObject()
                        .put("image", b64)
                        .put("target", target)
                        .put("width", scaled.width)
                        .put("height", scaled.height)
                        .toString()
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    val s = if (code in 200..299) conn.inputStream else conn.errorStream
                    val txt = s?.bufferedReader()?.use { it.readText() } ?: ""
                    conn.disconnect()

                    runOnUiThread {
                        try {
                            val obj = JSONObject(txt)
                            if (!obj.optBoolean("found", false)) {
                                updateStatus("Vision: not found"); return@runOnUiThread
                            }
                            val x = obj.optDouble("x", -1.0).toFloat()
                            val y = obj.optDouble("y", -1.0).toFloat()
                            if (x < 0 || y < 0) { updateStatus("Bad coords"); return@runOnUiThread }
                            val factor = 1f / scale
                            svc.tapAt(x * factor, y * factor)
                            updateStatus("Tapped " + obj.optString("label", target))
                        } catch (e: Exception) {
                            updateStatus("Bad vision reply")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { updateStatus("Vision error: ${e.message}") }
                }
            }
        }
    }

    private fun resolvePackage(friendly: String): String? {
        val map = mapOf(
            "whatsapp" to "com.whatsapp", "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome", "settings" to "com.android.settings",
            "telegram" to "org.telegram.messenger", "youtube" to "com.google.android.youtube",
            "camera" to "com.sec.android.app.camera", "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana", "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm"
        )
        val k = friendly.lowercase().trim()
        map[k]?.let { return it }
        for ((name, pkg) in map) if (k.contains(name)) return pkg
        return null
    }

    private fun openAppByQuery(query: String) {
        try {
            val pm = packageManager
            for (pkg in pm.getInstalledPackages(0)) {
                val info = pkg.applicationInfo ?: continue
                val label = pm.getApplicationLabel(info).toString().lowercase()
                if (label == query.lowercase() || label.contains(query.lowercase())) {
                    val launch = pm.getLaunchIntentForPackage(pkg.packageName) ?: continue
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    return
                }
            }
            updateStatus("Not installed: $query")
        } catch (e: Exception) { updateStatus("Could not open $query") }
    }

    private fun openApp(packageName: String, appName: String) {
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else updateStatus("$appName not installed")
        } catch (e: Exception) { updateStatus("Could not open $appName") }
    }

    private fun openWhatsAppMessage(to: String, message: String) {
        val msg = URLEncoder.encode(message, "UTF-8")
        val num = to.replace(Regex("[^0-9+]"), "")
        val url = if (num.isNotEmpty() && num.length >= 7) "https://wa.me/$num?text=$msg"
                  else "https://wa.me/?text=$msg"
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i)
        } catch (e: Exception) {
            try {
                val f = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?text=$msg"))
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(f)
            } catch (e2: Exception) { updateStatus("WhatsApp not installed") }
        }
    }

    private fun openSmsMessage(to: String, message: String) {
        val msg = URLEncoder.encode(message, "UTF-8")
        try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to?body=$msg"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i)
        } catch (e: Exception) { updateStatus("SMS failed") }
    }

    private fun createTapChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                TAP_CHANNEL_ID, "Sky Actions", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows what Sky is doing" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notifyTap(label: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, TAP_CHANNEL_ID)
                .setContentTitle("Sky: $label").setContentText("Tap STOP to halt.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true).build()
            nm.notify(TAP_NOTIF_ID, notif)
        } catch (e: Exception) {}
    }

    private fun logAction(type: String, detail: String) {
        try {
            val f = File(filesDir, "sky_action_log.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $type — $detail\n")
        } catch (e: Exception) {}
    }
}
