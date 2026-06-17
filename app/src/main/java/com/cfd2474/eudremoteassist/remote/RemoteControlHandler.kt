package com.cfd2474.eudremoteassist.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.cfd2474.eudremoteassist.service.RemoteAssistAccessibilityService
import com.cfd2474.eudremoteassist.session.RemoteSessionState
import com.google.gson.JsonObject

object RemoteControlHandler {
    private const val TAG = "RemoteControl"

    private var cachedKeyInjector: KeyInjector? = null
    private var lastServiceInstance: AccessibilityService? = null

    private fun getKeyInjector(service: AccessibilityService): KeyInjector {
        if (cachedKeyInjector == null || lastServiceInstance != service) {
            lastServiceInstance = service
            cachedKeyInjector = ChainedKeyInjector(
                AccessibilityKeyInjector(service),
                ShellKeyInjector()
            )
        }
        return cachedKeyInjector!!
    }

    fun handleControlMessage(json: JsonObject) {
        val service = RemoteAssistAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service is not running. Cannot perform remote control actions.")
            return
        }

        val action = json.get("action")?.asString ?: return
        Log.i(TAG, "Received control action: $action")

        val resources = service.resources
        val metrics = resources.displayMetrics
        val displayW = if (RemoteSessionState.displayWidth > 0) RemoteSessionState.displayWidth else metrics.widthPixels
        val displayH = if (RemoteSessionState.displayHeight > 0) RemoteSessionState.displayHeight else metrics.heightPixels

        if (displayW <= 0 || displayH <= 0) {
            Log.w(TAG, "Display dimensions not set. Cannot map coordinates.")
            return
        }

        when (action) {
            "CLICK" -> {
                val xPercent = json.get("x_percent")?.asFloat ?: return
                val yPercent = json.get("y_percent")?.asFloat ?: return
                val x = xPercent * displayW
                val y = yPercent * displayH
                Log.i(TAG, "CLICK coordinates: ($xPercent, $yPercent) -> ($x, $y) (display size: ${displayW}x${displayH})")
                click(service, x, y)
            }
            "LONG_PRESS" -> {
                val xPercent = json.get("x_percent")?.asFloat ?: return
                val yPercent = json.get("y_percent")?.asFloat ?: return
                val x = xPercent * displayW
                val y = yPercent * displayH
                Log.i(TAG, "LONG_PRESS coordinates: ($xPercent, $yPercent) -> ($x, $y)")
                longPress(service, x, y)
            }
            "SWIPE" -> {
                val xPercent = json.get("x_percent")?.asFloat ?: return
                val yPercent = json.get("y_percent")?.asFloat ?: return
                val endXPercent = (json.get("x2_percent") ?: json.get("end_x_percent"))?.asFloat ?: xPercent
                val endYPercent = (json.get("y2_percent") ?: json.get("end_y_percent"))?.asFloat ?: yPercent
                val duration = json.get("duration_ms")?.asLong ?: 300L

                val startX = xPercent * displayW
                val startY = yPercent * displayH
                val endX = endXPercent * displayW
                val endY = endYPercent * displayH
                Log.i(TAG, "SWIPE starting: ($startX, $startY) to ($endX, $endY) over ${duration}ms")
                swipe(service, startX, startY, endX, endY, duration)
            }
            "KEY" -> {
                val key = json.get("key")?.asString ?: return
                Log.i(TAG, "KEY action: $key")
                handleKey(service, key)
            }
        }
    }

    private fun click(service: AccessibilityService, x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y) // Essential to make path valid for gesture dispatch
        }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 50)
        val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
        service.dispatchGesture(gestureDescription, null, null)
    }

    private fun longPress(service: AccessibilityService, x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y) // Essential to make path valid for gesture dispatch
        }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 1000)
        val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
        service.dispatchGesture(gestureDescription, null, null)
    }

    private fun swipe(service: AccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
        service.dispatchGesture(gestureDescription, null, null)
    }

    private fun handleKey(service: AccessibilityService, key: String) {
        when (key.uppercase()) {
            "BACK" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "HOME" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "RECENTS" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "POWER_DIALOG" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            else -> {
                val parsed = PortalKeyParser.parse(key)
                if (parsed != null) {
                    val keyeventDown = KeyEvent(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_DOWN,
                        parsed.keyCode,
                        0,
                        parsed.metaState
                    )
                    val keyeventUp = KeyEvent(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP,
                        parsed.keyCode,
                        0,
                        parsed.metaState
                    )
                    
                    val injector = getKeyInjector(service)
                    val okDown = injector.inject(keyeventDown)
                    
                    // Add a small delay between DOWN and UP
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        injector.inject(keyeventUp)
                    }, 50L)
                } else {
                    Log.w(TAG, "Unmapped key: $key")
                }
            }
        }
    }
}
