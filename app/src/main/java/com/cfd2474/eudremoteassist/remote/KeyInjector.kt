package com.cfd2474.eudremoteassist.remote

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

interface KeyInjector {
    fun inject(event: KeyEvent): Boolean
}

class AccessibilityKeyInjector(
    private val service: AccessibilityService
) : KeyInjector {
    private val tag = "AccessibilityKeyInjector"

    private var lastNodeWindowId: Int = -1
    private var lastNodeText: String? = null
    private var lastSelectionStart: Int = -1
    private var lastSelectionEnd: Int = -1
    private var lastActionTime: Long = 0
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun inject(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val node = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.d(tag, "No focused input node found for key: ${event.keyCode}")
            resetState()
            return false
        }

        val now = SystemClock.uptimeMillis()
        val currentWindowId = node.windowId
        val currentNodeText = (node.text ?: "").toString()

        // Sync Logic:
        // 1. If window or node changed, we MUST sync.
        // 2. If it's been more than 2.0s since we typed, we sync to capture external changes.
        // 3. Otherwise, we TRUST our shadow buffer to allow high-speed typing.
        val nodeChanged = lastFocusedNode != node
        val timeoutExpired = now - lastActionTime > 2000
        
        if (lastNodeText == null || currentWindowId != lastNodeWindowId || nodeChanged || timeoutExpired) {
            if (nodeChanged || timeoutExpired) {
                Log.d(tag, "Syncing shadow state (Reason: nodeChanged=$nodeChanged, timeout=$timeoutExpired)")
            }
            lastNodeText = currentNodeText
            lastNodeWindowId = currentWindowId
            lastSelectionStart = node.textSelectionStart
            lastSelectionEnd = node.textSelectionEnd
            lastFocusedNode?.recycle()
            lastFocusedNode = AccessibilityNodeInfo.obtain(node)
        }

        lastActionTime = now

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> injectDel(node)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-1, node)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(1, node)
            KeyEvent.KEYCODE_MOVE_HOME -> moveCursor(-9999, node)
            KeyEvent.KEYCODE_MOVE_END -> moveCursor(9999, node)
            KeyEvent.KEYCODE_ENTER -> {
                var acted = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    acted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                if (!acted) acted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!acted) acted = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (acted) resetState() 
                acted
            }
            else -> {
                val unicodeChar = event.getUnicodeChar(event.metaState)
                Log.d(tag, "inject key: ${event.keyCode} | unicode: $unicodeChar")
                if (unicodeChar != 0) {
                    injectChar(unicodeChar.toChar(), node)
                } else {
                    false
                }
            }
        }
    }

    private fun resetState() {
        lastNodeWindowId = -1
        lastNodeText = null
        lastFocusedNode?.recycle()
        lastFocusedNode = null
    }

    private fun injectChar(char: Char, node: AccessibilityNodeInfo): Boolean {
        val text = lastNodeText ?: ""
        val hint = (node.hintText ?: "").toString()
        
        // Handle Chrome placeholder logic: if text equals hint and field is focused, treat as empty
        val effectiveText = if (text == hint && text.isNotEmpty()) "" else text
        
        val start = if (lastSelectionStart < 0) effectiveText.length else lastSelectionStart.coerceAtMost(effectiveText.length)
        val end = if (lastSelectionEnd < 0) effectiveText.length else lastSelectionEnd.coerceAtMost(effectiveText.length)
        
        val sb = StringBuilder(effectiveText)
        try {
            if (start != end) {
                sb.replace(start, end, char.toString())
            } else {
                sb.insert(start, char)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to insert character: ${e.message}", e)
            sb.append(char)
        }
        
        val newText = sb.toString()
        val newPos = start + 1
        
        Log.d(tag, "Shadow SET_TEXT to: '$newText' (cursor: $newPos, prev: '$text')")
        
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        
        if (success) {
            lastNodeText = newText
            lastSelectionStart = newPos
            lastSelectionEnd = newPos
            
            // Move cursor - critical for Chrome address bar
            val cursorBundle = Bundle()
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newPos)
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newPos)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorBundle)
        }
        return success
    }

    private fun moveCursor(delta: Int, node: AccessibilityNodeInfo): Boolean {
        val text = lastNodeText ?: (node.text ?: "").toString()
        if (text.isEmpty()) return true

        val start = if (lastSelectionStart < 0) text.length else lastSelectionStart.coerceAtMost(text.length)
        val end = if (lastSelectionEnd < 0) text.length else lastSelectionEnd.coerceAtMost(text.length)
        
        var newPos: Int
        if (delta < 0) {
            // Left
            newPos = if (start != end) start.coerceAtMost(end) else (start + delta).coerceAtLeast(0)
        } else {
            // Right
            newPos = if (start != end) start.coerceAtLeast(end) else (start + delta).coerceAtMost(text.length)
        }

        Log.d(tag, "Shadow moveCursor to: $newPos (delta: $delta, prev: $start-$end)")
        
        val cursorBundle = Bundle()
        cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newPos)
        cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newPos)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorBundle)
        
        if (success) {
            lastSelectionStart = newPos
            lastSelectionEnd = newPos
        }
        return success
    }

    private fun injectDel(node: AccessibilityNodeInfo): Boolean {
        val text = lastNodeText ?: ""
        if (text.isEmpty()) return true

        val start = if (lastSelectionStart < 0) text.length else lastSelectionStart.coerceAtMost(text.length)
        val end = if (lastSelectionEnd < 0) text.length else lastSelectionEnd.coerceAtMost(text.length)
        
        val sb = StringBuilder(text)
        var newPos: Int
        
        try {
            if (start != end) {
                sb.delete(start.coerceAtMost(end), start.coerceAtLeast(end))
                newPos = start.coerceAtMost(end)
            } else if (start > 0) {
                sb.deleteCharAt(start - 1)
                newPos = start - 1
            } else {
                return true 
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete character: ${e.message}", e)
            return false
        }

        val newText = sb.toString()
        Log.d(tag, "Shadow SET_TEXT (Del) to: '$newText' (cursor: $newPos, prev: '$text')")
        
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        
        if (success) {
            lastNodeText = newText
            lastSelectionStart = newPos
            lastSelectionEnd = newPos

            val cursorBundle = Bundle()
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newPos)
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newPos)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorBundle)
        }
        return success
    }
}

class ShellKeyInjector : KeyInjector {
    override fun inject(event: KeyEvent): Boolean {
        // We only need to inject one event per key press for shell commands
        if (event.action != KeyEvent.ACTION_DOWN) return true 

        return try {
            val cmd = arrayOf("input", "keyevent", event.keyCode.toString())
            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w("KeyInjector", "shell input keyevent failed with exit code $exitCode")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.w("KeyInjector", "shell input keyevent failed", e)
            false
        }
    }
}

class ChainedKeyInjector(
    private vararg val injectors: KeyInjector,
) : KeyInjector {
    override fun inject(event: KeyEvent): Boolean {
        for (injector in injectors) {
            if (injector.inject(event)) return true
        }
        return false
    }
}
