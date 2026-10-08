package com.sky.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SkyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SkyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // not used
    }

    override fun onInterrupt() {
        // not used
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

        // 1. Exact match
        val exact = find(root) { node ->
            val t = node.text?.toString()?.lowercase()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
            t == lower || d == lower
        }
        if (tryClick(exact)) return true

        // 2. Contains (substring)
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
            // Try clickable ancestor
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                current = current.parent
            }
            // Fallback: tap center of node bounds via gesture
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                val path = Path()
                path.moveTo(rect.exactCenterX(), rect.exactCenterY())
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                    .build()
                if (dispatchGesture(gesture, null, null)) return true
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
