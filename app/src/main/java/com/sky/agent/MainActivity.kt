package com.sky.agent

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.skyai.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var pendingContainer: LinearLayout
    private lateinit var completedContainer: LinearLayout
    private lateinit var btnRefresh: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvUpdated: TextView
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatBar: LinearLayout
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvCloseChat: TextView

    private val BACKEND_BASE = "https://sky-ai-backend.i96463073.workers.dev"

    private val BG = Color.parseColor("#0A0E17")
    private val BLUE = Color.parseColor("#3B82F6")
    private val BLUE_DARK = Color.parseColor("#2563EB")
    private val GREEN = Color.parseColor("#10B981")
    private val RED = Color.parseColor("#EF4444")
    private val MUTED = Color.parseColor("#94A3B8")
    private val GLASS_STROKE = Color.parseColor("#2AFFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        pendingContainer = findViewById(R.id.pendingContainer)
        completedContainer = findViewById(R.id.completedContainer)
        btnRefresh = findViewById(R.id.btnRefresh)
        progressBar = findViewById(R.id.progressBar)
        tvUpdated = findViewById(R.id.tvUpdated)
        chatPanel = findViewById(R.id.chatPanel)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        chatBar = findViewById(R.id.chatBar)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvCloseChat = findViewById(R.id.tvCloseChat)

        setupWindow()
        styleStaticViews()

        applyBounce(btnRefresh)
        applyBounce(btnSend)
        applyBounce(tvCloseChat)

        btnRefresh.setOnClickListener { fetchSignals() }
        btnSend.setOnClickListener { sendChat() }
        tvCloseChat.setOnClickListener { chatPanel.visibility = View.GONE }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else false
        }

        // Load on startup
        fetchSignals()
    }

    // ---------- Window / styling ----------

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun styleStaticViews() {
        val refreshBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(BLUE, BLUE_DARK)
        )
        refreshBg.cornerRadius = dpf(14f)
        btnRefresh.background = refreshBg

        chatBar.background = roundedBg(Color.parseColor("#131B2B"), dpf(28f), GLASS_STROKE)
        chatBar.elevation = dpf(12f)

        val sendBg = GradientDrawable()
        sendBg.shape = GradientDrawable.OVAL
        sendBg.setColor(BLUE)
        btnSend.background = sendBg

        chatPanel.background = roundedBg(Color.parseColor("#F0111827"), dpf(20f), GLASS_STROKE)
        chatPanel.elevation = dpf(8f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun roundedBg(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = radius
        g.setColor(color)
        if (strokeColor != null) g.setStroke(dp(1), strokeColor)
        return g
    }

    // ---------- Bounce animation ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun applyBounce(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.92f).scaleY(0.92f)
                        .setDuration(90)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(3f))
                        .start()
            }
            false // let the normal click listener still fire
        }
    }

    // ---------- Signals ----------

    private fun fetchSignals() {
        progressBar.visibility = View.VISIBLE
        btnRefresh.isEnabled = false
        btnRefresh.text = "Syncing…"

        thread {
            try {
                val url = URL("$BACKEND_BASE/api/signals")
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "GET"
                c.connectTimeout = 15000
                c.readTimeout = 15000

                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val txt = s?.bufferedReader()?.use { it.readText() } ?: "{}"
                c.disconnect()

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnRefresh.isEnabled = true
                    btnRefresh.text = "Sync Live Signals"

                    if (code in 200..299) {
                        parseAndDisplay(txt)
                        tvUpdated.text = "Updated " +
                            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    } else {
                        showError("Server Error: $code")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnRefresh.isEnabled = true
                    btnRefresh.text = "Sync Live Signals"
                    showError("Network Error: ${e.message}")
                }
            }
        }
    }

    private fun parseAndDisplay(jsonStr: String) {
        pendingContainer.removeAllViews()
        completedContainer.removeAllViews()

        try {
            val json = JSONObject(jsonStr)
            val pendingArray = json.optJSONArray("pending") ?: JSONArray()
            val completedArray = json.optJSONArray("completed") ?: JSONArray()

            if (pendingArray.length() == 0) {
                addEmptyText(pendingContainer, "No active signals right now.\nWaiting for an SMC setup...")
            } else {
                for (i in 0 until pendingArray.length()) {
                    addSignalCard(pendingContainer, pendingArray.getJSONObject(i), true, i)
                }
            }

            if (completedArray.length() == 0) {
                addEmptyText(completedContainer, "No signal history yet.")
            } else {
                for (i in 0 until completedArray.length()) {
                    addSignalCard(completedContainer, completedArray.getJSONObject(i), false, i)
                }
            }
        } catch (e: Exception) {
            showError("Parse Error: ${e.message}")
        }
    }

    private fun addSignalCard(
        container: LinearLayout,
        signal: JSONObject,
        isPending: Boolean,
        index: Int
    ) {
        val symbol = signal.optString("symbol", "UNKNOWN")
        val direction = signal.optString("direction", "NO_TRADE").uppercase(Locale.US)
        val entry = signal.optDouble("entry", 0.0)
        val sl = signal.optDouble("stop_loss", 0.0)
        val tp = signal.optDouble("take_profit", 0.0)
        val timeStr = signal.optString("created_at", "")
        val result = signal.optString("result", "").uppercase(Locale.US)

        val accent = when (direction) {
            "BUY" -> GREEN
            "SELL" -> RED
            else -> MUTED
        }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(18), dp(18), dp(18))

        // Glass-style gradient card
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1C2740"), Color.parseColor("#0F1626"))
        )
        bg.cornerRadius = dpf(18f)
        bg.setStroke(dp(1), GLASS_STROKE)
        card.background = bg
        card.elevation = dpf(6f)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(14)
        card.layoutParams = lp

        // Top row: symbol + direction badge
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL

        val tvSymbol = TextView(this)
        tvSymbol.text = symbol
        tvSymbol.setTextColor(Color.WHITE)
        tvSymbol.textSize = 20f
        tvSymbol.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tvSymbol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val tvBadge = TextView(this)
        tvBadge.text = direction
        tvBadge.setTextColor(accent)
        tvBadge.textSize = 13f
        tvBadge.letterSpacing = 0.1f
        tvBadge.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tvBadge.setPadding(dp(14), dp(6), dp(14), dp(6))
        tvBadge.background = roundedBg(
            ColorUtils.setAlphaComponent(accent, 0x26),
            dpf(20f),
            ColorUtils.setAlphaComponent(accent, 0x80)
        )

        topRow.addView(tvSymbol)
        topRow.addView(tvBadge)
        card.addView(topRow)

        // Time
        val tvTime = TextView(this)
        tvTime.text = formatTime(timeStr)
        tvTime.setTextColor(Color.parseColor("#64748B"))
        tvTime.textSize = 12f
        tvTime.setPadding(0, dp(4), 0, dp(14))
        card.addView(tvTime)

        // Entry (large)
        val tvEntryLabel = TextView(this)
        tvEntryLabel.text = "ENTRY"
        tvEntryLabel.setTextColor(MUTED)
        tvEntryLabel.textSize = 11f
        tvEntryLabel.letterSpacing = 0.12f
        card.addView(tvEntryLabel)

        val tvEntry = TextView(this)
        tvEntry.text = formatPrice(symbol, entry)
        tvEntry.setTextColor(Color.WHITE)
        tvEntry.textSize = 30f
        tvEntry.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        card.addView(tvEntry)

        // Divider
        val divider = View(this)
        divider.setBackgroundColor(Color.parseColor("#1FFFFFFF"))
        val dlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        dlp.topMargin = dp(14)
        dlp.bottomMargin = dp(14)
        divider.layoutParams = dlp
        card.addView(divider)

        // SL / TP
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.HORIZONTAL
        grid.weightSum = 2f
        grid.addView(createDataColumn("STOP LOSS", formatPrice(symbol, sl), RED))
        grid.addView(createDataColumn("TAKE PROFIT", formatPrice(symbol, tp), GREEN))
        card.addView(grid)

        // Outcome pill for history items
        if (!isPending && result.isNotEmpty() && result != "NULL") {
            val resColor = when (result) {
                "WIN" -> GREEN
                "LOSS" -> RED
                else -> MUTED
            }
            val tvResult = TextView(this)
            tvResult.text = result
            tvResult.setTextColor(resColor)
            tvResult.textSize = 12f
            tvResult.letterSpacing = 0.1f
            tvResult.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            tvResult.setPadding(dp(12), dp(5), dp(12), dp(5))
            tvResult.background = roundedBg(
                ColorUtils.setAlphaComponent(resColor, 0x26),
                dpf(16f),
                ColorUtils.setAlphaComponent(resColor, 0x80)
            )
            val rlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rlp.topMargin = dp(14)
            tvResult.layoutParams = rlp
            card.addView(tvResult)
        }

        // Entrance animation
        card.alpha = 0f
        card.translationY = dpf(16f)
        container.addView(card)
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * 60L)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun createDataColumn(label: String, value: String, color: Int): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val tvLabel = TextView(this)
        tvLabel.text = label
        tvLabel.setTextColor(MUTED)
        tvLabel.textSize = 11f
        tvLabel.letterSpacing = 0.12f

        val tvValue = TextView(this)
        tvValue.text = value
        tvValue.setTextColor(color)
        tvValue.textSize = 18f
        tvValue.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

        col.addView(tvLabel)
        col.addView(tvValue)
        return col
    }

    private fun formatPrice(symbol: String, v: Double): String {
        if (v == 0.0) return "—"
        val decimals = when {
            symbol.contains("XAU") -> 2
            symbol.contains("JPY") -> 3
            else -> 5
        }
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    private fun formatTime(isoString: String): String {
        if (isoString.isEmpty()) return ""
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in patterns) {
            try {
                val parser = SimpleDateFormat(p, Locale.US)
                parser.timeZone = TimeZone.getTimeZone("UTC")
                val date = parser.parse(isoString) ?: continue
                return SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(date)
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        return isoString
    }

    private fun addEmptyText(container: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.parseColor("#475569"))
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(24), 0, dp(24))
        container.addView(tv)
    }

    private fun showError(msg: String) {
        pendingContainer.removeAllViews()
        completedContainer.removeAllViews()
        val tv = TextView(this)
        tv.text = msg
        tv.setTextColor(RED)
        pendingContainer.addView(tv)
    }

    // ---------- AI chat ----------

    private fun sendChat() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        etInput.setText("")
        chatPanel.visibility = View.VISIBLE
        addBubble(text, isUser = true)
        val typing = addBubble("Sky AI is thinking…", isUser = false)
        btnSend.isEnabled = false

        thread {
            val reply: String = try {
                val c = URL("$BACKEND_BASE/chat").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use {
                    it.write(JSONObject().put("message", text).toString().toByteArray(Charsets.UTF_8))
                }

                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val body = s?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()

                if (code in 200..299) extractReply(body) else "Server Error: $code"
            } catch (e: Exception) {
                "Network Error: ${e.message}"
            }

            runOnUiThread {
                typing.text = reply
                btnSend.isEnabled = true
                scrollChatToBottom()
            }
        }
    }

    private fun extractReply(body: String): String {
        if (body.isBlank()) return "(empty reply)"
        return try {
            val j = JSONObject(body)
            val keys = listOf("reply", "response", "text", "message", "answer", "content", "output")
            for (k in keys) {
                val v = j.optString(k, "")
                if (v.isNotBlank() && v != "null") return v
            }
            body
        } catch (e: Exception) {
            body
        }
    }

    private fun addBubble(text: String, isUser: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()

        if (isUser) {
            val g = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(BLUE, BLUE_DARK)
            )
            g.cornerRadius = dpf(16f)
            tv.background = g
        } else {
            tv.background = roundedBg(Color.parseColor("#1E293B"), dpf(16f), GLASS_STROKE)
            tv.setTextIsSelectable(true)
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        lp.bottomMargin = dp(8)
        tv.layoutParams = lp

        chatContainer.addView(tv)
        scrollChatToBottom()
        return tv
    }

    private fun scrollChatToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
