package com.sky.agent

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // ---- Views ----
    private lateinit var rootLayout: LinearLayout
    private lateinit var pendingContainer: LinearLayout
    private lateinit var completedContainer: LinearLayout
    private lateinit var tickerContainer: LinearLayout
    private lateinit var newsContainer: LinearLayout
    private lateinit var btnRefresh: Button
    private lateinit var btnCharts: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvUpdated: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvNewsStatus: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var tvCompletedCount: TextView
    private lateinit var tvWins: TextView
    private lateinit var tvLosses: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvCloseChat: TextView

    // ---- Config (existing backend) ----
    private val BACKEND_BASE = "https://sky-ai-backend.i96463073.workers.dev"
    private val POLL_MS = 5000L

    // ---- Theme ----
    private val BG = Color.parseColor("#121212")
    private val CARD = Color.parseColor("#1E1E1E")
    private val GREEN = Color.parseColor("#00E676")
    private val RED = Color.parseColor("#FF1744")
    private val AMBER = Color.parseColor("#FFB300")
    private val MUTED = Color.parseColor("#9E9E9E")
    private val STROKE = Color.parseColor("#2E2E2E")

    // ---- Polling state ----
    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)
    private var polling = false
    private var hasData = false
    private var lastOkTime: String? = null
    private var lastSignalsSig: String? = null
    private val priceViews = LinkedHashMap<String, TextView>()
    private val lastPrices = HashMap<String, Double>()
    private var chartDialog: Dialog? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            fetchLive()
            handler.postDelayed(this, POLL_MS) // fixed cadence; inFlight guard prevents overlap
        }
    }

    // ---- TradingView symbol mapping ----
    private val FALLBACK_SYMBOLS = listOf("XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "AUDUSD")
    private val EXPLICIT_TV = mapOf(
        "XAUUSD" to "OANDA:XAUUSD",
        "XAGUSD" to "OANDA:XAGUSD"
    )
    private val FX_CCY = setOf("USD", "EUR", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF")
    private val TV_PATTERN = Regex("^[A-Z0-9_]{1,20}:[A-Z0-9_.!]{1,20}$")

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        pendingContainer = findViewById(R.id.pendingContainer)
        completedContainer = findViewById(R.id.completedContainer)
        tickerContainer = findViewById(R.id.tickerContainer)
        newsContainer = findViewById(R.id.newsContainer)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCharts = findViewById(R.id.btnCharts)
        progressBar = findViewById(R.id.progressBar)
        tvUpdated = findViewById(R.id.tvUpdated)
        tvStatus = findViewById(R.id.tvStatus)
        tvSession = findViewById(R.id.tvSession)
        tvNewsStatus = findViewById(R.id.tvNewsStatus)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        tvCompletedCount = findViewById(R.id.tvCompletedCount)
        tvWins = findViewById(R.id.tvWins)
        tvLosses = findViewById(R.id.tvLosses)
        tvWinRate = findViewById(R.id.tvWinRate)
        chatPanel = findViewById(R.id.chatPanel)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvCloseChat = findViewById(R.id.tvCloseChat)

        setupWindow()

        applyBounce(btnRefresh)
        applyBounce(btnCharts)
        applyBounce(btnSend)
        applyBounce(tvCloseChat)

        btnRefresh.setOnClickListener { fetchLive(manual = true) }
        btnCharts.setOnClickListener { showTradingViewChart("XAUUSD") }
        btnSend.setOnClickListener { sendChat() }
        tvCloseChat.setOnClickListener { chatPanel.visibility = View.GONE }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else false
        }

        addEmptyText(pendingContainer, "Connecting to backend…")
        addEmptyText(completedContainer, "Connecting to backend…")
        tvNewsStatus.text = "No news data received from the backend yet."
        updateSessionPanel()
    }

    override fun onResume() {
        super.onResume()
        if (!polling) {
            polling = true
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        chartDialog?.dismiss()
        chartDialog = null
        super.onDestroy()
    }

    // =====================================================================
    // Window / helpers
    // =====================================================================

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun roundedBg(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = radius
        g.setColor(color)
        if (strokeColor != null) g.setStroke(dp(1), strokeColor)
        return g
    }

    private fun makeText(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        return t
    }

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "").trim()
    private fun JSONObject.num(k: String): Double = if (isNull(k)) Double.NaN else optDouble(k, Double.NaN)

    private fun nowHms(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // Scale to 0.9 then back to 1.0 (ViewPropertyAnimator). Click listener still fires.
    @SuppressLint("ClickableViewAccessibility")
    private fun applyBounce(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.9f).scaleY(0.9f)
                        .setDuration(80)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(OvershootInterpolator(2f))
                        .start()
            }
            false
        }
    }

    // =====================================================================
    // Polling /api/live (falls back to the original /api/signals on 404)
    // =====================================================================

    private fun httpGet(path: String): Pair<Int, String> {
        val c = URL(BACKEND_BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun fetchLive(manual: Boolean = false) {
        if (!inFlight.compareAndSet(false, true)) return // never overlap requests
        if (!hasData) setStatus(State.CONNECTING)
        if (manual) {
            progressBar.visibility = View.VISIBLE
            btnRefresh.isEnabled = false
        }

        thread {
            var code = -1
            var body = ""
            var err: String? = null
            try {
                var r = httpGet("/api/live")
                if (r.first == 404) r = httpGet("/api/signals")
                code = r.first
                body = r.second
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }

            runOnUiThread {
                inFlight.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressBar.visibility = View.GONE
                btnRefresh.isEnabled = true
                when {
                    err != null -> onFailure("Network error: $err")
                    code !in 200..299 -> onFailure("HTTP $code")
                    else -> onSuccess(body)
                }
            }
        }
    }

    private fun onSuccess(body: String) {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            onFailure("Malformed response")
            return
        }
        if (json.has("ok") && !json.optBoolean("ok", true)) {
            onFailure("Backend returned ok=false")
            return
        }

        try {
            json.optJSONObject("live_prices")?.let { updateTicker(it) }
            val pending = json.optJSONArray("pending")
            val completed = json.optJSONArray("completed")
            if (pending != null || completed != null) {
                renderSignals(pending ?: JSONArray(), completed ?: JSONArray())
            }
            renderNews(json)
        } catch (e: Exception) {
            onFailure("Parse error: ${e.message}")
            return
        }

        hasData = true
        lastOkTime = nowHms()
        tvUpdated.text = "Last successful refresh $lastOkTime"
        setStatus(State.LIVE)
        updateSessionPanel()
    }

    private fun onFailure(msg: String) {
        setStatus(State.OFFLINE)
        tvUpdated.text = if (lastOkTime != null) "Last successful refresh $lastOkTime · $msg" else msg
        if (!hasData) {
            pendingContainer.removeAllViews()
            completedContainer.removeAllViews()
            addEmptyText(pendingContainer, msg)
        }
        // If we already have data, it is deliberately left on screen.
    }

    private enum class State { LIVE, CONNECTING, OFFLINE }

    private fun setStatus(s: State) {
        when (s) {
            State.LIVE -> { tvStatus.text = "● LIVE"; tvStatus.setTextColor(GREEN) }
            State.CONNECTING -> { tvStatus.text = "● CONNECTING"; tvStatus.setTextColor(AMBER) }
            State.OFFLINE -> {
                tvStatus.text = if (hasData) "● OFFLINE · showing last data" else "● OFFLINE"
                tvStatus.setTextColor(RED)
            }
        }
    }

    // =====================================================================
    // Ticker
    // =====================================================================

    private fun updateTicker(prices: JSONObject) {
        val keys = ArrayList<String>()
        val it = prices.keys()
        while (it.hasNext()) keys.add(it.next())

        if (keys.toSet() != priceViews.keys.toSet()) {
            tickerContainer.removeAllViews()
            priceViews.clear()
            for (sym in keys) {
                val chip = LinearLayout(this)
                chip.orientation = LinearLayout.VERTICAL
                chip.setPadding(dp(14), dp(10), dp(14), dp(10))
                chip.background = roundedBg(CARD, dpf(12f), STROKE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                chip.layoutParams = lp

                val price = makeText("—", 16f, Color.WHITE, true)
                chip.addView(makeText(sym, 11f, MUTED))
                chip.addView(price)
                chip.setOnClickListener { showTradingViewChart(sym) }
                applyBounce(chip)
                tickerContainer.addView(chip)
                priceViews[sym] = price
            }
        }

        for (sym in keys) {
            val tv = priceViews[sym] ?: continue
            val v = prices.num(sym)
            tv.text = formatPrice(sym, v)
            val prev = lastPrices[sym]
            if (prev != null && !v.isNaN() && prev != v) {
                tv.setTextColor(if (v > prev) GREEN else RED) // brief tick-direction flash
                tv.postDelayed({ tv.setTextColor(Color.WHITE) }, 700)
            }
            if (!v.isNaN()) lastPrices[sym] = v
        }
    }

    // =====================================================================
    // Signals
    // =====================================================================

    private fun renderSignals(pending: JSONArray, completed: JSONArray) {
        val sig = pending.toString() + "|" + completed.toString()
        if (sig == lastSignalsSig) return // unchanged: don't rebuild or re-animate
        lastSignalsSig = sig

        pendingContainer.removeAllViews()
        completedContainer.removeAllViews()

        var wins = 0
        var losses = 0
        for (i in 0 until completed.length()) {
            when (completed.optJSONObject(i)?.str("result")?.uppercase(Locale.US)) {
                "WIN" -> wins++
                "LOSS" -> losses++
            }
        }
        tvPendingCount.text = pending.length().toString()
        tvCompletedCount.text = completed.length().toString()
        tvWins.text = wins.toString()
        tvLosses.text = losses.toString()
        val decided = wins + losses
        tvWinRate.text = if (decided > 0)
            String.format(Locale.US, "Win rate: %.0f%% (%d wins of %d decided trades)", wins * 100.0 / decided, wins, decided)
        else "Win rate: — (no WIN/LOSS outcomes yet)"

        if (pending.length() == 0) {
            addEmptyText(pendingContainer, "No active signals right now.\nWaiting for a setup from the backend...")
        } else {
            for (i in 0 until pending.length()) {
                pending.optJSONObject(i)?.let { addSignalCard(pendingContainer, it, true, i) }
            }
        }
        if (completed.length() == 0) {
            addEmptyText(completedContainer, "No signal history yet.")
        } else {
            for (i in 0 until completed.length()) {
                completed.optJSONObject(i)?.let { addSignalCard(completedContainer, it, false, i) }
            }
        }
    }

    /** Returns (riskReward, warning). Never alters values; only flags inconsistencies. */
    private fun checkLevels(dir: String, e: Double, sl: Double, tp: Double): Pair<Double?, String?> {
        if (e.isNaN() || sl.isNaN() || tp.isNaN()) return Pair(null, null)
        val (risk, reward) = when (dir) {
            "BUY" -> Pair(e - sl, tp - e)
            "SELL" -> Pair(sl - e, e - tp)
            else -> return Pair(null, null)
        }
        if (risk <= 0 || reward <= 0) {
            return Pair(null, "Inconsistent levels for $dir: SL/TP on the wrong side of entry")
        }
        return Pair(reward / risk, null)
    }

    private fun pill(text: String, color: Int): TextView {
        val t = makeText(text, 12f, color, true)
        t.letterSpacing = 0.08f
        t.setPadding(dp(12), dp(5), dp(12), dp(5))
        t.background = roundedBg(
            ColorUtils.setAlphaComponent(color, 0x26),
            dpf(20f),
            ColorUtils.setAlphaComponent(color, 0x80)
        )
        return t
    }

    private fun addSignalCard(container: LinearLayout, s: JSONObject, isPending: Boolean, index: Int) {
        val symbol = s.str("symbol").ifEmpty { "UNKNOWN" }
        val direction = s.str("direction").uppercase(Locale.US).ifEmpty { "—" }
        val entry = s.num("entry")
        val sl = s.num("stop_loss")
        val tp = s.num("take_profit")
        val result = s.str("result").uppercase(Locale.US)
        val quality = s.str("quality").ifEmpty { s.str("grade") }.uppercase(Locale.US)
        val status = s.str("status")

        val accent = when (direction) {
            "BUY" -> GREEN
            "SELL" -> RED
            else -> MUTED
        }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.background = roundedBg(CARD, dpf(16f), STROKE)
        card.elevation = dpf(4f)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(12) }

        // Header: symbol, quality (if supplied), direction
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val tvSymbol = makeText(symbol, 20f, Color.WHITE, true)
        tvSymbol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(tvSymbol)
        if (quality.isNotEmpty()) {
            val qColor = when (quality) {
                "A+" -> GREEN
                "A" -> AMBER
                "REJECTED" -> RED
                else -> MUTED
            }
            val q = pill(quality, qColor)
            (LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                .also { it.marginEnd = dp(6); q.layoutParams = it }
            top.addView(q)
        }
        top.addView(pill(direction, accent))
        card.addView(top)

        // Status + time
        val statusText = status.ifEmpty { if (isPending) "PENDING" else "CLOSED" }
        val tvTime = makeText("$statusText · ${formatTime(s.str("created_at"))}", 12f, MUTED)
        tvTime.setPadding(0, dp(4), 0, dp(12))
        card.addView(tvTime)

        // EP / SL / TP
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.HORIZONTAL
        grid.addView(createDataColumn("EP", formatPrice(symbol, entry), Color.WHITE))
        grid.addView(createDataColumn("SL", formatPrice(symbol, sl), RED))
        grid.addView(createDataColumn("TP", formatPrice(symbol, tp), GREEN))
        card.addView(grid)

        // Risk / R:R
        val check = checkLevels(direction, entry, sl, tp)
        val rrBackend = s.str("rr").ifEmpty { s.str("risk_reward") }
        val meta = ArrayList<String>()
        s.str("risk_level").takeIf { it.isNotEmpty() }?.let { meta.add("Risk: $it") }
        if (rrBackend.isNotEmpty()) meta.add("R:R $rrBackend")
        else check.first?.let { meta.add(String.format(Locale.US, "R:R 1:%.2f (calculated)", it)) }
        if (meta.isNotEmpty()) {
            val m = makeText(meta.joinToString("  ·  "), 12f, MUTED)
            m.setPadding(0, dp(12), 0, 0)
            card.addView(m)
        }
        check.second?.let {
            val w = makeText("⚠ $it", 12f, AMBER)
            w.setPadding(0, dp(6), 0, 0)
            card.addView(w)
        }

        // Confluence: only what the engine actually returned
        val conf = s.optJSONArray("confluence")
        if (conf != null && conf.length() > 0) {
            val lines = ArrayList<String>()
            for (i in 0 until conf.length()) {
                val item = conf.opt(i)
                val t = when (item) {
                    is String -> item
                    is JSONObject -> item.str("label").ifEmpty { item.str("text") }.ifEmpty { item.str("name") }
                    else -> ""
                }
                if (t.isNotBlank()) lines.add("• $t")
            }
            if (lines.isNotEmpty()) {
                val h = makeText((if (quality.isNotEmpty()) "$quality " else "") + "CONFLUENCE", 11f, GREEN, true)
                h.letterSpacing = 0.1f
                h.setPadding(0, dp(14), 0, dp(4))
                card.addView(h)
                card.addView(makeText(lines.joinToString("\n"), 13f, Color.WHITE))
            }
        }

        s.str("invalidation").takeIf { it.isNotEmpty() }?.let {
            val t = makeText("Invalidation: $it", 12f, MUTED)
            t.setPadding(0, dp(8), 0, 0)
            card.addView(t)
        }
        s.str("reason").takeIf { it.isNotEmpty() }?.let {
            val t = makeText("Note: $it", 12f, MUTED)
            t.setPadding(0, dp(8), 0, 0)
            card.addView(t)
        }

        // Outcome (completed only, only if backend supplied it)
        if (!isPending && result.isNotEmpty()) {
            val rc = when (result) {
                "WIN" -> GREEN
                "LOSS" -> RED
                else -> MUTED
            }
            val p = pill(result, rc)
            p.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
            card.addView(p)
        }

        // Open Chart (symbol read from this trade)
        val btnChart = makeText("📊 Open Chart", 12f, Color.WHITE, true)
        btnChart.setPadding(dp(14), dp(8), dp(14), dp(8))
        btnChart.background = roundedBg(Color.parseColor("#262626"), dpf(14f), GREEN)
        btnChart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(14) }
        applyBounce(btnChart)
        btnChart.setOnClickListener { showTradingViewChart(symbol) }
        card.addView(btnChart)

        card.alpha = 0f
        card.translationY = dpf(16f)
        container.addView(card)
        card.animate().alpha(1f).translationY(0f)
            .setStartDelay(index * 60L).setDuration(350)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun createDataColumn(label: String, value: String, color: Int): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(makeText(label, 11f, MUTED).also { it.letterSpacing = 0.12f })
        col.addView(makeText(value, 15f, color, true))
        return col
    }

    private fun formatPrice(symbol: String, v: Double): String {
        if (v.isNaN() || v == 0.0) return "—"
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
                return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return isoString
    }

    private fun addEmptyText(container: LinearLayout, text: String) {
        val tv = makeText(text, 14f, Color.parseColor("#757575"))
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(24), 0, dp(24))
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(tv)
    }

    // =====================================================================
    // News (display only) + session clock
    // =====================================================================

    /** Displays news only if the backend actually returns a "news" array. Nothing is invented. */
    private fun renderNews(json: JSONObject) {
        val news = json.optJSONArray("news")
        newsContainer.removeAllViews()
        if (news == null) {
            tvNewsStatus.text = "No news data from the backend yet. A news/economic-calendar service is required (see backend notes)."
            return
        }
        if (news.length() == 0) {
            tvNewsStatus.text = "No news items returned."
            return
        }
        tvNewsStatus.text = "News (as provided by backend):"
        for (i in 0 until news.length()) {
            val n = news.optJSONObject(i) ?: continue
            val title = n.str("title").ifEmpty { n.str("event") }
            if (title.isEmpty()) continue
            val parts = ArrayList<String>()
            for (k in listOf("currency", "impact", "time", "source")) n.str(k).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            val nums = ArrayList<String>()
            for (k in listOf("actual", "forecast", "previous")) n.str(k).takeIf { it.isNotEmpty() }?.let { nums.add("$k: $it") }
            val impl = n.str("implication").ifEmpty { n.str("bias") }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(0, dp(8), 0, dp(4))
            box.addView(makeText(title, 13f, Color.WHITE, true))
            if (parts.isNotEmpty()) box.addView(makeText(parts.joinToString(" · "), 11f, MUTED))
            if (nums.isNotEmpty()) box.addView(makeText(nums.joinToString("  "), 11f, MUTED))
            if (impl.isNotEmpty()) box.addView(makeText(impl, 12f, AMBER))
            newsContainer.addView(box)
        }
    }

    /** Clock-based session context (DST-aware via TimeZone). Not a signal source. Holidays not modelled. */
    private fun updateSessionPanel() {
        val now = System.currentTimeMillis()
        fun cal(tz: String): Calendar =
            Calendar.getInstance(TimeZone.getTimeZone(tz)).also { it.timeInMillis = now }

        val ny = cal("America/New_York")
        val dow = ny.get(Calendar.DAY_OF_WEEK)
        val nyMin = ny.get(Calendar.HOUR_OF_DAY) * 60 + ny.get(Calendar.MINUTE)
        val closed = dow == Calendar.SATURDAY ||
            (dow == Calendar.FRIDAY && nyMin >= 17 * 60) ||
            (dow == Calendar.SUNDAY && nyMin < 17 * 60)

        val utc = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
        if (closed) {
            tvSession.text = "Forex market closed (weekend) · reopens Sunday 17:00 New York time\nUTC now: $utc"
            return
        }
        // name, tz, open hour, close hour (local business hours)
        val defs = listOf(
            Triple("Sydney", "Australia/Sydney", 7..15),
            Triple("Tokyo", "Asia/Tokyo", 9..17),
            Triple("London", "Europe/London", 8..16),
            Triple("New York", "America/New_York", 8..16)
        )
        val active = defs.filter { cal(it.second).get(Calendar.HOUR_OF_DAY) in it.third }.map { it.first }
        val overlap = if ("London" in active && "New York" in active) "\nLondon–New York overlap: typically higher liquidity and volatility." else ""
        tvSession.text = "Active sessions: " + (if (active.isEmpty()) "none (low-liquidity window)" else active.joinToString(", ")) +
            overlap + "\nUTC now: $utc · session context only, not a trade signal"
    }

    // =====================================================================
    // TradingView
    // =====================================================================

    /** Returns a validated TradingView symbol (e.g. "FX:EURUSD"), or "" if unsupported/malformed. */
    fun toTradingViewSymbol(symbol: String): String {
        val s = symbol.trim().uppercase(Locale.US)
        if (s.isEmpty()) return ""
        if (s.contains(":")) return if (TV_PATTERN.matches(s)) s else "" // already prefixed: don't re-map
        val clean = s.replace("/", "").replace("_", "").replace(" ", "")
        EXPLICIT_TV[clean]?.let { return it }
        if (Regex("^[A-Z]{6}$").matches(clean)) {
            val a = clean.substring(0, 3)
            val b = clean.substring(3)
            if (a in FX_CCY && b in FX_CCY && a != b) return "FX:$clean"
        }
        return ""
    }

    private fun buildChartHtml(tvSymbol: String): String {
        val sym = JSONObject.quote(tvSymbol) // tvSymbol is regex-validated; quoted anyway
        return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#121212}
#tradingview_chart{width:100%;height:100%}
</style></head><body>
<div id="tradingview_chart"></div>
<script src="https://s3.tradingview.com/tv.js" onerror="document.title='tv_error'"></script>
<script>
try {
  new TradingView.widget({
    autosize: true, symbol: $sym, interval: "15", timezone: "Etc/UTC",
    theme: "dark", style: "1", locale: "en", enable_publishing: false,
    hide_top_toolbar: false, hide_legend: false, save_image: false,
    container_id: "tradingview_chart"
  });
} catch (e) { document.title = 'tv_error'; }
</script></body></html>
""".trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showTradingViewChart(symbol: String) {
        val tvSymbol = toTradingViewSymbol(symbol)
        if (tvSymbol.isEmpty()) {
            Toast.makeText(this, "Chart not available for \"$symbol\"", Toast.LENGTH_SHORT).show()
            return
        }
        chartDialog?.dismiss()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(BG))
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.fitsSystemWindows = true

        // Header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(12), dp(12), dp(12))
        header.setBackgroundResource(R.drawable.bg_dialog_header)
        val title = makeText("", 16f, Color.WHITE, true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val close = makeText("✕ Close", 13f, Color.WHITE, true)
        close.setPadding(dp(14), dp(8), dp(14), dp(8))
        close.background = roundedBg(Color.parseColor("#262626"), dpf(14f), STROKE)
        applyBounce(close)
        header.addView(title)
        header.addView(close)
        root.addView(header)

        // Instrument chips
        val names = LinkedHashSet<String>()
        names.add(tvSymbol.substringAfter(":"))
        priceViews.keys.forEach { if (toTradingViewSymbol(it).isNotEmpty()) names.add(it.uppercase(Locale.US)) }
        FALLBACK_SYMBOLS.forEach { names.add(it) }

        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.setPadding(dp(12), dp(8), dp(12), dp(8))
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // Chart area
        val frame = FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        val web = WebView(this)
        web.setBackgroundColor(BG)
        val loader = ProgressBar(this)
        loader.layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
        val errorView = makeText("", 14f, AMBER)
        errorView.gravity = Gravity.CENTER
        errorView.setPadding(dp(24), dp(24), dp(24), dp(24))
        errorView.visibility = View.GONE
        errorView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        )
        frame.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(loader)
        frame.addView(errorView)
        root.addView(frame)

        // Footer: feed disclaimer
        val note = makeText(
            "Chart feed is supplied by TradingView and may differ from the dashboard's backend prices; it is not tied to the 5-second polling. No trade levels are overlaid. Viewing a chart does not place trades.",
            10f, MUTED
        )
        note.setPadding(dp(16), dp(6), dp(16), dp(8))
        root.addView(note)

        fun showError(msg: String) {
            loader.visibility = View.GONE
            errorView.text = msg
            errorView.visibility = View.VISIBLE
        }

        web.settings.apply {
            javaScriptEnabled = true // required by the TradingView widget
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loader.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    showError("Couldn't load the chart. Check your internet connection and try again.")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                showError("The chart view stopped unexpectedly. Close and reopen the chart.")
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (t == "tv_error") showError("The TradingView widget failed to load. Check your connection or try again later.")
            }
        }

        val chips = LinkedHashMap<String, TextView>()
        fun styleChips(selected: String) {
            for ((n, v) in chips) {
                val sel = n == selected
                v.setTextColor(if (sel) Color.BLACK else Color.WHITE)
                v.background = roundedBg(if (sel) GREEN else Color.parseColor("#262626"), dpf(18f), if (sel) GREEN else STROKE)
            }
        }
        fun load(name: String) {
            val tv = toTradingViewSymbol(name)
            if (tv.isEmpty()) {
                showError("Chart not available for \"$name\".")
                return
            }
            title.text = "$name · TradingView"
            styleChips(name)
            errorView.visibility = View.GONE
            loader.visibility = View.VISIBLE
            web.loadDataWithBaseURL("https://www.tradingview.com/", buildChartHtml(tv), "text/html", "UTF-8", null)
        }
        for (n in names) {
            if (toTradingViewSymbol(n).isEmpty()) continue
            val chip = makeText(n, 13f, Color.WHITE, true)
            chip.setPadding(dp(14), dp(7), dp(14), dp(7))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8) }
            applyBounce(chip)
            chip.setOnClickListener { load(n) }
            chips[n] = chip
            chipRow.addView(chip)
        }

        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
            if (chartDialog === dialog) chartDialog = null
        }

        dialog.setContentView(root)
        chartDialog = dialog
        dialog.show()
        load(tvSymbol.substringAfter(":"))
    }

    // =====================================================================
    // AI chat (unchanged contract: POST /chat {"message": ...})
    // =====================================================================

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
                if (isFinishing || isDestroyed) return@runOnUiThread
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
        val tv = makeText(text, 14f, if (isUser) Color.BLACK else Color.WHITE)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        if (isUser) {
            tv.background = roundedBg(GREEN, dpf(16f))
        } else {
            tv.background = roundedBg(Color.parseColor("#262626"), dpf(16f), STROKE)
            tv.setTextIsSelectable(true)
        }
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = if (isUser) Gravity.END else Gravity.START
            it.bottomMargin = dp(8)
        }
        chatContainer.addView(tv)
        scrollChatToBottom()
        return tv
    }

    private fun scrollChatToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
