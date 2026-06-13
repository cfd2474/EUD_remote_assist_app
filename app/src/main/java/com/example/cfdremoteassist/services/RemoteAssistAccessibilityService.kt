package com.example.cfdremoteassist.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.os.Bundle
import android.view.InputDevice
import android.view.View

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
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performLongPress(xPercent: Float, yPercent: Float) {
        val metrics = resources.displayMetrics
        val x = xPercent * metrics.widthPixels
        val y = yPercent * metrics.heightPixels
        
        Log.d("AccessibilityService", "Performing long press at $x, $y")
        
        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 600))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performSwipe(x1Percent: Float, y1Percent: Float, x2Percent: Float, y2Percent: Float, durationMs: Long = 350) {
        val metrics = resources.displayMetrics
        val x1 = x1Percent * metrics.widthPixels
        val y1 = y1Percent * metrics.heightPixels
        val x2 = x2Percent * metrics.widthPixels
        val y2 = y2Percent * metrics.heightPixels
        
        Log.d("AccessibilityService", "Performing swipe from $x1,$y1 to $x2,$y2 over ${durationMs}ms")
        
        val swipePath = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, durationMs))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun handleKeyAction(key: String, inputMethod: String?) {
        Log.d("AccessibilityService", "Key Action: $key (method: $inputMethod)")
        
        // 1. Navigation / Global Actions
        when (key) {
            "BACK", "KEYCODE_BACK" -> { performGlobalAction(GLOBAL_ACTION_BACK); return }
            "HOME", "KEYCODE_HOME" -> { performGlobalAction(GLOBAL_ACTION_HOME); return }
            "RECENTS", "KEYCODE_APP_SWITCH" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); return }
            "NOTIFICATIONS" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return }
            "QUICK_SETTINGS" -> { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); return }
            "POWER_DIALOG" -> { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); return }
        }

        // 2. Hardware Keyboard Emulation (Experimental)
        if (inputMethod == "hardware_keyboard") {
            // Note: AccessibilityService cannot easily inject raw KeyEvents into other apps.
            // We'll try to find the focused node and perform actions if possible.
            injectHardwareKey(key)
            return
        }

        // 3. Fallback to global action mapping
        performGlobalActionByName(key)
    }

    private fun performGlobalActionByName(action: String) {
        val normalized = if (action.startsWith("KEYCODE_")) action else "KEYCODE_$action"
        
        when (normalized) {
            "KEYCODE_DPAD_UP" -> injectHardwareKey("KEYCODE_DPAD_UP")
            "KEYCODE_DPAD_DOWN" -> injectHardwareKey("KEYCODE_DPAD_DOWN")
            "KEYCODE_DPAD_LEFT" -> injectHardwareKey("KEYCODE_DPAD_LEFT")
            "KEYCODE_DPAD_RIGHT" -> injectHardwareKey("KEYCODE_DPAD_RIGHT")
            "KEYCODE_ENTER", "KEYCODE_NUMPAD_ENTER" -> injectHardwareKey("KEYCODE_ENTER")
            "KEYCODE_DEL", "KEYCODE_BACKSPACE" -> injectHardwareKey("KEYCODE_DEL")
            "KEYCODE_SPACE" -> injectHardwareKey("KEYCODE_SPACE")
            "KEYCODE_TAB" -> injectHardwareKey("KEYCODE_TAB")
            "KEYCODE_ESCAPE" -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> {
                // If it's a single character or starts with KEYCODE_ and has one char after
                if (action.length == 1) {
                    injectChar(action[0])
                } else if (normalized.length == 9 && normalized.startsWith("KEYCODE_")) {
                    injectChar(normalized.last())
                }
            }
        }
    }

    private fun injectHardwareKey(keyName: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        when (keyName) {
            "KEYCODE_ENTER" -> {
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    // Try to click center of screen as fallback for DPAD_CENTER/ENTER
                    performClick(0.5f, 0.5f)
                }
            }
            "KEYCODE_DEL" -> {
                if (node != null && node.className?.contains("EditText") == true) {
                    val currentText = (node.text ?: "").toString()
                    if (currentText.isNotEmpty()) {
                        val bundle = Bundle()
                        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentText.dropLast(1))
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    }
                }
            }
            "KEYCODE_DPAD_UP" -> performSwipe(0.5f, 0.6f, 0.5f, 0.4f, 100)
            "KEYCODE_DPAD_DOWN" -> performSwipe(0.5f, 0.4f, 0.5f, 0.6f, 100)
            "KEYCODE_DPAD_LEFT" -> performSwipe(0.6f, 0.5f, 0.4f, 0.5f, 100)
            "KEYCODE_DPAD_RIGHT" -> performSwipe(0.4f, 0.5f, 0.6f, 0.5f, 100)
            "KEYCODE_TAB" -> {
                // Accessibility focus move
                rootInActiveWindow?.focusSearch(View.FOCUS_FORWARD)?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
        }
    }

    private fun injectChar(char: Char) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null) {
            val bundle = Bundle()
            val newText = (node.text ?: "").toString() + char
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            
            // Move cursor to end
            val cursorBundle = Bundle()
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorBundle)
        }
    }

    companion object {
        var instance: RemoteAssistAccessibilityService? = null
    }
}
