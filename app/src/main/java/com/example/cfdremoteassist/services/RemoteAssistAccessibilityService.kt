package com.example.cfdremoteassist.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RemoteAssistAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Auto-accept Screen Share / Media Projection dialog
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val packageName = event.packageName?.toString() ?: ""
            
            // System UI or Android System is usually responsible for this dialog
            if (packageName.contains("systemui") || packageName.contains("android") || packageName.isEmpty()) {
                // Try multiple times as the window might still be loading
                serviceHandler.removeCallbacks(autoAcceptRunnable)
                serviceHandler.postDelayed(autoAcceptRunnable, 200)
                serviceHandler.postDelayed(autoAcceptRunnable, 500)
                serviceHandler.postDelayed(autoAcceptRunnable, 1000)
            }
        }
    }

    private val serviceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoAcceptRunnable = Runnable {
        findAndClickMediaProjectionButtons(rootInActiveWindow)
    }

    private fun findAndClickMediaProjectionButtons(node: AccessibilityNodeInfo?) {
        if (node == null) return

        // 1. Look for specific system button IDs and text patterns
        val textToFind = listOf("Start now", "Allow", "Entire screen", "Start recording", "START NOW", "ALLOW")
        val idsToFind = listOf(
            "android:id/button1", // Standard "OK/Positive" button ID
            "com.android.systemui:id/remember_checkbox",
            "com.android.systemui:id/button_start_now",
            "com.android.systemui:id/start_button"
        )
        
        // Try IDs first (more reliable)
        for (id in idsToFind) {
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            for (foundNode in nodes) {
                if (foundNode.isClickable || foundNode.isCheckable) {
                    Log.d("AccessibilityService", "Auto-acting on ID: $id")
                    foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (foundNode.isCheckable) continue 
                }
            }
        }

        // Try Text patterns
        for (text in textToFind) {
            val nodes = node.findAccessibilityNodeInfosByText(text)
            for (foundNode in nodes) {
                if (foundNode.isClickable) {
                    Log.d("AccessibilityService", "Auto-clicking text: $text")
                    foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }

        // 2. Recursive search for children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findAndClickMediaProjectionButtons(child)
        }
    }

    override fun onInterrupt() {
        Log.d("AccessibilityService", "Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AccessibilityService", "Service Connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // Remote Control Implementation
    fun performClick(xPercent: Float, yPercent: Float) {
        val metrics = resources.displayMetrics
        val x = xPercent * metrics.widthPixels
        val y = yPercent * metrics.heightPixels
        
        Log.d("AccessibilityService", "Performing click at $x, $y ($xPercent, $yPercent)")
        
        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 100))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performSwipe(xPercent: Float, yPercent: Float, x2Percent: Float, y2Percent: Float) {
        val metrics = resources.displayMetrics
        val x1 = xPercent * metrics.widthPixels
        val y1 = yPercent * metrics.heightPixels
        val x2 = x2Percent * metrics.widthPixels
        val y2 = yPercent * metrics.heightPixels
        
        Log.d("AccessibilityService", "Performing swipe from $x1,$y1 to $x2,$y2")
        
        val swipePath = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 400))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performGlobalAction(action: String) {
        Log.d("AccessibilityService", "Performing global action: $action")
        when (action) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "POWER_DIALOG" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }
    }

    companion object {
        var instance: RemoteAssistAccessibilityService? = null
    }
}
