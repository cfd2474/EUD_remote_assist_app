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
import android.app.KeyguardManager
import android.content.Context
import com.example.cfdremoteassist.remote.ChainedKeyInjector
import com.example.cfdremoteassist.remote.RemoteControlHandler
import com.example.cfdremoteassist.remote.RemoteSessionManager
import com.example.cfdremoteassist.remote.ShellKeyInjector
import org.json.JSONObject

class RemoteAssistAccessibilityService : AccessibilityService() {

    private lateinit var controlHandler: RemoteControlHandler

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
        val root = rootInActiveWindow
        findAndClickMediaProjectionButtons(root)
        checkAndDismissLockScreen(root)
    }

    private fun checkAndDismissLockScreen(node: AccessibilityNodeInfo?) {
        if (node == null) return
        
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        
        if (!isLocked) return

        // Only auto-dismiss if a session was recently started or is active
        if (!RemoteSessionManager.isSessionActive) {
            // We could also check a timestamp here if needed
            return
        }
        
        val packageName = node.packageName?.toString() ?: ""
        if (packageName.contains("systemui")) {
            val metrics = resources.displayMetrics
            val middleX = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.8f
            val endY = metrics.heightPixels * 0.2f
            
            Log.d("AccessibilityService", "Lock screen detected while session active. Attempting swipe up.")
            
            val swipePath = Path().apply {
                moveTo(middleX, startY)
                lineTo(middleX, endY)
            }
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
            dispatchGesture(gestureBuilder.build(), null, null)
        }
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
        controlHandler = RemoteControlHandler(
            service = this,
            displayWidth = { RemoteSessionManager.displayWidth },
            displayHeight = { RemoteSessionManager.displayHeight }
        )
    }

    /** Called from your device WebSocket client when a message arrives. */
    fun onControlMessage(json: JSONObject) {
        if (::controlHandler.isInitialized) {
            controlHandler.handle(json)
        } else {
            Log.w("AccessibilityService", "controlHandler not initialized")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        var instance: RemoteAssistAccessibilityService? = null
    }
}
