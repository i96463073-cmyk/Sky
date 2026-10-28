package com.sky.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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

/** Animated circular confidence gauge for the signal cards. */
class RingView(context: Context) : View(context) {
    var ringColor = Color.GREEN
    var label = ""
    private var shown = 0f
    private val d = context.resources.displayMetrics.density
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; color = Color.parseColor("#2A3449")
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9 * d; strokeCap = Paint.Cap.ROUND
    }
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND
    }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 15 * d; color = Color.WHITE
    }
    private val r = RectF()

    fun setPercent(p: Int, animate: Boolean = true) {
        val target = p.coerceIn(0, 100).toFloat()
        if (!animate) { shown = target; invalidate(); return }
        ValueAnimator.ofFloat(0f, target).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val pad = 8 * d
        r.set(pad, pad, width - pad, height - pad)
        c.drawOval(r, trackP)
        val sweep = 360f * shown / 100f
        glowP.color = ColorUtils.setAlphaComponent(ringColor, 0x33)
        c.drawArc(r, -90f, sweep, false, glowP)
        arcP.color = ringColor
        c.drawArc(r, -90f, sweep, false, arcP)
        c.drawText(label, width / 2f, height / 2f - (textP.descent() + textP.ascent()) / 2f, textP)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var logoMark: TextView
    private lateinit var tvBrand: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var tickerContainer: LinearLayout
    private lateinit var tvUpdated: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollMain: ScrollView

    private lateinit var chartSection: LinearLayout
    private lateinit var signalsSection: LinearLayout
    private lateinit var historySection: LinearLayout
    private lateinit var marketSection: LinearLayout

    private lateinit var chartCard: LinearLayout
    private lateinit var tvChartTitle: TextView
    private lateinit var tvChartSub: TextView
    private lateinit var tvChartPrice: TextView
    private lateinit var btnRefresh: TextView
    private lateinit var tfContainer: LinearLayout
    private lateinit var chartFrame: FrameLayout
    private lateinit var tvTradeInfo: TextView
    private lateinit var tvLivePill: TextView
    private lateinit var filterContainer: LinearLayout
    private lateinit var statsCard: LinearLayout
    private lateinit var pendingContainer: LinearLayout
    private lateinit var completedContainer: LinearLayout
    private lateinit var newsContainer: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvNewsStatus: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var tvCompletedCount: TextView
    private lateinit var tvWins: TextView
    private lateinit var tvLosses: TextView
    private lateinit var tvTotalPips: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var tvCloseChat: TextView
    private lateinit var navHome: TextView
    private lateinit var navSignals: TextView
    private lateinit var navNew: TextView
    private lateinit var navHistory: TextView
    private lateinit var navProfile: TextView

    private lateinit var web: WebView
    private lateinit var chartLoader: ProgressBar
    private lateinit var chartError: TextView

    private val BACKEND_BASE = "https://sky-ai-backend.i96463073.workers.dev"
    private val SIGNAL_WORKER_BASE = "https://sky-ai-backend.i96463073.workers.dev"
    private val POLL_MS = 4000L

    private val BG = Color.parseColor("#0B0E14")
    private val CARD = Color.parseColor("#131824")
    private val GREEN = Color.parseColor("#00E676")
    private val RED = Color.parseColor("#FF3B5C")
    private val AMBER = Color.parseColor("#FFB300")
    private val MUTED = Color.parseColor("#8A93A6")
    private val STROKE = Color.parseColor("#2A3449")
    private val PURPLE = Color.parseColor("#8C52FF")
    private val PURPLE_L = Color.parseColor("#B388FF")

    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)
    private var polling = false
    private var hasData = false
    private var pollCount = 0
    private var lastOkTime: String? = null
    private var lastSignalsSig: String? = null

    private val priceViews = LinkedHashMap<String, TextView>()
    private val chipViews = LinkedHashMap<String, LinearLayout>()
    private val tfChips = LinkedHashMap<String, TextView>()
    private val filterChips = LinkedHashMap<String, TextView>()
    private val lastPrices = HashMap<String, Double>()
    private val chartCache = HashMap<String, JSONObject>()

    private val TIMEFRAMES = listOf("M5", "M15", "H1", "H4")
    private val TF_LABELS = mapOf("M5" to "5m", "M15" to "15m", "H1" to "1h", "H4" to "4h")
    private val FALLBACK_SYMBOLS = listOf("XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "BTCUSD")

    private var selectedSymbol = "XAUUSD"
    private var selectedTf = "M15"
    private var requestId = 0
    private var pageReady = false
    private val queued = ArrayList<String>()

    private lateinit var sharedPrefs: android.content.SharedPreferences
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var lastSignalIds: Set<String> = emptySet()
    private var signalsSeen = false

    private var allPending = JSONArray()
    private var allCompleted = JSONArray()
    private var dirFilter = "ALL"

    private var activeTrade: JSONObject? = null
    private var activeTradeSym = ""

    private class LivePip(val tv: TextView, val sym: String, val dir: String, val entry: Double)
    private data class PipResult(val pips: Double, val estimated: Boolean)
    private val livePips = ArrayList<LivePip>()

    private enum class Tab { CHART, SIGNALS, HISTORY, MARKET }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            fetchLive()
            pollCount++
            if (pollCount % 8 == 0 && chartSection.visibility == View.VISIBLE) loadChart()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("SkyAiPrefs", Context.MODE_PRIVATE)
        selectedSymbol = sharedPrefs.getString("selectedSymbol", "XAUUSD") ?: "XAUUSD"
        selectedTf = sharedPrefs.getString("selectedTf", "M15") ?: "M15"

        bindViews()
        setupWindow()
        styleStatic()
        buildTicker(FALLBACK_SYMBOLS)
        buildTfChips()
        buildFilterChips()
        setupChart()
        updateChartHeader()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        } catch (_: Exception) {}

        applyBounce(btnRefresh); applyBounce(btnSend); applyBounce(tvCloseChat)
        btnRefresh.setOnClickListener { fetchLive(manual = true); loadChart() }
        btnSend.setOnClickListener { sendChat() }
        tvCloseChat.setOnClickListener { chatPanel.visibility = View.GONE; navProfile.setTextColor(MUTED) }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }
        tvTradeInfo.setOnClickListener {
            activeTrade = null
            updateTradeInfo()
            js("window.setTrade(null);")
        }

        navHome.setOnClickListener { showTab(Tab.CHART) }
        navSignals.setOnClickListener { showTab(Tab.SIGNALS) }
        navHistory.setOnClickListener { showTab(Tab.HISTORY) }
        navNew.setOnClickListener { showTab(Tab.MARKET) }
        navProfile.setOnClickListener {
            val open = chatPanel.visibility != View.VISIBLE
            chatPanel.visibility = if (open) View.VISIBLE else View.GONE
            navProfile.setTextColor(if (open) PURPLE_L else MUTED)
        }

        addEmptyText(pendingContainer, "Connecting to backend…")
        addEmptyText(completedContainer, "Connecting to backend…")
        tvNewsStatus.text = "No news data received from the backend yet."
        updateSessionPanel()
        showTab(Tab.CHART)
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootLayout)
        logoMark = findViewById(R.id.logoMark)
        tvBrand = findViewById(R.id.tvBrand)
        statusPill = findViewById(R.id.statusPill)
        tickerContainer = findViewById(R.id.tickerContainer)
        tvUpdated = findViewById(R.id.tvUpdated)
        progressBar = findViewById(R.id.progressBar)
        scrollMain = findViewById(R.id.scrollMain)

        chartSection = findViewById(R.id.chartSection)
        signalsSection = findViewById(R.id.signalsSection)
        historySection = findViewById(R.id.historySection)
        marketSection = findViewById(R.id.marketSection)

        chartCard = findViewById(R.id.chartCard)
        tvChartTitle = findViewById(R.id.tvChartTitle)
        tvChartSub = findViewById(R.id.tvChartSub)
        tvChartPrice = findViewById(R.id.tvChartPrice)
        btnRefresh = findViewById(R.id.btnRefresh)
        tfContainer = findViewById(R.id.tfContainer)
        chartFrame = findViewById(R.id.chartFrame)
        tvTradeInfo = findViewById(R.id.tvTradeInfo)
        tvLivePill = findViewById(R.id.tvLivePill)
        filterContainer = findViewById(R.id.filterContainer)
        statsCard = findViewById(R.id.statsCard)
        pendingContainer = findViewById(R.id.pendingContainer)
        completedContainer = findViewById(R.id.completedContainer)
        newsContainer = findViewById(R.id.newsContainer)
        tvStatus = findViewById(R.id.tvStatus)
        tvSession = findViewById(R.id.tvSession)
        tvNewsStatus = findViewById(R.id.tvNewsStatus)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        tvCompletedCount = findViewById(R.id.tvCompletedCount)
        tvWins = findViewById(R.id.tvWins)
        tvLosses = findViewById(R.id.tvLosses)
        tvTotalPips = findViewById(R.id.tvTotalPips)
        tvWinRate = findViewById(R.id.tvWinRate)
        chatPanel = findViewById(R.id.chatPanel)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        tvCloseChat = findViewById(R.id.tvCloseChat)
        navHome = findViewById(R.id.navHome)
        navSignals = findViewById(R.id.navSignals)
        navNew = findViewById(R.id.navNew)
        navHistory = findViewById(R.id.navHistory)
        navProfile = findViewById(R.id.navProfile)
    }

    override fun onResume() {
        super.onResume()
        if (!polling) {
            polling = true
            handler.removeCallbacks(pollRunnable); handler.post(pollRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
        toneGenerator?.release()
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

    private fun styleStatic() {
        logoMark.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(PURPLE_L, Color.parseColor("#5B2BD9"))
        ).apply { cornerRadius = dpf(10f) }

        val sp = SpannableString("SMC SIGNALS")
        sp.setSpan(ForegroundColorSpan(PURPLE_L), 4, 11, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvBrand.text = sp

        statusPill.background = roundedBg(ColorUtils.setAlphaComponent(PURPLE, 0x18), dpf(14f), ColorUtils.setAlphaComponent(PURPLE_L, 0x80))
        chartCard.background = roundedBg(CARD, dpf(16f), STROKE)
        statsCard.background = roundedBg(CARD, dpf(14f), STROKE)
        tvLivePill.background = roundedBg(ColorUtils.setAlphaComponent(GREEN, 0x20), dpf(12f), GREEN)
        chatPanel.setBackgroundColor(CARD)
        etInput.background = roundedBg(Color.parseColor("#1B2130"), dpf(18f), STROKE)
        btnSend.background = roundedBg(GREEN, dpf(18f))
        btnSend.setTextColor(Color.BLACK)
    }

    // ---------- SEPARATE SCREENS ----------
    private fun showTab(t: Tab) {
        if (t == Tab.CHART) {
            chartSection.visibility = View.VISIBLE
            scrollMain.visibility = View.GONE
        } else {
            chartSection.visibility = View.GONE
            scrollMain.visibility = View.VISIBLE

            signalsSection.visibility = if (t == Tab.SIGNALS) View.VISIBLE else View.GONE
            historySection.visibility = if (t == Tab.HISTORY) View.VISIBLE else View.GONE
            marketSection.visibility = if (t == Tab.MARKET) View.VISIBLE else View.GONE

            scrollMain.post { scrollMain.scrollTo(0, 0) }
        }

        setNav(when (t) {
            Tab.CHART -> navHome
            Tab.SIGNALS -> navSignals
            Tab.HISTORY -> navHistory
            Tab.MARKET -> navNew
        })
    }

    private fun setNav(active: TextView) {
        listOf(navHome, navSignals, navHistory, navNew).forEach {
            it.setTextColor(if (it === active) PURPLE_L else MUTED)
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

    private fun col(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "").trim()
    private fun JSONObject.num(k: String): Double = if (isNull(k)) Double.NaN else optDouble(k, Double.NaN)
    private fun nowHms(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    @SuppressLint("ClickableViewAccessibility")
    private fun applyBounce(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    private fun pairName(sym: String): String = when (sym) {
        "XAUUSD" -> "Gold / US Dollar"
        "EURUSD" -> "Euro / US Dollar"
        "GBPUSD" -> "Pound / US Dollar"
        "USDJPY" -> "US Dollar / Yen"
        "AUDUSD" -> "Aussie / US Dollar"
        "USDCAD" -> "US Dollar / CAD"
        "USDCHF" -> "US Dollar / Franc"
        "NZDUSD" -> "Kiwi / US Dollar"
        "BTCUSD" -> "Bitcoin / US Dollar"
        else -> "Forex Pair"
    }

    private fun pipSize(sym: String): Double = when {
        sym.contains("JPY") -> 0.01
        sym.contains("XAU") -> 0.1
        sym.contains("BTC") -> 1.0
        else -> 0.0001
    }

    private fun tfKey(raw: String): String? {
        val r = raw.trim().uppercase(Locale.US)
        if (r in TIMEFRAMES) return r
        return when (r) { "5M" -> "M5"; "15M" -> "M15"; "1H" -> "H1"; "4H" -> "H4"; else -> null }
    }

    // ---------- PIPS ----------
    private fun pipsOf(sym: String, dir: String, from: Double, to: Double): Double {
        val diff = if (dir == "SELL") from - to else to - from
        return diff / pipSize(sym)
    }

    private fun fmtPips(p: Double): String = String.format(Locale.US, "%+.1f", p)

    private fun slOf(s: JSONObject): Double {
        val a = s.num("stop_loss")
        return if (a.isNaN()) s.num("sl") else a
    }

    private fun JSONObject.tps(): List<Double> {
        val out = ArrayList<Double>()
        val a = optJSONArray("take_profit") ?: optJSONArray("take_profits") ?: optJSONArray("targets")
        if (a != null) {
            for (i in 0 until a.length()) { val v = a.optDouble(i, Double.NaN); if (!v.isNaN()) out.add(v) }
        } else {
            val single = num("take_profit")
            if (!single.isNaN()) out.add(single)
            for (k in listOf("tp1", "tp2", "tp3")) {
                val v = num(k)
                if (!v.isNaN() && !out.contains(v)) out.add(v)
            }
        }
        return out
    }

    private fun pipsForSignal(s: JSONObject): PipResult? {
        val sym = s.str("symbol").uppercase(Locale.US)
        val dir = s.str("direction").uppercase(Locale.US)
        val result = s.str("result").uppercase(Locale.US)
        val entry = s.num("entry")
        for (k in listOf("pips", "pips_achieved", "pnl_pips", "profit_pips")) {
            val v = s.num(k)
            if (!v.isNaN()) return PipResult(v, false)
        }
        if (entry.isNaN()) return null
        for (k in listOf("exit_price", "close_price", "closed_price", "exit")) {
            val v = s.num(k)
            if (!v.isNaN()) return PipResult(pipsOf(sym, dir, entry, v), false)
        }
        val tps = s.tps()
        return when (result) {
            "WIN" -> {
                val hit = s.optInt("tp_hit", 0)
                val tp = (if (hit in 1..tps.size) tps[hit - 1] else tps.firstOrNull()) ?: return null
                PipResult(pipsOf(sym, dir, entry, tp), true)
            }
            "LOSS" -> {
                val sl = slOf(s)
                if (sl.isNaN()) null else PipResult(pipsOf(sym, dir, entry, sl), true)
            }
            else -> null
        }
    }

    private fun refreshLivePips() {
        for (p in livePips) {
            val px = lastPrices[p.sym]
            if (px == null || p.entry.isNaN()) { p.tv.text = "—"; continue }
            val v = pipsOf(p.sym, p.dir, p.entry, px)
            p.tv.text = fmtPips(v)
            p.tv.setTextColor(if (v >= 0) GREEN else RED)
        }
    }

    // ---------- NETWORK ----------
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
            addEmptyText(completedContainer, msg)
        }
    }

    private enum class State { LIVE, CONNECTING, OFFLINE }

    private fun setStatus(s: State) {
        when (s) {
            State.LIVE -> { tvStatus.text = "● LIVE"; tvStatus.setTextColor(GREEN) }
            State.CONNECTING -> { tvStatus.text = "● CONNECTING"; tvStatus.setTextColor(AMBER) }
            State.OFFLINE -> { tvStatus.text = if (hasData) "● OFFLINE · last data" else "● OFFLINE"; tvStatus.setTextColor(RED) }
        }
    }

    // ---------- TICKER ----------
    private fun buildTicker(keys: List<String>) {
        tickerContainer.removeAllViews(); priceViews.clear(); chipViews.clear()
        for (sym in keys) {
            val chip = col()
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.marginEnd = dp(8); chip.layoutParams = lp
            val price = makeText(lastPrices[sym]?.let { formatPrice(sym, it) } ?: "—", 14f, Color.WHITE, true)
            chip.addView(makeText(sym, 11f, MUTED)); chip.addView(price)
            chip.setOnClickListener { selectSymbol(sym) }
            applyBounce(chip)
            tickerContainer.addView(chip)
            priceViews[sym] = price; chipViews[sym] = chip
        }
        styleSymbolChips()
    }

    private fun styleSymbolChips() {
        chipViews.forEach { (n, c) ->
            c.background = if (n == selectedSymbol)
                roundedBg(ColorUtils.setAlphaComponent(PURPLE, 0x40), dpf(14f), PURPLE)
            else roundedBg(CARD, dpf(14f), STROKE)
        }
    }

    private fun updateTicker(prices: JSONObject) {
        val keys = ArrayList<String>()
        val it = prices.keys()
        while (it.hasNext()) keys.add(it.next())

        if (keys.toSet() != priceViews.keys.toSet()) buildTicker(keys)

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

            if (sym == selectedSymbol && !v.isNaN()) {
                tvChartPrice.text = formatPrice(sym, v)
                if (pageReady) web.evaluateJavascript("window.tick($v);", null)
            }
        }
        refreshLivePips()
    }

    private fun buildTfChips() {
        for (tf in TIMEFRAMES) {
            val c = makeText(TF_LABELS[tf] ?: tf, 12f, Color.WHITE, true).apply {
                setPadding(dp(16), dp(7), dp(16), dp(7))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = dp(6) }
            }
            applyBounce(c)
            c.setOnClickListener {
                selectedTf = tf
                sharedPrefs.edit().putString("selectedTf", selectedTf).apply()
                updateChartHeader(); styleTfChips(); loadChart()
            }
            tfChips[tf] = c; tfContainer.addView(c)
        }
        styleTfChips()
    }

    private fun styleTfChips() {
        tfChips.forEach { (n, c) ->
            val on = n == selectedTf
            c.setTextColor(if (on) Color.WHITE else MUTED)
            c.background = if (on) roundedBg(ColorUtils.setAlphaComponent(PURPLE, 0x55), dpf(10f), PURPLE)
            else roundedBg(Color.TRANSPARENT, dpf(10f))
        }
    }

    private fun buildFilterChips() {
        for (f in listOf("ALL", "BUY", "SELL")) {
            val c = makeText(f, 12f, Color.WHITE, true).apply {
                setPadding(dp(18), dp(7), dp(18), dp(7))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = dp(8) }
            }
            applyBounce(c)
            c.setOnClickListener { dirFilter = f; styleFilterChips(); renderLists() }
            filterChips[f] = c; filterContainer.addView(c)
        }
        styleFilterChips()
    }

    private fun styleFilterChips() {
        filterChips.forEach { (n, c) ->
            val on = n == dirFilter
            val tint = when (n) { "BUY" -> GREEN; "SELL" -> RED; else -> PURPLE }
            c.setTextColor(if (on) Color.WHITE else MUTED)
            c.background = if (on) roundedBg(ColorUtils.setAlphaComponent(tint, 0x55), dpf(14f), tint)
            else roundedBg(Color.TRANSPARENT, dpf(14f), STROKE)
        }
    }

    private fun updateChartHeader() {
        tvChartTitle.text = selectedSymbol
        tvChartSub.text = pairName(selectedSymbol) + " · " + (TF_LABELS[selectedTf] ?: selectedTf)
    }

    private fun updateTradeInfo() {
        val t = activeTrade
        if (t != null && activeTradeSym == selectedSymbol) {
            tvTradeInfo.text = "Showing ${t.optString("direction")} setup on $activeTradeSym · tap to clear"
            tvTradeInfo.visibility = View.VISIBLE
        } else tvTradeInfo.visibility = View.GONE
    }

    private fun selectSymbol(sym: String) {
        selectedSymbol = sym.trim().uppercase(Locale.US).ifEmpty { "XAUUSD" }
        sharedPrefs.edit().putString("selectedSymbol", selectedSymbol).apply()
        updateChartHeader(); styleSymbolChips(); updateTradeInfo()
        tvChartPrice.text = lastPrices[selectedSymbol]?.let { formatPrice(selectedSymbol, it) } ?: "—"
        loadChart()
    }

    private fun openChartFor(symbol: String, direction: String, entry: Double, sl: Double, tps: List<Double>, tf: String) {
        val t = JSONObject().put("direction", direction)
        if (!entry.isNaN()) t.put("entry", entry)
        if (!sl.isNaN()) t.put("stop_loss", sl)
        t.put("tps", JSONArray(tps))
        activeTrade = t
        activeTradeSym = symbol
        tfKey(tf)?.let {
            selectedTf = it
            sharedPrefs.edit().putString("selectedTf", it).apply()
            styleTfChips()
        }
        selectSymbol(symbol)
        showTab(Tab.CHART)
    }

    // ---------- CHART ----------
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupChart() {
        web = WebView(this).apply { setBackgroundColor(BG) }
        chartLoader = ProgressBar(this).apply { layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER) }
        chartError = makeText("", 13f, AMBER).apply {
            gravity = Gravity.CENTER; setPadding(dp(22), dp(20), dp(22), dp(20)); visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        chartFrame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chartFrame.addView(chartLoader); chartFrame.addView(chartError)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true; queued.forEach { web.evaluateJavascript(it, null) }; queued.clear()
                loadChart()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, e: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    chartLoader.visibility = View.GONE
                    chartError.text = "Chart page failed to load. Check your connection."
                    chartError.visibility = View.VISIBLE
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }

        web.loadDataWithBaseURL("file:///android_asset/", buildChartHtml(), "text/html", "UTF-8", null)
    }

    private fun js(code: String) { if (pageReady) web.evaluateJavascript(code, null) else queued.add(code) }

    private fun render(json: JSONObject, key: String) {
        val t = activeTrade
        val trade = if (t != null && activeTradeSym == selectedSymbol) t.toString() else "null"
        js("window.renderSignal(JSON.parse(${JSONObject.quote(json.toString())}), ${JSONObject.quote(key)}); window.setTrade($trade);")
    }

    private fun loadChart() {
        val key = "$selectedSymbol|$selectedTf"
        val id = ++requestId
        val cached = chartCache[key]
        if (cached != null) { render(cached, key); chartLoader.visibility = View.GONE } else chartLoader.visibility = View.VISIBLE
        chartError.visibility = View.GONE

        loadSignalData(selectedSymbol, selectedTf) { json, msg ->
            if (json != null) chartCache[key] = json
            if (id != requestId) return@loadSignalData
            chartLoader.visibility = View.GONE
            if (json != null) render(json, key)
            else if (chartCache[key] == null) {
                chartError.text = "Couldn't load backend candles.\n$msg"; chartError.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSignalData(symbol: String, timeframe: String, callback: (JSONObject?, String?) -> Unit) {
        thread {
            var connection: HttpURLConnection? = null
            try {
                val s = URLEncoder.encode(symbol, "UTF-8")
                val tf = URLEncoder.encode(timeframe, "UTF-8")
                val endpoint = "$SIGNAL_WORKER_BASE/test-signal?symbol=$s&timeframe=$tf"
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(180)}")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Signal endpoint returned ok=false"))
                runOnUiThread { if (!isFinishing && !isDestroyed) callback(json, null) }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) callback(null, e.message ?: "Unable to load backend candles") }
            } finally { connection?.disconnect() }
        }
    }

    private fun buildChartHtml(): String {
        return try {
            assets.open("chart.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body style='background:#0B0E14;color:#fff;padding:20px;font-family:sans-serif;'><h2>Chart Missing</h2><p>Could not find <b>chart.html</b> in the assets folder.</p></body></html>"
        }
    }

    // ---------- SIGNALS + HISTORY ----------
    private fun renderSignals(pending: JSONArray, completed: JSONArray) {
        val sig = pending.toString() + "|" + completed.toString()
        if (sig == lastSignalsSig) return
        lastSignalsSig = sig

        // --- NEW: Filter out old signals (older than 12 hours) ---
        val validPending = JSONArray()
        val now = System.currentTimeMillis()
        
        for (i in 0 until pending.length()) {
            val o = pending.optJSONObject(i) ?: continue
            val timeStr = o.str("created_at").ifEmpty { o.str("time").ifEmpty { o.str("timestamp") } }
            val date = parseIso(timeStr)
            
            if (date != null) {
                val ageMs = now - date.time
                if (ageMs > 12 * 60 * 60 * 1000L) {
                    continue // Skip this old signal
                }
            }
            validPending.put(o)
        }
        // ----------------------------------------------------------

        val ids = mutableSetOf<String>()
        for (i in 0 until validPending.length()) {
            val id = validPending.optJSONObject(i)?.str("id")
            if (!id.isNullOrEmpty()) ids.add(id)
        }
        if (signalsSeen && !lastSignalIds.containsAll(ids)) triggerAlert()
        signalsSeen = true
        lastSignalIds = ids

        var wins = 0; var losses = 0; var totalPips = 0.0; var anyPips = false
        for (i in 0 until completed.length()) {
            val o = completed.optJSONObject(i) ?: continue
            when (o.str("result").uppercase(Locale.US)) { "WIN" -> wins++; "LOSS" -> losses++ }
            pipsForSignal(o)?.let { totalPips += it.pips; anyPips = true }
        }
        
        tvPendingCount.text = "${validPending.length()} active"
        tvCompletedCount.text = completed.length().toString()
        tvWins.text = wins.toString(); tvLosses.text = losses.toString()
        tvTotalPips.text = if (anyPips) fmtPips(totalPips) else "—"
        tvTotalPips.setTextColor(if (!anyPips) Color.WHITE else if (totalPips >= 0) GREEN else RED)
        val decided = wins + losses
        tvWinRate.text = if (decided > 0) String.format(Locale.US, "Win rate: %.0f%% (%d wins of %d decided trades)", wins * 100.0 / decided, wins, decided) else "Win rate: — (no WIN/LOSS outcomes yet)"

        allPending = validPending
        allCompleted = completed
        renderLists()
    }

    private fun passes(o: JSONObject?): Boolean =
        o != null && (dirFilter == "ALL" || o.str("direction").uppercase(Locale.US) == dirFilter)

    private fun renderLists() {
        livePips.clear()
        pendingContainer.removeAllViews(); completedContainer.removeAllViews()

        var n = 0
        for (i in 0 until allPending.length()) {
            val o = allPending.optJSONObject(i)
            if (o != null && passes(o)) addSignalCard(pendingContainer, o, true, n++)
        }
        if (n == 0) addEmptyText(pendingContainer, "No active signals right now.\nWaiting for a setup from the backend...")

        var m = 0
        for (i in 0 until allCompleted.length()) {
            val o = allCompleted.optJSONObject(i)
            if (o != null && passes(o)) addSignalCard(completedContainer, o, false, m++)
        }
        if (m == 0) addEmptyText(completedContainer, "No signal history yet.")

        refreshLivePips()
    }

    private fun triggerAlert() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200)
            }
        } catch (_: Exception) {}
    }

    private fun checkLevels(dir: String, e: Double, sl: Double, tp: Double): Pair<Double?, String?> {
        if (e.isNaN() || sl.isNaN() || tp.isNaN()) return Pair(null, null)
        val (risk, reward) = when (dir) {
            "BUY" -> Pair(e - sl, tp - e)
            "SELL" -> Pair(sl - e, e - tp)
            else -> return Pair(null, null)
        }
        if (risk <= 0 || reward <= 0) return Pair(null, "Inconsistent levels for $dir")
        return Pair(reward / risk, null)
    }

    private fun badge(text: String, bg: Int, fg: Int): TextView {
        val t = makeText(text, 10f, fg, true)
        t.setPadding(dp(8), dp(3), dp(8), dp(3))
        t.background = roundedBg(bg, dpf(8f))
        t.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginStart = dp(6) }
        return t
    }

    private fun priceBox(label: String, value: String, color: Int, weight: Float): LinearLayout {
        val b = col()
        b.addView(makeText(label, 9f, MUTED))
        b.addView(makeText(value, 13f, color, true))
        b.layoutParams = LinearLayout.LayoutParams(0, WRAP, weight)
        return b
    }

    private fun addSignalCard(container: LinearLayout, s: JSONObject, isPending: Boolean, index: Int) {
        val symbol = s.str("symbol").ifEmpty { "UNKNOWN" }.uppercase(Locale.US)
        val direction = s.str("direction").uppercase(Locale.US).ifEmpty { "—" }
        val entry = s.num("entry")
        val sl = slOf(s)
        val tps = s.tps()
        val result = s.str("result").uppercase(Locale.US)
        val quality = s.str("quality").ifEmpty { s.str("grade") }.uppercase(Locale.US)
        val tf = s.str("timeframe")
        val whenRaw = s.str("created_at").ifEmpty { s.str("time").ifEmpty { s.str("timestamp") } }
        val accent = when (direction) { "BUY" -> GREEN; "SELL" -> RED; else -> MUTED }

        val confRaw = s.num("confidence")
        val confidence: Int? = if (confRaw.isNaN()) null else (if (confRaw <= 1.0) confRaw * 100 else confRaw).toInt()

        val card = col()
        card.setPadding(dp(14), dp(14), dp(14), dp(12))
        card.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 0x15), CARD)
        ).apply { cornerRadius = dpf(14f); setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x60)) }
        card.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(12) }

        // ---- top row: confidence ring | symbol info | pips ----
        val top = row()
        top.gravity = Gravity.CENTER_VERTICAL

        val ringCol = col()
        ringCol.gravity = Gravity.CENTER_HORIZONTAL
        val ring = RingView(this)
        ring.ringColor = accent
        
        // --- NEW: Better fallback for missing confidence ---
        if (confidence != null) { 
            ring.label = "$confidence%"
            ring.setPercent(confidence) 
        } else if (quality.isNotEmpty()) { 
            ring.label = quality
            ring.setPercent(100) 
        } else { 
            ring.label = "—" 
            ring.setPercent(0) 
        }
        // ----------------------------------------------------
        
        ringCol.addView(ring, LinearLayout.LayoutParams(dp(68), dp(68)))
        ringCol.addView(makeText("Confidence", 9f, MUTED).apply { gravity = Gravity.CENTER })
        top.addView(ringCol)

        val info = col()
        info.setPadding(dp(12), 0, dp(8), 0)
        info.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val nameRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(makeText(symbol, 17f, Color.WHITE, true))
        nameRow.addView(badge(direction, accent, Color.BLACK))
        if (tf.isNotEmpty()) nameRow.addView(badge(TF_LABELS[tfKey(tf) ?: ""] ?: tf, Color.parseColor("#2A3449"), Color.WHITE))
        info.addView(nameRow)
        info.addView(makeText(pairName(symbol), 10f, MUTED))
        if (whenRaw.isNotEmpty()) info.addView(makeText(formatTime(whenRaw), 10f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        top.addView(info)

        val pipCol = col()
        pipCol.gravity = Gravity.END
        val pipTv = makeText("—", 20f, MUTED, true)
        pipCol.addView(pipTv)
        var pipSub = if (isPending) "pips · live" else "pips"
        if (isPending) {
            livePips.add(LivePip(pipTv, symbol, direction, entry))
        } else {
            val pr = pipsForSignal(s)
            if (pr != null) {
                pipTv.text = fmtPips(pr.pips)
                pipTv.setTextColor(if (pr.pips >= 0) GREEN else RED)
                if (pr.estimated) pipSub = "pips · est."
            }
        }
        pipCol.addView(makeText(pipSub, 9f, MUTED).apply { gravity = Gravity.END })
        top.addView(pipCol)
        card.addView(top)

        // ---- levels ----
        val rr = if (tps.isNotEmpty()) checkLevels(direction, entry, sl, tps[0]).first else null
        val row1 = row().apply { setPadding(0, dp(12), 0, 0) }
        row1.addView(priceBox("Entry", formatPrice(symbol, entry), Color.WHITE, 1f))
        row1.addView(priceBox("Stop Loss", formatPrice(symbol, sl), RED, 1f))
        row1.addView(priceBox("Risk : Reward", rr?.let { String.format(Locale.US, "1 : %.1f", it) } ?: "—", PURPLE_L, 1f))
        card.addView(row1)

        if (tps.isNotEmpty()) {
            val row2 = row().apply { setPadding(0, dp(8), 0, 0) }
            for (k in 0 until minOf(tps.size, 3)) row2.addView(priceBox("TP${k + 1}", formatPrice(symbol, tps[k]), GREEN, 1f))
            for (k in tps.size until 3) row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            card.addView(row2)
        }

        // ---- confluence ----
        val conf = s.optJSONArray("confluence")
        if (conf != null && conf.length() > 0) {
            val items = ArrayList<String>()
            for (i in 0 until minOf(conf.length(), 5)) {
                val item = conf.opt(i)
                val text = when (item) { is String -> item; is JSONObject -> item.str("label").ifEmpty { item.str("text") }; else -> "" }
                if (text.isNotBlank()) items.add("✓ $text")
            }
            if (items.isNotEmpty()) card.addView(makeText(items.joinToString("    "), 10f, Color.parseColor("#C5CBD8")).apply { setPadding(0, dp(10), 0, 0) })
        }

        // ---- bottom: status + view chart ----
        val bottom = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val statusText = if (isPending) "● Active" else if (result.isNotEmpty()) "● $result" else "● Closed"
        val statusColor = if (isPending) AMBER else when (result) { "WIN" -> GREEN; "LOSS" -> RED; else -> MUTED }
        bottom.addView(makeText(statusText, 11f, statusColor, true).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        val btnChart = makeText("📈 View Chart", 11f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBg(ColorUtils.setAlphaComponent(PURPLE, 0x40), dpf(10f), PURPLE)
            gravity = Gravity.CENTER
        }
        applyBounce(btnChart)
        btnChart.setOnClickListener { openChartFor(symbol, direction, entry, sl, tps, tf) }
        bottom.addView(btnChart)
        card.addView(bottom)

        card.alpha = 0f; card.translationX = dpf(20f); container.addView(card)
        card.animate().alpha(1f).translationX(0f).setStartDelay(index * 50L).setDuration(300).start()
    }

    private fun formatPrice(symbol: String, v: Double): String {
        if (v.isNaN() || v == 0.0 || v.isInfinite()) return "—"
        val decimals = when {
            symbol.contains("XAU") -> 2
            symbol.contains("BTC") -> 2
            symbol.contains("JPY") -> 3
            else -> 5
        }
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    private fun parseIso(iso: String): Date? {
        if (iso.isEmpty()) return null
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in patterns) {
            try {
                val parser = SimpleDateFormat(p, Locale.US); parser.timeZone = TimeZone.getTimeZone("UTC")
                val d = parser.parse(iso)
                if (d != null) return d
            } catch (_: Exception) {}
        }
        return null
    }

    private fun formatTime(iso: String): String {
        val d = parseIso(iso) ?: return iso
        return SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(d)
    }

    private fun addEmptyText(container: LinearLayout, text: String) {
        val tv = makeText(text, 14f, MUTED)
        tv.gravity = Gravity.CENTER; tv.setPadding(0, dp(24), 0, dp(24))
        tv.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        container.addView(tv)
    }

    // ---------- MARKET ----------
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
            val box = col(); box.setPadding(0, dp(8), 0, dp(4))
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

    // ---------- CHAT ----------
    private fun sendChat() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.setText(""); chatPanel.visibility = View.VISIBLE
        navProfile.setTextColor(PURPLE_L)
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
        tv.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also {
            it.gravity = if (isUser) Gravity.END else Gravity.START; it.bottomMargin = dp(8)
        }
        chatContainer.addView(tv); scrollChatToBottom()
        return tv
    }

    private fun scrollChatToBottom() { chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) } }
}
