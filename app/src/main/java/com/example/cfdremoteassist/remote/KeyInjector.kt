package com.example.cfdremoteassist.remote

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
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

    override fun inject(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val node = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.d(tag, "No focused input node found for key: ${event.keyCode}")
            resetState()
            return false
        }

        // Detect focus change or external text change
        val currentNodeText = (node.text ?: "").toString()
        val currentWindowId = node.windowId
        
        if (currentWindowId != lastNodeWindowId || 
            (lastNodeText != null && !currentNodeText.startsWith(lastNodeText!!))) {
            // Focus changed or text changed in a way we didn't expect (e.g. user cleared it)
            lastNodeText = currentNodeText
            lastNodeWindowId = currentWindowId
            lastSelectionStart = node.textSelectionStart
            lastSelectionEnd = node.textSelectionEnd
            Log.d(tag, "Focus/Text changed externally. Resetting shadow state to: '$lastNodeText'")
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> injectDel(node)
            KeyEvent.KEYCODE_ENTER -> {
                var acted = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    acted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                if (!acted) acted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!acted) acted = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (acted) resetState() // Assume enter might change focus
                acted
            }
            else -> {
                val unicodeChar = event.getUnicodeChar(event.metaState)
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
    }

    private fun injectChar(char: Char, node: AccessibilityNodeInfo): Boolean {
        val hint = (node.hintText ?: "").toString()
        val text = lastNodeText ?: (node.text ?: "").toString()
        
        // Handle Chrome placeholder logic
        val effectiveText = if (text == hint && text.isNotEmpty() && node.isFocused) "" else text
        
        val start = if (lastSelectionStart < 0) effectiveText.length else lastSelectionStart.coerceAtMost(effectiveText.length)
        val end = if (lastSelectionEnd < 0) effectiveText.length else lastSelectionEnd.coerceAtMost(effectiveText.length)
        
        val sb = StringBuilder(effectiveText)
        try {
            if (start <= end) {
                sb.replace(start, end, char.toString())
            } else {
                sb.append(char)
            }
        } catch (e: Exception) {
            sb.append(char)
        }
        
        val newText = sb.toString()
        val newPos = if (start <= end) start + 1 else newText.length
        
        Log.d(tag, "Shadow SET_TEXT to: '$newText' (cursor: $newPos)")
        
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

    private fun injectDel(node: AccessibilityNodeInfo): Boolean {
        val text = lastNodeText ?: (node.text ?: "").toString()
        val hint = (node.hintText ?: "").toString()
        val effectiveText = if (text == hint && text.isNotEmpty() && node.isFocused) "" else text
        
        if (effectiveText.isEmpty()) return true

        val start = if (lastSelectionStart < 0) effectiveText.length else lastSelectionStart.coerceAtMost(effectiveText.length)
        val end = if (lastSelectionEnd < 0) effectiveText.length else lastSelectionEnd.coerceAtMost(effectiveText.length)
        
        val sb = StringBuilder(effectiveText)
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
            return false
        }

        val newText = sb.toString()
        Log.d(tag, "Shadow SET_TEXT (Del) to: '$newText' (cursor: $newPos)")
        
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

/**
 * Fallback injector that uses the 'input keyevent' shell command.
 * Requires device-owner or privileged shell permissions on managed devices.
 */
class ShellKeyInjector : KeyInjector {
    override fun inject(event: KeyEvent): Boolean {
        // We only need to inject one event per key press for shell commands
        if (event.action != KeyEvent.ACTION_DOWN) return true 

        return try {
            val cmd = arrayOf("sh", "-c", "input keyevent ${event.keyCode}")
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
