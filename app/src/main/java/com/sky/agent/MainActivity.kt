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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

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

    private val BACKEND_BASE = "https://sky-ai-backend.i96463073.workers.dev"
    private val SIGNAL_WORKER_BASE = "https://sky-ai-backend.i96463073.workers.dev"

    // Live price polling: 5s normally, 2s while the chart is open (cheap endpoint).
    private var livePollMs = 5000L
    // Full candles + SMC analysis refresh while the chart is open.
    private val CHART_SIGNAL_POLL_MS = 10000L

    private val BG = Color.parseColor("#121212")
    private val CARD = Color.parseColor("#1E1E1E")
    private val GREEN = Color.parseColor("#00E676")
    private val RED = Color.parseColor("#FF1744")
    private val AMBER = Color.parseColor("#FFB300")
    private val MUTED = Color.parseColor("#9E9E9E")
    private val STROKE = Color.parseColor("#2E2E2E")

    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)
    private var polling = false
    private var hasData = false
    private var lastOkTime: String? = null
    private var lastSignalsSig: String? = null
    private val priceViews = LinkedHashMap<String, TextView>()
    private val lastPrices = HashMap<String, Double>()
    private var chartDialog: Dialog? = null
    // Set while the chart is open: receives every live tick so the last candle moves in real time.
    private var chartTick: ((String, Double) -> Unit)? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            fetchLive()
            handler.postDelayed(this, livePollMs)
        }
    }

    private val FALLBACK_SYMBOLS = listOf("XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "AUDUSD")
    private val CHART_TIMEFRAMES = listOf("M5", "M15", "H1")
    private val EXPLICIT_TV = mapOf("XAUUSD" to "OANDA:XAUUSD", "XAGUSD" to "OANDA:XAGUSD")
    private val FX_CCY = setOf("USD", "EUR", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF")
    private val TV_PATTERN = Regex("^[A-Z0-9_]{1,20}:[A-Z0-9_.!]{1,20}$")

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
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }

        addEmptyText(pendingContainer, "Connecting to backend…")
        addEmptyText(completedContainer, "Connecting to backend…")
        tvNewsStatus.text = "No news data received from the backend yet."
        updateSessionPanel()
    }

    override fun onResume() {
        super.onResume()
        if (!polling) { polling = true; handler.removeCallbacks(pollRunnable); handler.post(pollRunnable) }
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        chartTick = null
        chartDialog?.dismiss()
        chartDialog = null
        super.onDestroy()
    }

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
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
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

    @SuppressLint("ClickableViewAccessibility")
    private fun applyBounce(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    private fun httpGet(path: String): Pair<Int, String> {
        val c = URL(BACKEND_BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, body)
        } finally { c.disconnect() }
    }

    private fun fetchLive(manual: Boolean = false) {
        if (!inFlight.compareAndSet(false, true)) return
        if (!hasData) setStatus(State.CONNECTING)
        if (manual) { progressBar.visibility = View.VISIBLE; btnRefresh.isEnabled = false }

        thread {
            var code = -1; var body = ""; var err: String? = null
            try {
                var r = httpGet("/api/live")
                if (r.first == 404) r = httpGet("/api/signals")
                code = r.first; body = r.second
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }

            runOnUiThread {
                inFlight.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressBar.visibility = View.GONE; btnRefresh.isEnabled = true
                when {
                    err != null -> onFailure("Network error: $err")
                    code !in 200..299 -> onFailure("HTTP $code")
                    else -> onSuccess(body)
                }
            }
        }
    }

    private fun onSuccess(body: String) {
        val json = try { JSONObject(body) } catch (e: Exception) { onFailure("Malformed response"); return }
        if (json.has("ok") && !json.optBoolean("ok", true)) { onFailure("Backend returned ok=false"); return }

        try {
            json.optJSONObject("live_prices")?.let { updateTicker(it) }
            val pending = json.optJSONArray("pending")
            val completed = json.optJSONArray("completed")
            if (pending != null || completed != null) renderSignals(pending ?: JSONArray(), completed ?: JSONArray())
            renderNews(json)
        } catch (e: Exception) { onFailure("Parse error: ${e.message}"); return }

        hasData = true; lastOkTime = nowHms()
        tvUpdated.text = "Last successful refresh $lastOkTime"
        setStatus(State.LIVE); updateSessionPanel()
    }

    private fun onFailure(msg: String) {
        setStatus(State.OFFLINE)
        tvUpdated.text = if (lastOkTime != null) "Last successful refresh $lastOkTime · $msg" else msg
        if (!hasData) {
            pendingContainer.removeAllViews(); completedContainer.removeAllViews()
            addEmptyText(pendingContainer, msg)
        }
    }

    private enum class State { LIVE, CONNECTING, OFFLINE }

    private fun setStatus(s: State) {
        when (s) {
            State.LIVE -> { tvStatus.text = "● LIVE"; tvStatus.setTextColor(GREEN) }
            State.CONNECTING -> { tvStatus.text = "● CONNECTING"; tvStatus.setTextColor(AMBER) }
            State.OFFLINE -> { tvStatus.text = if (hasData) "● OFFLINE · showing last data" else "● OFFLINE"; tvStatus.setTextColor(RED) }
        }
    }

    private fun updateTicker(prices: JSONObject) {
        val keys = ArrayList<String>()
        val it = prices.keys()
        while (it.hasNext()) keys.add(it.next())

        if (keys.toSet() != priceViews.keys.toSet()) {
            tickerContainer.removeAllViews(); priceViews.clear()
            for (sym in keys) {
                val chip = LinearLayout(this)
                chip.orientation = LinearLayout.VERTICAL
                chip.setPadding(dp(14), dp(10), dp(14), dp(10))
                chip.background = roundedBg(CARD, dpf(12f), STROKE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(8); chip.layoutParams = lp

                val price = makeText("—", 16f, Color.WHITE, true)
                chip.addView(makeText(sym, 11f, MUTED)); chip.addView(price)
                chip.setOnClickListener { showTradingViewChart(sym) }
                applyBounce(chip); tickerContainer.addView(chip)
                priceViews[sym] = price
            }
        }

        for (sym in keys) {
            val tv = priceViews[sym] ?: continue
            val v = prices.num(sym)
            tv.text = formatPrice(sym, v)
            val prev = lastPrices[sym]
            if (prev != null && !v.isNaN() && prev != v) {
                tv.setTextColor(if (v > prev) GREEN else RED)
                tv.postDelayed({ tv.setTextColor(Color.WHITE) }, 700)
            }
            if (!v.isNaN()) {
                lastPrices[sym] = v
                chartTick?.invoke(sym, v)
            }
        }
    }

    private fun renderSignals(pending: JSONArray, completed: JSONArray) {
        val sig = pending.toString() + "|" + completed.toString()
        if (sig == lastSignalsSig) return
        lastSignalsSig = sig

        pendingContainer.removeAllViews(); completedContainer.removeAllViews()

        var wins = 0; var losses = 0
        for (i in 0 until completed.length()) {
            when (completed.optJSONObject(i)?.str("result")?.uppercase(Locale.US)) {
                "WIN" -> wins++; "LOSS" -> losses++
            }
        }
        tvPendingCount.text = pending.length().toString()
        tvCompletedCount.text = completed.length().toString()
        tvWins.text = wins.toString(); tvLosses.text = losses.toString()
        val decided = wins + losses
        tvWinRate.text = if (decided > 0) String.format(Locale.US, "Win rate: %.0f%% (%d wins of %d decided trades)", wins * 100.0 / decided, wins, decided) else "Win rate: — (no WIN/LOSS outcomes yet)"

        if (pending.length() == 0) addEmptyText(pendingContainer, "No active signals right now.\nWaiting for a setup from the backend...")
        else for (i in 0 until pending.length()) pending.optJSONObject(i)?.let { addSignalCard(pendingContainer, it, true, i) }

        if (completed.length() == 0) addEmptyText(completedContainer, "No signal history yet.")
        else for (i in 0 until completed.length()) completed.optJSONObject(i)?.let { addSignalCard(completedContainer, it, false, i) }
    }

    private fun checkLevels(dir: String, e: Double, sl: Double, tp: Double): Pair<Double?, String?> {
        if (e.isNaN() || sl.isNaN() || tp.isNaN()) return Pair(null, null)
        val (risk, reward) = when (dir) {
            "BUY" -> Pair(e - sl, tp - e)
            "SELL" -> Pair(sl - e, e - tp)
            else -> return Pair(null, null)
        }
        if (risk <= 0 || reward <= 0) return Pair(null, "Inconsistent levels for $dir: SL/TP on the wrong side of entry")
        return Pair(reward / risk, null)
    }

    private fun pill(text: String, color: Int): TextView {
        val t = makeText(text, 12f, color, true)
        t.letterSpacing = 0.08f
        t.setPadding(dp(12), dp(5), dp(12), dp(5))
        t.background = roundedBg(ColorUtils.setAlphaComponent(color, 0x26), dpf(20f), ColorUtils.setAlphaComponent(color, 0x80))
        return t
    }

    private fun addSignalCard(container: LinearLayout, s: JSONObject, isPending: Boolean, index: Int) {
        val symbol = s.str("symbol").ifEmpty { "UNKNOWN" }
        val direction = s.str("direction").uppercase(Locale.US).ifEmpty { "—" }
        val entry = s.num("entry"); val sl = s.num("stop_loss"); val tp = s.num("take_profit")
        val result = s.str("result").uppercase(Locale.US)
        val quality = s.str("quality").ifEmpty { s.str("grade") }.uppercase(Locale.US)
        val status = s.str("status")
        val accent = when (direction) { "BUY" -> GREEN; "SELL" -> RED; else -> MUTED }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.background = roundedBg(CARD, dpf(16f), STROKE)
        card.elevation = dpf(4f)
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(12) }

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        val tvSymbol = makeText(symbol, 20f, Color.WHITE, true)
        tvSymbol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(tvSymbol)

        if (quality.isNotEmpty()) {
            val qColor = when (quality) { "A+" -> GREEN; "A" -> AMBER; "REJECTED" -> RED; else -> MUTED }
            val q = pill(quality, qColor)
            q.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(6) }
            top.addView(q)
        }
        top.addView(pill(direction, accent))
        card.addView(top)

        val statusText = status.ifEmpty { if (isPending) "PENDING" else "CLOSED" }
        val tvTime = makeText("$statusText · ${formatTime(s.str("created_at"))}", 12f, MUTED)
        tvTime.setPadding(0, dp(4), 0, dp(12))
        card.addView(tvTime)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.HORIZONTAL
        grid.addView(createDataColumn("EP", formatPrice(symbol, entry), Color.WHITE))
        grid.addView(createDataColumn("SL", formatPrice(symbol, sl), RED))
        grid.addView(createDataColumn("TP", formatPrice(symbol, tp), GREEN))
        card.addView(grid)

        val check = checkLevels(direction, entry, sl, tp)
        val rrBackend = s.str("rr").ifEmpty { s.str("risk_reward") }
        val meta = ArrayList<String>()
        s.str("risk_level").takeIf { it.isNotEmpty() }?.let { meta.add("Risk: $it") }
        if (rrBackend.isNotEmpty()) meta.add("R:R $rrBackend") else check.first?.let { meta.add(String.format(Locale.US, "R:R 1:%.2f (calculated)", it)) }

        if (meta.isNotEmpty()) {
            val m = makeText(meta.joinToString("  ·  "), 12f, MUTED)
            m.setPadding(0, dp(12), 0, 0); card.addView(m)
        }
        check.second?.let { val w = makeText("⚠ $it", 12f, AMBER); w.setPadding(0, dp(6), 0, 0); card.addView(w) }

        val conf = s.optJSONArray("confluence")
        if (conf != null && conf.length() > 0) {
            val lines = ArrayList<String>()
            for (i in 0 until conf.length()) {
                val item = conf.opt(i)
                val t = when (item) { is String -> item; is JSONObject -> item.str("label").ifEmpty { item.str("text") }.ifEmpty { item.str("name") }; else -> "" }
                if (t.isNotBlank()) lines.add("• $t")
            }
            if (lines.isNotEmpty()) {
                val h = makeText((if (quality.isNotEmpty()) "$quality " else "") + "CONFLUENCE", 11f, GREEN, true)
                h.letterSpacing = 0.1f; h.setPadding(0, dp(14), 0, dp(4)); card.addView(h)
                card.addView(makeText(lines.joinToString("\n"), 13f, Color.WHITE))
            }
        }

        s.str("invalidation").takeIf { it.isNotEmpty() }?.let { val t = makeText("Invalidation: $it", 12f, MUTED); t.setPadding(0, dp(8), 0, 0); card.addView(t) }
        s.str("reason").takeIf { it.isNotEmpty() }?.let { val t = makeText("Note: $it", 12f, MUTED); t.setPadding(0, dp(8), 0, 0); card.addView(t) }

        if (!isPending && result.isNotEmpty()) {
            val rc = when (result) { "WIN" -> GREEN; "LOSS" -> RED; else -> MUTED }
            val p = pill(result, rc)
            p.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) }
            card.addView(p)
        }

        val btnChart = makeText("📊 Open Chart", 12f, Color.WHITE, true)
        btnChart.setPadding(dp(14), dp(8), dp(14), dp(8))
        btnChart.background = roundedBg(Color.parseColor("#262626"), dpf(14f), GREEN)
        btnChart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(14) }
        applyBounce(btnChart); btnChart.setOnClickListener { showTradingViewChart(symbol) }; card.addView(btnChart)

        card.alpha = 0f; card.translationY = dpf(16f); container.addView(card)
        card.animate().alpha(1f).translationY(0f).setStartDelay(index * 60L).setDuration(350).setInterpolator(DecelerateInterpolator()).start()
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
        if (v.isNaN() || v == 0.0 || v.isInfinite()) return "—"
        val decimals = when { symbol.contains("XAU") -> 2; symbol.contains("JPY") -> 3; else -> 5 }
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    private fun formatTime(isoString: String): String {
        if (isoString.isEmpty()) return ""
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in patterns) {
            try {
                val parser = SimpleDateFormat(p, Locale.US); parser.timeZone = TimeZone.getTimeZone("UTC")
                val date = parser.parse(isoString) ?: continue
                return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)
            } catch (_: Exception) {}
        }
        return isoString
    }

    private fun addEmptyText(container: LinearLayout, text: String) {
        val tv = makeText(text, 14f, Color.parseColor("#757575"))
        tv.gravity = Gravity.CENTER; tv.setPadding(0, dp(24), 0, dp(24))
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        container.addView(tv)
    }

    private fun renderNews(json: JSONObject) {
        val news = json.optJSONArray("news")
        newsContainer.removeAllViews()
        if (news == null) { tvNewsStatus.text = "No news data from the backend yet."; return }
        if (news.length() == 0) { tvNewsStatus.text = "No news items returned."; return }
        tvNewsStatus.text = "News (as provided by backend):"
        for (i in 0 until news.length()) {
            val n = news.optJSONObject(i) ?: continue
            val title = n.str("title").ifEmpty { n.str("event") }
            if (title.isEmpty()) continue
            val parts = ArrayList<String>()
            for (k in listOf("currency", "impact", "time", "source")) n.str(k).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(0, dp(8), 0, dp(4))
            box.addView(makeText(title, 13f, Color.WHITE, true))
            if (parts.isNotEmpty()) box.addView(makeText(parts.joinToString(" · "), 11f, MUTED))
            newsContainer.addView(box)
        }
    }

    private fun updateSessionPanel() {
        val now = System.currentTimeMillis()
        fun cal(tz: String): Calendar = Calendar.getInstance(TimeZone.getTimeZone(tz)).also { it.timeInMillis = now }
        val utc = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
        val ny = cal("America/New_York")
        val dow = ny.get(Calendar.DAY_OF_WEEK)
        val nyMin = ny.get(Calendar.HOUR_OF_DAY) * 60 + ny.get(Calendar.MINUTE)
        val closed = dow == Calendar.SATURDAY || (dow == Calendar.FRIDAY && nyMin >= 17 * 60) || (dow == Calendar.SUNDAY && nyMin < 17 * 60)
        if (closed) { tvSession.text = "Forex market closed (weekend)\nUTC now: $utc"; return }
        val defs = listOf(Triple("Sydney", "Australia/Sydney", 7..15), Triple("Tokyo", "Asia/Tokyo", 9..17), Triple("London", "Europe/London", 8..16), Triple("New York", "America/New_York", 8..16))
        val active = defs.filter { cal(it.second).get(Calendar.HOUR_OF_DAY) in it.third }.map { it.first }
        val overlap = if ("London" in active && "New York" in active) "\nLondon–New York overlap active." else ""
        tvSession.text = "Active sessions: " + (if (active.isEmpty()) "none" else active.joinToString(", ")) + overlap + "\nUTC now: $utc"
    }

    fun toTradingViewSymbol(symbol: String): String {
        val s = symbol.trim().uppercase(Locale.US)
        if (s.isEmpty()) return ""
        if (s.contains(":")) return if (TV_PATTERN.matches(s)) s else ""
        val clean = s.replace("/", "").replace("_", "").replace(" ", "")
        EXPLICIT_TV[clean]?.let { return it }
        if (Regex("^[A-Z]{6}$").matches(clean)) {
            val a = clean.substring(0, 3); val b = clean.substring(3)
            if (a in FX_CCY && b in FX_CCY && a != b) return "FX:$clean"
        }
        return ""
    }

    // Loads the real candles and SMC decision data from the existing Cloudflare Worker.
    private fun loadSignalData(symbol: String, timeframe: String, callback: (JSONObject?, String?) -> Unit) {
        thread {
            var connection: HttpURLConnection? = null
            try {
                val s = URLEncoder.encode(symbol, "UTF-8")
                val tf = URLEncoder.encode(timeframe, "UTF-8")
                val endpoint = "$SIGNAL_WORKER_BASE/test-signal?symbol=$s&timeframe=$tf"
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(180)}")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Signal endpoint returned ok=false"))
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) callback(json, null)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) callback(null, e.message ?: "Unable to load backend candles")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Professional chart page (TradingView Lightweight Charts, pinned version).
     *  - FVG / order-block zones are DRAWN on the chart as shaded boxes via a custom canvas primitive.
     *  - Real-time: window.tick(price) moves the last candle; window.renderSignal(json) only appends/updates
     *    the newest candles (no full redraw), so it stays light on a phone.
     *  - No template literals / dollar signs are used in the JS because this is a Kotlin raw string.
     */
    private fun buildChartHtml(): String = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0;height:100%;overflow:hidden;background:#121212;color:#e6e8eb;font:12px/1.4 Roboto,-apple-system,'Segoe UI',sans-serif}
