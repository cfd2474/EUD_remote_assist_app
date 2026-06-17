package com.cfd2474.eudremoteassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cfd2474.eudremoteassist.session.RemoteSessionState

class RemoteAssistAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "RemoteAssistAccess"

        @Volatile
        var instance: RemoteAssistAccessibilityService? = null
            private set
    }

    private val serviceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoAcceptRunnable = Runnable {
        if (!RemoteSessionState.isSessionActive) return@Runnable
        val root = rootInActiveWindow
        findAndClickMediaProjectionButtons(root)
        checkAndDismissLockScreen(root)
    }

    private var lastSwipeTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "RemoteAssistAccessibilityService connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !RemoteSessionState.isSessionActive) return

        // Auto-accept Screen Share / Media Projection dialog
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val packageName = event.packageName?.toString() ?: ""
            
            // System UI, Android System, or our app is responsible for this dialog
            if (packageName.contains("systemui") || 
                packageName.contains("android") || 
                packageName.contains("cfd2474") || 
                packageName.isEmpty()) {
                // Try multiple times as the window might still be loading
                serviceHandler.removeCallbacks(autoAcceptRunnable)
                serviceHandler.postDelayed(autoAcceptRunnable, 100)
                serviceHandler.postDelayed(autoAcceptRunnable, 200)
                serviceHandler.postDelayed(autoAcceptRunnable, 500)
                serviceHandler.postDelayed(autoAcceptRunnable, 1000)
                serviceHandler.postDelayed(autoAcceptRunnable, 2000)
            }
        }
    }

    private fun checkAndDismissLockScreen(node: AccessibilityNodeInfo?) {
        if (node == null) return
        
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        
        if (!isLocked) return

        // Anti-bounce: Don't swipe more than once every 3 seconds
        if (System.currentTimeMillis() - lastSwipeTime < 3000) {
            return
        }

        // Only auto-dismiss if a session was recently started or is active
        if (!RemoteSessionState.isSessionActive) {
            return
        }

        // Check package names - Lock screen is in systemui
        val packageName = node.packageName?.toString() ?: ""
        if (packageName.contains("systemui") || packageName.contains("android") || packageName.isEmpty()) {
            val metrics = resources.displayMetrics
            val middleX = metrics.widthPixels / 2f
            
            // Aggressive swipe: start very low, end very high
            val startY = metrics.heightPixels * 0.9f
            val endY = metrics.heightPixels * 0.1f
            
            Log.d(TAG, "Lock screen detected while session active. Attempting aggressive swipe up.")
            
            val swipePath = Path().apply {
                moveTo(middleX, startY)
                lineTo(middleX, endY)
            }
            
            // Duration matters: 200ms is a fast flick. 
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 200))
            
            lastSwipeTime = System.currentTimeMillis()

            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Swipe gesture completed")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Swipe gesture cancelled")
                }
            }, null)
        }
    }

    fun performRemoteUnlock(pin: String) {
        Log.i(TAG, "Initiating remote unlock sequence")
        
        // 1. Swipe up to reveal PIN pad
        val metrics = resources.displayMetrics
        val middleX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.8f
        val endY = metrics.heightPixels * 0.2f
        
        val swipePath = Path().apply {
            moveTo(middleX, startY)
            lineTo(middleX, endY)
        }
        val swipeGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 200))
            .build()
            
        dispatchGesture(swipeGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Manual remote unlock swipe completed")
                // 2. Wait for PIN pad to animate in, then enter digits
                serviceHandler.postDelayed({
                    enterPinDigits(pin)
                }, 800)
            }
        }, null)
    }

    private fun enterPinDigits(pin: String, index: Int = 0) {
        if (index >= pin.length) {
            // 3. Finalize entry (Click Enter/Done if needed)
            serviceHandler.postDelayed({
                rootInActiveWindow?.let { finalizePinEntry(it) }
            }, 500)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "Root is null during PIN entry")
            return
        }

        val digit = pin[index]
        val digitString = digit.toString()
        val nodes = root.findAccessibilityNodeInfosByText(digitString)
        
        var clicked = false
        for (node in nodes) {
            // Verify it's a keypad button (usually in systemui)
            if (node.isClickable && node.packageName?.contains("systemui") == true) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clicked = true
                break
            }
        }
        
        if (!clicked) {
            Log.w(TAG, "Could not find button for digit: $digit")
        }

        // Schedule next digit
        serviceHandler.postDelayed({
            enterPinDigits(pin, index + 1)
        }, 200)
    }

    private fun finalizePinEntry(root: AccessibilityNodeInfo) {
        // Some devices auto-unlock after the last digit, others need an 'Enter' click.
        val finalizeButtons = listOf("Enter", "Done", "OK", "checkmark")
        val finalizeIds = listOf("android:id/button1", "com.android.systemui:id/key_enter")
        
        for (id in finalizeIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }

        for (text in finalizeButtons) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
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
                    Log.d(TAG, "Auto-acting on ID: $id")
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
                    Log.d(TAG, "Auto-clicking text: $text")
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
        Log.w(TAG, "RemoteAssistAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "RemoteAssistAccessibilityService destroyed")
        if (instance === this) {
            instance = null
        }
    }
}
