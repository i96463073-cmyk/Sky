package com.sky.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

class SkyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SkyAccessibilityService? = null
        private const val TAG = "SkyA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------- NAVIGATION ----------
    fun goBack(): Boolean {
        return try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (e: Exception) { false }
    }

    fun goHome(): Boolean {
        return try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (e: Exception) { false }
    }

    // ---------- READ ----------
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) sb.append(t).append(" | ")
        if (d.isNotEmpty() && d != t) sb.append(d).append(" | ")
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb)
        }
    }

    // ---------- TAP ----------
    fun tapText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val lower = target.lowercase().trim()
        if (lower.isEmpty()) return false

        val exact = find(root) { node ->
            val t = node.text?.toString()?.lowercase()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
            t == lower || d == lower
        }
        if (tryClick(exact)) return true

        val contains = find(root) { node ->
            val t = node.text?.toString()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            t.contains(lower) || d.contains(lower)
        }
        if (tryClick(contains)) return true

        return false
    }

    private fun tryClick(nodes: List<AccessibilityNodeInfo>): Boolean {
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                current = current.parent
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                if (tapAt(rect.exactCenterX(), rect.exactCenterY())) return true
            }
        }
        return false
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        walkFind(node, predicate, out)
        return out
    }

    private fun walkFind(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (predicate(node)) out.add(node)
        for (i in 0 until node.childCount) {
            walkFind(node.getChild(i), predicate, out)
        }
    }

    // ---------- TAP AT COORDINATES ----------
    fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "tapAt failed", e)
            false
        }
    }

    // ---------- SCREENSHOT ----------
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        try {
            val executor = Executors.newSingleThreadExecutor()
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            callback(copy)
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot wrap failed", e)
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed code=$errorCode")
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot threw", e)
            callback(null)
        }
    }

    // ---------- TYPE ----------
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused == null) return false

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------- SCROLL ----------
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return scrollNode(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return scrollNode(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun scrollNode(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if (node == null) return false
        if (node.isScrollable) {
            if (node.performAction(action)) return true
        }
        for (i in 0 until node.childCount) {
            if (scrollNode(node.getChild(i), action)) return true
        }
        return false
    }
}