body{display:flex;flex-direction:column}
#wrap{position:relative;flex:1;min-height:0}
#chart{position:absolute;left:0;top:0;right:0;bottom:0}
#lg{position:absolute;top:6px;left:8px;z-index:5;pointer-events:none;font-size:11px;color:#b5b9bf;text-shadow:0 1px 2px #000}
#lg b{color:#fff;font-size:13px;margin-right:6px}
#lg span{margin-right:8px;white-space:nowrap}
.dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:#26a69a;margin-right:4px;animation:p 1.6s ease-in-out infinite}
@keyframes p{0%,100%{opacity:1}50%{opacity:.25}}
#key{position:absolute;left:8px;bottom:6px;z-index:5;pointer-events:none;font-size:10px;color:#9aa0a6;display:flex;gap:10px}
#key i{display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:4px;vertical-align:-1px}
#panel{background:#171a1e;border-top:1px solid #2a2f35;max-height:36%;overflow:auto;padding:0 12px 10px}
#grab{padding:7px 0 6px;text-align:center}
#grab i{display:inline-block;width:36px;height:4px;border-radius:2px;background:#3a4047}
#head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}
.badge{padding:5px 11px;border-radius:14px;font-weight:700;font-size:12px;letter-spacing:.3px;border:1px solid}
.amb{color:#ffb300;background:rgba(255,179,0,.12);border-color:rgba(255,179,0,.45)}
.grn{color:#26a69a;background:rgba(38,166,154,.14);border-color:rgba(38,166,154,.5)}
.red{color:#ef5350;background:rgba(239,83,80,.14);border-color:rgba(239,83,80,.5)}
#px{font-size:18px;font-weight:700;font-variant-numeric:tabular-nums}
#panel.min #more{display:none}
#stats{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-bottom:8px}
.c{background:#1e2227;border:1px solid #2a2f35;border-radius:10px;padding:6px 8px;min-width:0}
.c b{display:block;font-weight:500;font-size:10px;color:#8b9198;margin-bottom:1px}
.c span{display:block;font-size:12px;color:#e6e8eb;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#tog{display:flex;gap:6px;margin-bottom:8px;flex-wrap:wrap}
.tg{padding:5px 10px;border-radius:14px;border:1px solid #333a42;color:#8b9198;font-size:11px}
.tg.on{color:#fff;border-color:#26a69a;background:rgba(38,166,154,.16)}
#why{font-size:12px;color:#c2c6cc;line-height:1.5}
#why div{padding-left:10px;position:relative}
#why div:before{content:'';position:absolute;left:0;top:7px;width:4px;height:4px;border-radius:50%;background:#6b727a}
</style>
<script src="https://unpkg.com/lightweight-charts@5.0.8/dist/lightweight-charts.standalone.production.js"></script>
</head>
<body>
<div id="wrap">
 <div id="chart"></div>
 <div id="lg"></div>
 <div id="key"><span><i style="background:rgba(38,166,154,.6)"></i>Bull FVG</span><span><i style="background:rgba(239,83,80,.6)"></i>Bear FVG</span><span><i style="background:rgba(255,179,0,.6)"></i>OB / zone</span></div>
</div>
<div id="panel">
 <div id="grab"><i></i></div>
 <div id="head"><span id="badge" class="badge amb">LOADING</span><span id="px"></span></div>
 <div id="more">
  <div id="stats"></div>
  <div id="tog"><span class="tg on" data-k="filled">Filled FVG</span><span class="tg on" data-k="ob">Order blocks</span><span class="tg on" data-k="lv">Levels</span></div>
  <div id="why"></div>
 </div>
</div>
<script>
(function(){
 var g=function(i){return document.getElementById(i);};
 var el=g('chart'),chart=null,candles=null,zp=null,lines=[],bars=[],zones=[],fresh=true,P=2,levels=null;
 var opt={filled:true,ob:true,lv:true};
 var UP='#26a69a',DN='#ef5350';
 function fin(v){return typeof v==='number'&&isFinite(v);}
 function esc(s){return String(s==null?'':s).replace(/[&<>]/g,function(c){return c==='&'?'&amp;':(c==='<'?'&lt;':'&gt;');});}
 function num(o,ks){for(var i=0;i<ks.length;i++){var v=o&&o[ks[i]];if(fin(v))return v;if(typeof v==='string'&&v.trim()!==''&&isFinite(Number(v)))return Number(v);}return null;}
 function sec(v){if(v==null)return null;if(typeof v==='number')return v>100000000000?Math.floor(v/1000):v;var p=Date.parse(v);return isNaN(p)?null:Math.floor(p/1000);}
 function fx(v){return fin(v)?v.toFixed(P):'—';}
 function precFor(sym){sym=String(sym||'');return sym.indexOf('JPY')>=0?3:(sym.indexOf('XAU')>=0?2:5);}

 function makePrimitive(){
  var self={z:[],chart:null,series:null,req:null};
  var view={zOrder:function(){return 'bottom';},renderer:function(){return {draw:function(t){t.useBitmapCoordinateSpace(function(s){paint(s);});}};}};
  function paint(s){
   if(!self.chart||!self.series||!self.z.length)return;
   var ctx=s.context,hr=s.horizontalPixelRatio,vr=s.verticalPixelRatio,ts=self.chart.timeScale(),W=s.mediaSize.width;
   var n=bars.length,px=12;
   if(n>1){var a=ts.timeToCoordinate(bars[n-1].time),b=ts.timeToCoordinate(bars[n-2].time);if(a!=null&&b!=null&&a!==b)px=Math.abs(a-b);}
   for(var i=0;i<self.z.length;i++){
    var q=self.z[i];
    if(q.kind==='OB'&&!opt.ob)continue;
    if(q.filled&&!opt.filled)continue;
    var y1=self.series.priceToCoordinate(q.top),y2=self.series.priceToCoordinate(q.bottom);
    if(y1==null||y2==null)continue;
    var x1=q.t1==null?0:ts.timeToCoordinate(q.t1);
    if(x1==null){if(bars.length&&q.t1<bars[0].time)x1=0;else continue;}
    var x2=q.filled?x1+px*10:W;
    if(x2<0||x1>W)continue;
    var l=Math.max(0,x1),top=Math.min(y1,y2),h=Math.max(1,Math.abs(y2-y1));
    var c=q.dir==='bull'?'38,166,154':(q.dir==='bear'?'239,83,80':'255,179,0');
    var X=Math.round(l*hr),Y=Math.round(top*vr),WW=Math.round((x2-l)*hr),HH=Math.round(h*vr);
    ctx.fillStyle='rgba('+c+','+(q.filled?0.07:0.2)+')';
    ctx.fillRect(X,Y,WW,HH);
    ctx.strokeStyle='rgba('+c+','+(q.filled?0.28:0.75)+')';
    ctx.lineWidth=Math.max(1,Math.round(hr));
    ctx.strokeRect(X+0.5,Y+0.5,WW,HH);
    if(!q.filled){
     ctx.fillStyle='rgba('+c+',1)';
     ctx.font='bold '+Math.round(10*vr)+'px sans-serif';
     ctx.fillText(q.kind+(q.dir==='bull'?' \u25B2':(q.dir==='bear'?' \u25BC':'')),X+Math.round(5*hr),Y+Math.round(12*vr));
    }
   }
  }
  self.attached=function(p){self.chart=p.chart;self.series=p.series;self.req=p.requestUpdate;};
  self.detached=function(){self.chart=null;self.series=null;self.req=null;};
  self.updateAllViews=function(){};
  self.paneViews=function(){return [view];};
  self.set=function(z){self.z=z;if(self.req)self.req();};
  self.redraw=function(){if(self.req)self.req();};
  return self;
 }

 function init(){
  if(!window.LightweightCharts){g('badge').textContent='CHART LIBRARY FAILED';g('why').textContent='Check your internet connection and press Refresh.';return;}
  chart=LightweightCharts.createChart(el,{
   autoSize:true,
   layout:{background:{type:'solid',color:'#121212'},textColor:'#9aa0a6',fontSize:11},
   grid:{vertLines:{visible:false},horzLines:{color:'#1c1f23'}},
   rightPriceScale:{borderColor:'#2a2f35',scaleMargins:{top:0.12,bottom:0.1}},
   timeScale:{borderColor:'#2a2f35',timeVisible:true,secondsVisible:false,rightOffset:6,barSpacing:7},
   crosshair:{mode:LightweightCharts.CrosshairMode.Normal,vertLine:{color:'#4a5058',labelBackgroundColor:'#2a2f35'},horzLine:{color:'#4a5058',labelBackgroundColor:'#2a2f35'}},
   handleScroll:{mouseWheel:true,pressedMouseMove:true,horzTouchDrag:true,vertTouchDrag:false},
   handleScale:{mouseWheel:true,pinch:true,axisPressedMouseMove:true}
  });
  candles=chart.addSeries(LightweightCharts.CandlestickSeries,{
   upColor:UP,downColor:DN,borderVisible:false,wickUpColor:UP,wickDownColor:DN,
   priceLineVisible:true,priceLineStyle:2,priceLineWidth:1
  });
  zp=makePrimitive();
  candles.attachPrimitive(zp);
  chart.subscribeCrosshairMove(function(p){
   var d=p&&p.seriesData?p.seriesData.get(candles):null;
   legend(d||bars[bars.length-1]);
  });
  var tg=document.querySelectorAll('.tg');
  for(var i=0;i<tg.length;i++){(function(e){e.addEventListener('click',function(){
   var k=e.getAttribute('data-k');opt[k]=!opt[k];e.className='tg'+(opt[k]?' on':'');
   if(k==='lv')drawLevels();else zp.redraw();
  });})(tg[i]);}
  g('grab').addEventListener('click',function(){var p=g('panel');p.className=p.className==='min'?'':'min';});
 }

 var symName='',tfName='';
 function legend(b){
  if(!b)return;
  var up=b.close>=b.open,c=up?UP:DN;
  g('lg').innerHTML='<b>'+esc(symName)+'</b><span><i class="dot"></i>'+esc(tfName)+'</span>'+
   '<span style="color:'+c+'">O '+fx(b.open)+'</span><span style="color:'+c+'">H '+fx(b.high)+'</span><span style="color:'+c+'">L '+fx(b.low)+'</span><span style="color:'+c+'">C '+fx(b.close)+'</span>';
 }
 function priceUi(p){
  var e=g('px');e.textContent=fx(p);
  var b=bars[bars.length-1];if(b)e.style.color=b.close>=b.open?UP:DN;
 }
 function clearLines(){for(var i=0;i<lines.length;i++){try{candles.removePriceLine(lines[i]);}catch(e){}}lines=[];}
 function addLine(price,color,title,style){
  if(!fin(price))return;
  lines.push(candles.createPriceLine({price:price,color:color,lineWidth:1,lineStyle:style,axisLabelVisible:true,title:title}));
 }
 function drawLevels(){
  clearLines();
  if(!opt.lv||!levels)return;
  addLine(levels.entry,'#40c4ff','ENTRY',0);
  addLine(levels.sl,'#ef5350','SL',2);
  addLine(levels.tp,'#26a69a','TP',2);
 }

 function collect(value,out,depth){
  if(depth>7||value==null||typeof value!=='object')return;
  if(Array.isArray(value)){for(var i=0;i<value.length;i++)collect(value[i],out,depth+1);return;}
  var ty=String(value.type||'').toUpperCase(),lb=String(value.label||'').toLowerCase();
  var isF=ty.indexOf('FVG')>=0||lb.indexOf('fair value gap')>=0;
  var isO=ty.indexOf('ORDER')>=0||ty==='OB'||ty.indexOf('_OB')>=0||lb.indexOf('order block')>=0;
  if((isF||isO)&&fin(value.top)&&fin(value.bottom)){
   var d=String(value.direction||value.bias||value.type||'').toLowerCase();
   var st=String(value.status||'').toLowerCase();
   out.push({kind:isF?'FVG':'OB',top:value.top,bottom:value.bottom,dir:d.indexOf('bull')>=0?'bull':(d.indexOf('bear')>=0?'bear':'neutral'),
    filled:st.indexOf('filled')>=0||(st.indexOf('mitigated')>=0&&st.indexOf('unmit')<0),
    t1:sec(value.time||value.startTime||value.createdAt),id:String(value.id||'')});
  }
  var ks=Object.keys(value);
  for(var j=0;j<ks.length;j++){var v=value[ks[j]];if(v&&typeof v==='object')collect(v,out,depth+1);}
 }

 function merge(nb){
  var needFull=fresh||!bars.length||nb[0].time>bars[bars.length-1].time;
  if(needFull){
   candles.setData(nb);
   if(fresh){var n=nb.length;chart.timeScale().setVisibleLogicalRange({from:Math.max(0,n-90),to:n+6});}
   fresh=false;
  }else{
   var lt=bars[bars.length-1].time;
   for(var i=0;i<nb.length;i++){if(nb[i].time>=lt)candles.update(nb[i]);}
  }
  bars=nb;
 }

 function render(data){
  try{
   var market=data.market||{};
   var raw=Array.isArray(market.candles)?market.candles:[];
   var map={},keys=[];
   raw.forEach(function(c){
    var t=sec(c.time);if(t==null)return;
    var o=Number(c.open),h=Number(c.high),l=Number(c.low),cl=Number(c.close);
    if(![o,h,l,cl].every(isFinite))return;
    if(!map[t])keys.push(t);
    map[t]={time:t,open:o,high:h,low:l,close:cl};
   });
   keys.sort(function(a,b){return a-b;});
   var nb=keys.map(function(k){return map[k];});
   if(!nb.length)throw new Error('Backend response contains no usable market.candles.');
   symName=String(data.symbol||symName||'');tfName=String(market.timeframe||data.timeframe||tfName||'');
   P=precFor(symName);
   candles.applyOptions({priceFormat:{type:'price',precision:P,minMove:1/Math.pow(10,P)}});
   merge(nb);

   var setup=data.setup||data.signal||data.decision||(data.analysis&&data.analysis.setup)||{};
   var decision=String(setup.decision||data.status||'NO_TRADE').toUpperCase();
   var direction=String(setup.direction||setup.side||'').toUpperCase();
   var noTrade=decision==='NO_TRADE'||decision==='WAIT'||decision==='NONE';
   var entry=num(setup,['entry','entryPrice','entry_price','plannedEntry']);
   var sl=num(setup,['stopLoss','stop_loss','sl']);
   var tp=num(setup,['takeProfit','take_profit','tp','tp1','target']);
   levels=noTrade?null:{entry:entry,sl:sl,tp:tp};
   drawLevels();

   var all=[];
   collect(data.analysis||{},all,0);collect(data.annotations||[],all,0);
   if(setup.candidateZone&&fin(setup.candidateZone.top)&&fin(setup.candidateZone.bottom)){
    var cz=setup.candidateZone;
    all.push({kind:'ZONE',top:cz.top,bottom:cz.bottom,dir:direction==='BUY'?'bull':(direction==='SELL'?'bear':'neutral'),filled:false,t1:sec(cz.time),id:'cand'});
   }
   var seen={},uniq=[];
   all.forEach(function(z){var k=z.kind+'|'+z.id+'|'+z.t1+'|'+z.top+'|'+z.bottom;if(!seen[k]){seen[k]=1;uniq.push(z);}});
   uniq.sort(function(a,b){return (a.t1||0)-(b.t1||0);});
   var open=uniq.filter(function(z){return !z.filled;}).slice(-8);
   var done=uniq.filter(function(z){return z.filled;}).slice(-6);
   zones=open.concat(done);
   zp.set(zones);

   var cur=num(market,['currentPrice']);if(cur==null)cur=nb[nb.length-1].close;
   var bd=g('badge');
   bd.textContent=noTrade?'NO TRADE \u00B7 WAIT':(direction||decision)+' SETUP';
   bd.className='badge '+(noTrade?'amb':(direction==='SELL'?'red':'grn'));
   priceUi(cur);legend(nb[nb.length-1]);

   var trend=data.analysis&&data.analysis.trend;
   if(trend&&typeof trend==='object')trend=trend.direction||trend.bias||trend.trend||JSON.stringify(trend);
   var atr=data.analysis&&data.analysis.atr;
   if(atr&&typeof atr==='object')atr=fin(atr.value)?atr.value.toFixed(2):JSON.stringify(atr);
   var score=setup.score!=null?(setup.score+(setup.maxScore!=null?' / '+setup.maxScore:'')):null;
   var cells=[['Confidence',setup.confidenceLabel||setup.confidence],['Score',score],['Trend',trend],['ATR',atr],['Entry',setup.entryStatus],
    ['Open FVG',open.filter(function(z){return z.kind==='FVG';}).length]];
   if(!noTrade){cells.push(['Entry price',fin(entry)?fx(entry):null],['Stop loss',fin(sl)?fx(sl):null],['Take profit',fin(tp)?fx(tp):null]);}
   g('stats').innerHTML=cells.filter(function(c){return c[1]!=null&&c[1]!=='';}).map(function(c){return '<div class="c"><b>'+esc(c[0])+'</b><span>'+esc(c[1])+'</span></div>';}).join('');

   var reasons=Array.isArray(setup.reasons)?setup.reasons:(Array.isArray(setup.evidence)?setup.evidence:[]);
   var html=reasons.map(function(x){return '<div>'+esc(typeof x==='string'?x:JSON.stringify(x))+'</div>';}).join('');
   if(noTrade&&!html)html='<div>No confirmed setup from the backend. No entry, SL or TP is invented.</div>';
   g('why').innerHTML=html;
  }catch(e){g('badge').textContent='RENDER ERROR';g('badge').className='badge red';g('why').textContent=String(e&&e.message||e);}
 }

 // Real-time tick from the live price feed: moves only the last candle.
 window.tick=function(p){
  if(!candles||!bars.length||!fin(p))return;
  var b=bars[bars.length-1];
  b.close=p;if(p>b.high)b.high=p;if(p<b.low)b.low=p;
  candles.update(b);
  priceUi(p);legend(b);
 };
 window.resetChart=function(){
  fresh=true;bars=[];zones=[];levels=null;
  if(candles){candles.setData([]);clearLines();zp.set([]);}
  g('badge').textContent='LOADING';g('badge').className='badge amb';g('px').textContent='';g('stats').innerHTML='';g('why').innerHTML='';
 };
 window.renderSignal=render;
 init();
})();
</script>
</body></html>
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun showTradingViewChart(symbol: String) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US).ifEmpty { "XAUUSD" }
        chartDialog?.dismiss()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#181818"))
        }
        val title = makeText("$cleanSymbol · SKY AI SIGNALS", 15f, Color.WHITE, true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val refresh = makeText("↻ Refresh", 12f, GREEN, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBg(Color.parseColor("#262626"), dpf(12f), STROKE)
        }
        val close = makeText("✕", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(Color.parseColor("#262626"), dpf(12f), STROKE)
        }
        header.addView(title)
        header.addView(refresh, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(8) })
        header.addView(close)
        root.addView(header)

        // One compact row: timeframes first, a divider, then symbols.
        val symbols = LinkedHashSet<String>()
        symbols.add(cleanSymbol)
        priceViews.keys.forEach { symbols.add(it.uppercase(Locale.US)) }
        FALLBACK_SYMBOLS.forEach { symbols.add(it) }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#181818"))
            addView(chipRow)
        }
        root.addView(chipScroll)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val web = WebView(this).apply { setBackgroundColor(BG) }
        val loader = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER)
        }
        val error = makeText("", 13f, AMBER).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(20), dp(22), dp(20))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(loader)
        frame.addView(error)
        root.addView(frame)

        val footer = makeText("Live · candles & SMC from your SKY AI backend · Drag to pan · Pinch to zoom", 10f, MUTED).apply {
            setPadding(dp(12), dp(5), dp(12), dp(6))
        }
        root.addView(footer)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        var chartPageReady = false
        var queuedChartJson: JSONObject? = null
        fun injectChartData(json: JSONObject) {
            if (!chartPageReady) {
                queuedChartJson = json
                return
            }
            val encoded = JSONObject.quote(json.toString())
            web.evaluateJavascript("window.renderSignal && window.renderSignal(JSON.parse($encoded));", null)
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loader.visibility = View.GONE
                chartPageReady = true
                queuedChartJson?.let {
                    queuedChartJson = null
                    injectChartData(it)
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, e: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    loader.visibility = View.GONE
                    error.text = "Chart page failed to load. Check your internet connection."
                    error.visibility = View.VISIBLE
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }

        val chips = LinkedHashMap<String, TextView>()
        val tfChips = LinkedHashMap<String, TextView>()
        var selectedSymbol = cleanSymbol
        var selectedTf = "M15"
        val signalBusy = AtomicBoolean(false)

        fun styleChips() {
            chips.forEach { (name, chip) ->
                val selected = name == selectedSymbol
                chip.setTextColor(if (selected) Color.BLACK else Color.WHITE)
                chip.background = roundedBg(if (selected) GREEN else Color.parseColor("#262626"), dpf(18f), if (selected) GREEN else STROKE)
            }
            tfChips.forEach { (name, chip) ->
                val selected = name == selectedTf
                chip.setTextColor(if (selected) GREEN else MUTED)
                chip.background = roundedBg(if (selected) ColorUtils.setAlphaComponent(GREEN, 0x22) else Color.TRANSPARENT, dpf(18f), if (selected) GREEN else STROKE)
            }
        }

        // reset = true: user changed symbol/timeframe/refresh -> clear chart and show loader.
        // reset = false: silent background refresh, only the newest candles + zones are updated.
        fun loadData(reset: Boolean) {
            if (reset) {
                selectedSymbol = selectedSymbol.uppercase(Locale.US)
                title.text = "$selectedSymbol · SKY AI SIGNALS"
                styleChips()
                loader.visibility = View.VISIBLE
                error.visibility = View.GONE
                queuedChartJson = null
                if (chartPageReady) web.evaluateJavascript("window.resetChart && window.resetChart();", null)
            } else if (signalBusy.get()) {
                return
            }
            signalBusy.set(true)
            val reqSymbol = selectedSymbol
            val reqTf = selectedTf
            loadSignalData(reqSymbol, reqTf) { json, message ->
                val current = reqSymbol == selectedSymbol && reqTf == selectedTf
                if (current) signalBusy.set(false)
                if (current && dialog.isShowing && !isFinishing && !isDestroyed) {
                    loader.visibility = View.GONE
                    if (json == null) {
                        if (reset) {
                            error.text = "Couldn't load backend candles.\n$message"
                            error.visibility = View.VISIBLE
                        }
                    } else {
                        error.visibility = View.GONE
                        injectChartData(json)
                    }
                }
            }
        }

        // Timeframe chips
        for (tf in CHART_TIMEFRAMES) {
            val chip = makeText(tf, 12f, MUTED, true).apply {
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(6) }
            }
            applyBounce(chip)
            chip.setOnClickListener { selectedTf = tf; loadData(true) }
            tfChips[tf] = chip
            chipRow.addView(chip)
        }
        val divider = View(this).apply {
            setBackgroundColor(STROKE)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).also { it.marginStart = dp(2); it.marginEnd = dp(10) }
        }
        chipRow.addView(divider)

        // Symbol chips
        for (name in symbols) {
            val chip = makeText(name, 12f, Color.WHITE, true).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(7) }
            }
            applyBounce(chip)
            chip.setOnClickListener {
                selectedSymbol = name
                loadData(true)
            }
            chips[name] = chip
            chipRow.addView(chip)
        }

        // Background refresh of candles + SMC zones every few seconds while the chart is open.
        val signalPoll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || isFinishing || isDestroyed) return
                loadData(false)
                handler.postDelayed(this, CHART_SIGNAL_POLL_MS)
            }
        }

        // Live ticks from the cheap /api/live poll move the last candle in real time.
        chartTick = { sym, price ->
            if (chartPageReady && dialog.isShowing && sym.equals(selectedSymbol, ignoreCase = true) && !price.isNaN()) {
                web.evaluateJavascript("window.tick && window.tick(" + String.format(Locale.US, "%.5f", price) + ")", null)
            }
        }

        refresh.setOnClickListener { loadData(true) }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            handler.removeCallbacks(signalPoll)
            if (chartDialog === dialog) {
                chartDialog = null
                chartTick = null
                livePollMs = 5000L
            }
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(BG))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        chartDialog = dialog
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        web.loadDataWithBaseURL("https://local.skyai/", buildChartHtml(), "text/html", "UTF-8", null)

        // Faster live-price polling while the chart is visible.
        livePollMs = 2000L
        handler.removeCallbacks(pollRunnable)
        if (polling) handler.post(pollRunnable)

        loadData(true)
        handler.postDelayed(signalPoll, CHART_SIGNAL_POLL_MS)
    }

    private fun sendChat() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.setText(""); chatPanel.visibility = View.VISIBLE
        addBubble(text, isUser = true)
        val typing = addBubble("Sky AI is thinking…", isUser = false)
        btnSend.isEnabled = false

        thread {
            val reply: String = try {
                val c = URL("$BACKEND_BASE/chat").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(JSONObject().put("message", text).toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                val body = s?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                if (code in 200..299) extractReply(body) else "Server Error: $code"
            } catch (e: Exception) { "Network Error: ${e.message}" }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                typing.text = reply; btnSend.isEnabled = true; scrollChatToBottom()
            }
        }
    }

    private fun extractReply(body: String): String {
        if (body.isBlank()) return "(empty reply)"
        return try {
            val j = JSONObject(body)
            val keys = listOf("reply", "response", "text", "message", "answer", "content", "output")
            for (k in keys) { val v = j.optString(k, ""); if (v.isNotBlank() && v != "null") return v }
            body
        } catch (_: Exception) { body }
    }

    private fun addBubble(text: String, isUser: Boolean): TextView {
        val tv = makeText(text, 14f, if (isUser) Color.BLACK else Color.WHITE)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        if (isUser) tv.background = roundedBg(GREEN, dpf(16f))
        else { tv.background = roundedBg(Color.parseColor("#262626"), dpf(16f), STROKE); tv.setTextIsSelectable(true) }
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.gravity = if (isUser) Gravity.END else Gravity.START; it.bottomMargin = dp(8)
        }
        chatContainer.addView(tv); scrollChatToBottom()
        return tv
    }

    private fun scrollChatToBottom() { chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) } }
}
