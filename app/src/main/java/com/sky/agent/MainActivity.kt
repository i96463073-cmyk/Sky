// ============================================================
// SKY AI – MainActivity.kt patch
// 1) Put chart.html in:  app/src/main/assets/chart.html
// 2) In MainActivity.kt DELETE the old buildChartHtml() and
//    showTradingViewChart() and paste everything in section A.
// 3) Section B: two small edits inside addSignalCard().
// Needs no new imports beyond what you already have.
// ============================================================

// ---------------- A. CHART (replaces buildChartHtml + showTradingViewChart) ----------------

private val chartCache = HashMap<String, JSONObject>()
private val priceInFlight = AtomicBoolean(false)
private val TIMEFRAMES = listOf("M5", "M15", "H1")

private fun buildChartHtml(): String =
    assets.open("chart.html").bufferedReader().use { it.readText() }

// Light call for the live tick. Uses the same /api/live you already poll.
// TODO: add a tiny /api/price?symbol= endpoint on the Worker to make this even lighter.
private fun fetchPriceOnly(symbol: String, cb: (Double) -> Unit) {
    if (!priceInFlight.compareAndSet(false, true)) return
    thread {
        var p = Double.NaN
        try {
            val r = httpGet("/api/live")
            if (r.first in 200..299) p = JSONObject(r.second).optJSONObject("live_prices")?.num(symbol) ?: Double.NaN
        } catch (_: Exception) {}
        runOnUiThread {
            priceInFlight.set(false)
            if (!isFinishing && !isDestroyed && !p.isNaN()) { lastPrices[symbol] = p; cb(p) }
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
            if (!dialog.isShowing || id != requestId) return@loadSignalData   // ignore stale responses
            loader.visibility = View.GONE
            if (json != null) { chartCache[key] = json; render(json, key) }
            else if (chartCache[key] == null) { error.text = "Couldn't load backend candles.\n$msg"; error.visibility = View.VISIBLE }
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

// Also tighten the existing loadSignalData timeouts (it blocks the first paint):
//   connection.connectTimeout = 8000
//   connection.readTimeout    = 12000

// ---------------- B. SIGNAL CARDS (inside addSignalCard) ----------------

// B1. Replace:  card.background = roundedBg(CARD, dpf(16f), STROKE)
// with (green/red tinted like the Forex Ninja reference):
card.background = GradientDrawable(
    GradientDrawable.Orientation.LEFT_RIGHT,
    intArrayOf(ColorUtils.setAlphaComponent(accent, 0x30), CARD, CARD)
).apply {
    cornerRadius = dpf(16f)
    setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x70))
}

// B2. Right after `top.addView(pill(direction, accent))` add the signal id if the backend sends one:
s.str("id").takeIf { it.isNotEmpty() }?.let {
    val idTv = makeText("#" + it.takeLast(6), 11f, MUTED)
    idTv.setPadding(dp(8), 0, 0, 0)
    top.addView(idTv)
}
