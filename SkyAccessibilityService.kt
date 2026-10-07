package com.sky.agent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class SkyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SkyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Sky can observe permitted screen events here.
    }

    override fun onInterrupt() {
    }

    // -----------------------------
    // TAP VISIBLE TEXT
    // -----------------------------

    fun tapText(text: String): Boolean {

        val root = rootInActiveWindow ?: return false

        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {

            if (node.isClickable) {
                val result = node.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )

                node.recycle()

                if (result) return true
            }

            var parent = node.parent

            while (parent != null) {

                if (parent.isClickable) {

                    val result = parent.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )

                    parent.recycle()
                    node.recycle()

                    if (result) return true
                    break
                }

                val next = parent.parent

                parent.recycle()
                parent = next
            }

            node.recycle()
        }

        return false
    }

    // -----------------------------
    // TYPE TEXT
    // -----------------------------

    fun typeText(text: String): Boolean {

        val root = rootInActiveWindow ?: return false

        val focused = root.findFocus(
            AccessibilityNodeInfo.FOCUS_INPUT
        ) ?: return false

        val arguments = Bundle()

        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        val result = focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )

        focused.recycle()

        return result
    }

    // -----------------------------
    // BACK
    // -----------------------------

    fun goBack(): Boolean {
        return performGlobalAction(
            GLOBAL_ACTION_BACK
        )
    }

    // -----------------------------
    // HOME
    // -----------------------------

    fun goHome(): Boolean {
        return performGlobalAction(
            GLOBAL_ACTION_HOME
        )
    }

    // -----------------------------
    // READ CURRENT SCREEN
    // -----------------------------

    fun readScreen(): String {

        val root = rootInActiveWindow
            ?: return "I cannot read the current screen."

        val result = StringBuilder()

        collectText(root, result)

        root.recycle()

        return result.toString().trim()
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        result: StringBuilder
    ) {

        node.text?.let {

            if (it.isNotBlank()) {
                result.append(it)
                result.append("\n")
            }
        }

        node.contentDescription?.let {

            if (it.isNotBlank()) {
                result.append(it)
                result.append("\n")
            }
        }

        for (i in 0 until node.childCount) {

            val child = node.getChild(i)

            if (child != null) {
                collectText(child, result)
                child.recycle()
            }
        }
    }
}
