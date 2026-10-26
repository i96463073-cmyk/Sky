package com.sky.agent

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var pendingContainer: LinearLayout
    private lateinit var completedContainer: LinearLayout
    private lateinit var btnRefresh: Button

    // Your Cloudflare backend URL
    private val BACKEND_BASE = "https://sky-ai-backend.i96463073.workers.dev"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pendingContainer = findViewById(R.id.pendingContainer)
        completedContainer = findViewById(R.id.completedContainer)
        btnRefresh = findViewById(R.id.btnRefresh)

        btnRefresh.setOnClickListener {
            fetchSignals()
        }

        // Load data on startup
        fetchSignals()
    }

    private fun fetchSignals() {
        btnRefresh.text = "Loading..."
        btnRefresh.isEnabled = false

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
                    btnRefresh.text = "Refresh Signals"
                    btnRefresh.isEnabled = true
                    
                    if (code in 200..299) {
                        parseAndDisplay(txt)
                    } else {
                        showError("Backend Error: $code")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnRefresh.text = "Refresh Signals"
                    btnRefresh.isEnabled = true
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
                addEmptyText(pendingContainer, "No active trades right now.")
            } else {
                for (i in 0 until pendingArray.length()) {
                    addTradeCard(pendingContainer, pendingArray.getJSONObject(i), isPending = true)
                }
            }

            if (completedArray.length() == 0) {
                addEmptyText(completedContainer, "No completed trades yet.")
            } else {
                for (i in 0 until completedArray.length()) {
                    addTradeCard(completedContainer, completedArray.getJSONObject(i), isPending = false)
                }
            }

        } catch (e: Exception) {
            showError("Parse Error: ${e.message}")
        }
    }

    private fun addTradeCard(container: LinearLayout, trade: JSONObject, isPending: Boolean) {
        val d = resources.displayMetrics.density
        
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val pad = (16 * d).toInt()
        card.setPadding(pad, pad, pad, pad)

        val bg = GradientDrawable()
        bg.cornerRadius = 12f * d
        bg.setColor(Color.parseColor("#1E293B"))
        card.background = bg

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = (12 * d).toInt()
        card.layoutParams = lp

        val symbol = trade.optString("symbol", "UNKNOWN")
        val direction = trade.optString("direction", "NO_TRADE")
        val entry = trade.optDouble("entry", 0.0)
        val result = trade.optString("result", "") // WIN or LOSS for completed trades

        // Top Row: Symbol and Direction
        val topRow = TextView(this)
        topRow.text = "$symbol  •  $direction"
        topRow.setTextColor(if (direction == "BUY") Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
        topRow.textSize = 18f
        topRow.setTypeface(null, android.graphics.Typeface.BOLD)
        card.addView(topRow)

        // Entry Price
        val entryText = TextView(this)
        entryText.text = "Entry: $entry"
        entryText.setTextColor(Color.WHITE)
        entryText.textSize = 14f
        card.addView(entryText)

        // SL / TP
        val slTpText = TextView(this)
        slTpText.text = "SL: ${trade.optDouble("stop_loss")}  |  TP: ${trade.optDouble("take_profit")}"
        slTpText.setTextColor(Color.parseColor("#94A3B8"))
        slTpText.textSize = 14f
        card.addView(slTpText)

        // Status for Completed
        if (!isPending && result.isNotEmpty() && result != "null") {
            val statusText = TextView(this)
            statusText.text = "Result: $result"
            statusText.setTextColor(if (result == "WIN") Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            statusText.textSize = 14f
            statusText.setTypeface(null, android.graphics.Typeface.BOLD)
            card.addView(statusText)
        }

        container.addView(card)
    }

    private fun addEmptyText(container: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.parseColor("#64748B"))
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        container.addView(tv)
    }

    private fun showError(msg: String) {
        val tv = TextView(this)
        tv.text = msg
        tv.setTextColor(Color.RED)
        pendingContainer.addView(tv)
    }
}
