package com.example.cfdremoteassist.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Locale

class RemoteControlHandler(
    private val service: AccessibilityService,
    /** Physical display width in pixels — `WindowManager.currentWindowMetrics.bounds.width()` */
    private val displayWidth: () -> Int,
    /** Physical display height in pixels — `WindowManager.currentWindowMetrics.bounds.height()` */
    private val displayHeight: () -> Int,
    private val keyInjector: KeyInjector = ChainedKeyInjector(InstrumentationKeyInjector(), ShellKeyInjector()),
) {
    private val tag = "RemoteControlHandler"

    fun handle(message: JSONObject) {
        if (message.optString("type") != "control") return

        when (message.optString("action")) {
            "CLICK" -> injectClick(
                message.optDouble("x_percent", 0.0),
                message.optDouble("y_percent", 0.0),
                message
            )
            "SWIPE" -> injectSwipe(
                message.optDouble("x_percent", 0.0),
                message.optDouble("y_percent", 0.0),
                message.optDouble("x2_percent", 0.0),
                message.optDouble("y2_percent", 0.0),
                message.optLong("duration_ms", 350L),
                message
            )
            "LONG_PRESS" -> injectLongPress(
                message.optDouble("x_percent", 0.0),
                message.optDouble("y_percent", 0.0),
                message
            )
            "KEY" -> injectKey(
                message.optString("key"),
                message.optString("input_method"),
            )
            else -> Log.w(tag, "Unknown control action: ${message.optString("action")}")
        }
    }

    private fun toX(xPercent: Double): Float {
        val w = displayWidth().coerceAtLeast(1)
        return (xPercent * w).toFloat().coerceIn(0f, w - 1f)
    }

    private fun toY(yPercent: Double): Float {
        val h = displayHeight().coerceAtLeast(1)
        return (yPercent * h).toFloat().coerceIn(0f, h - 1f)
    }

    private fun logScaleHint(message: JSONObject) {
        val sw = message.optInt("stream_width", 0)
        val sh = message.optInt("stream_height", 0)
        if (sw > 0 && sh > 0) {
            val dw = displayWidth()
            val dh = displayHeight()
            Log.d(tag, "display=${dw}x${dh} stream=${sw}x${sh}")
        }
    }

    private fun injectClick(xPercent: Double, yPercent: Double, message: JSONObject) {
        val x = toX(xPercent)
        val y = toY(yPercent)
        logScaleHint(message)
        dispatchStroke(x, y, x, y, durationMs = 50L)
        Log.d(tag, "CLICK at $x,$y (${displayWidth()}x${displayHeight()})")
    }

    private fun injectLongPress(xPercent: Double, yPercent: Double, message: JSONObject) {
        val x = toX(xPercent)
        val y = toY(yPercent)
        logScaleHint(message)
        dispatchStroke(x, y, x, y, durationMs = 600L)
        Log.d(tag, "LONG_PRESS at $x,$y")
    }

    private fun injectSwipe(
        x1Percent: Double,
        y1Percent: Double,
        x2Percent: Double,
        y2Percent: Double,
        durationMs: Long,
        message: JSONObject
    ) {
        val x1 = toX(x1Percent)
        val y1 = toY(y1Percent)
        val x2 = toX(x2Percent)
        val y2 = toY(y2Percent)
        val duration = durationMs.coerceIn(100L, 2000L)
        logScaleHint(message)
        dispatchStroke(x1, y1, x2, y2, durationMs = duration)
        Log.d(tag, "SWIPE ($x1,$y1)→($x2,$y2) ${duration}ms")
    }

    private fun dispatchStroke(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun injectKey(key: String, inputMethod: String) {
        if (key.isBlank()) return

        // Navigation shortcuts — no KeyEvent needed
        when (key.uppercase(Locale.US)) {
            "BACK" -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }
            "HOME" -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return
            }
            "RECENTS" -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                return
            }
        }

        val parsed = PortalKeyParser.parse(key)
        if (parsed == null) {
            Log.w(tag, "Unmapped key: $key")
            return
        }

        val source = when (inputMethod) {
            "hardware_keyboard" -> InputDevice.SOURCE_KEYBOARD
            else -> InputDevice.SOURCE_KEYBOARD
        }

        val down = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            parsed.keyCode,
            0,
            parsed.metaState,
            0,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            source,
        )
        val up = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            parsed.keyCode,
            0,
            parsed.metaState,
            0,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            source,
        )

        val okDown = keyInjector.inject(down)
        val okUp = keyInjector.inject(up)
        Log.d(tag, "KEY $key → keyCode=${parsed.keyCode} meta=${parsed.metaState} down=$okDown up=$okUp")
    }
}
