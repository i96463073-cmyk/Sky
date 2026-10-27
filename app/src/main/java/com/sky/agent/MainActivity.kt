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
    private val POLL_MS = 5000L

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

    // --- CHART CACHE & PRICE FETCHING (NEW) ---
    private val chartCache = HashMap<String, JSONObject>()
    private val priceInFlight = AtomicBoolean(false)
    private val TIMEFRAMES = listOf("M5", "M15", "H1")

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            fetchLive()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val FALLBACK_SYMBOLS = listOf("XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "AUDUSD")
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
            if (!v.isNaN()) lastPrices[sym] = v
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
        
        // --- B1. GRADIENT BACKGROUND ---
        card.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 0x30), CARD, CARD)
        ).apply {
            cornerRadius = dpf(16f)
            setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x70))
        }
        
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
        
        // --- B2. SIGNAL ID ---
        s.str("id").takeIf { it.isNotEmpty() }?.let { id ->
            val idTv = makeText("#" + id.takeLast(6), 11f, MUTED)
            idTv.setPadding(dp(8), 0, 0, 0)
            top.addView(idTv)
        }
        
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
                connection.connectTimeout = 8000 // Tightened timeout
                connection.readTimeout = 12000    // Tightened timeout
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

    // --- SECTION A: NEW CHART IMPLEMENTATION ---
    private fun buildChartHtml(): String {
        return try {
            assets.open("chart.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Fallback HTML if chart.html is missing
            "<html><body style='background:#121212;color:#fff;padding:20px;font-family:sans-serif;'><h2>Chart Missing</h2><p>Could not find <b>chart.html</b> in the assets folder.</p></body></html>"
        }
    }

    private fun fetchPriceOnly(symbol: String, cb: (Double) -> Unit) {
        if (!priceInFlight.compareAndSet(false, true)) return
        thread {
            var p = Double.NaN
            try {
                val r = httpGet("/api/live")
                if (r.first in 200..299) p = JSONObject(r.second).optJSONObject("live_prices")?.num(symbol) ?: Double.NaN
            } catch (_: Exception) {
            } finally {
                runOnUiThread {
                    priceInFlight.set(false)
                    if (!isFinishing && !isDestroyed && !p.isNaN()) {
                        lastPrices[symbol] = p
                        cb(p)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showTradingViewChart(symbol: String) {
        val cleanSymbol = symbol.trim().uppercase(Locale.US).ifEmpty { "XAUUSD" }
        chartDialog?.dismiss()

        var selectedSymbol = cleanSymbol
        var selectedTf = "M15"
        var requestId = 0
        var pageReady = false
        val queued = ArrayList<String>()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10)); setBackgroundColor(Color.parseColor("#181818"))
        }
        val title = makeText("$cleanSymbol · SKY AI SIGNALS", 15f, Color.WHITE, true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val refresh = makeText("↻ Refresh", 12f, GREEN, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = roundedBg(Color.parseColor("#262626"), dpf(12f), STROKE)
        }
        val close = makeText("✕", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); background = roundedBg(Color.parseColor("#262626"), dpf(12f), STROKE)
        }
        header.addView(title)
        header.addView(refresh, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(8) })
        header.addView(close)
        root.addView(header)

        // chip row: timeframes + symbols
        val symbols = LinkedHashSet<String>()
        symbols.add(cleanSymbol)
        priceViews.keys.forEach { symbols.add(it.uppercase(Locale.US)) }
        FALLBACK_SYMBOLS.forEach { symbols.add(it) }

        val tfChips = LinkedHashMap<String, TextView>()
        val symChips = LinkedHashMap<String, TextView>()
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(7), dp(12), dp(7)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })

        // chart area
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val web = WebView(this).apply { setBackgroundColor(BG) }
        val loader = ProgressBar(this).apply { layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER) }
        val error = makeText("", 13f, AMBER).apply {
            gravity = Gravity.CENTER; setPadding(dp(22), dp(20), dp(22), dp(20)); visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(loader); frame.addView(error)
        root.addView(frame)
        root.addView(makeText("Live price every 2s · analysis every 30s · Drag to pan · Pinch to zoom", 10f, MUTED).apply { setPadding(dp(12), dp(6), dp(12), dp(8)) })

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setSupportMultipleWindows(false); mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        
        fun js(code: String) { if (pageReady) web.evaluateJavascript(code, null) else queued.add(code) }
        fun render(json: JSONObject, key: String) =
            js("window.renderSignal(JSON.parse(${JSONObject.quote(json.toString())}), ${JSONObject.quote(key)});")

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true; queued.forEach { web.evaluateJavascript(it, null) }; queued.clear()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, e: WebResourceError?) {
                if (request?.isForMainFrame == true) { loader.visibility = View.GONE; error.text = "Chart page failed to load. Check your connection."; error.visibility = View.VISIBLE }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }

        fun styleChips() {
            fun paint(map: Map<String, TextView>, sel: String) = map.forEach { (n, c) ->
                val on = n == sel
                c.setTextColor(if (on) Color.BLACK else Color.WHITE)
                c.background = roundedBg(if (on) GREEN else Color.parseColor("#262626"), dpf(18f), if (on) GREEN else STROKE)
            }
            paint(tfChips, selectedTf); paint(symChips, selectedSymbol)
        }

        // analysis load: show cached data instantly, then refresh in the background
        fun load() {
            val key = "$selectedSymbol|$selectedTf"
            val id = ++requestId
            val cached = chartCache[key]
            if (cached != null) { render(cached, key); loader.visibility = View.GONE } else loader.visibility = View.VISIBLE
            error.visibility = View.GONE
            
            loadSignalData(selectedSymbol, selectedTf) { json, msg ->
                // Cache fresh data even if request is stale (avoids refetching when switching back)
                if (json != null) chartCache[key] = json
                
                if (!dialog.isShowing || id != requestId) return@loadSignalData   // ignore stale responses
                loader.visibility = View.GONE
                if (json != null) {
                    render(json, key)
                } else if (chartCache[key] == null) {
                    error.text = "Couldn't load backend candles.\n$msg"; error.visibility = View.VISIBLE
                }
            }
        }

        val analysisLoop = object : Runnable {
            override fun run() { if (!dialog.isShowing) return; load(); handler.postDelayed(this, 30_000L) }
        }
        val priceLoop = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val sym = selectedSymbol
                fetchPriceOnly(sym) { p -> if (sym == selectedSymbol) js("window.tick($p);") }
                handler.postDelayed(this, 2_000L)
            }
        }
        fun restartLoops() {
            handler.removeCallbacks(analysisLoop); handler.removeCallbacks(priceLoop)
            handler.post(analysisLoop); handler.postDelayed(priceLoop, 1_500L)
        }
        
        fun makeChip(label: String, onClick: () -> Unit): TextView = makeText(label, 12f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(7) }
            applyBounce(this); setOnClickListener { onClick() }
        }

        for (tf in TIMEFRAMES) {
            val c = makeChip(tf) { selectedTf = tf; title.text = "$selectedSymbol · SKY AI SIGNALS"; styleChips(); restartLoops() }
            tfChips[tf] = c; chipRow.addView(c)
        }
        for (name in symbols) {
            val c = makeChip(name) { selectedSymbol = name; title.text = "$name · SKY AI SIGNALS"; styleChips(); restartLoops() }
            symChips[name] = c; chipRow.addView(c)
        }
        styleChips()

        refresh.setOnClickListener { restartLoops() }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            handler.removeCallbacks(analysisLoop); handler.removeCallbacks(priceLoop)
            web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy()
            if (chartDialog === dialog) chartDialog = null
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(BG))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        chartDialog = dialog
        dialog.show()
        web.loadDataWithBaseURL("https://local.skyai/", buildChartHtml(), "text/html", "UTF-8", null)
        restartLoops()   // data is queued until the page finishes loading
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
