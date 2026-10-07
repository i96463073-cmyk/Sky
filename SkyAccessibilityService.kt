package com.sky.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class SkyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // V1 foundation: observe permitted UI events. Action execution is added after the permission firewall.
    }
    override fun onInterrupt() {}
}
