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

    override fun inject(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val node = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.d(tag, "No focused input node found for key: ${event.keyCode}")
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> injectDel(node)
            KeyEvent.KEYCODE_ENTER -> {
                // For enter, we try to trigger the IME search/go action or click the node
                var acted = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    acted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                if (!acted) acted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!acted) acted = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
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

    private fun injectChar(char: Char, node: AccessibilityNodeInfo): Boolean {
        val text = (node.text ?: "").toString()
        val hint = (node.hintText ?: "").toString()
        
        Log.d(tag, "injectChar '$char' | currentText: '$text' | hint: '$hint' | focused: ${node.isFocused}")
        
        // Browsers like Chrome sometimes show hint in text property when empty
        val effectiveText = if (text == hint && text.isNotEmpty() && node.isFocused) "" else text
        
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        
        // If selection is -1, it usually means cursor is at the end or not provided
        val start = if (selStart < 0) effectiveText.length else selStart.coerceAtMost(effectiveText.length)
        val end = if (selEnd < 0) effectiveText.length else selEnd.coerceAtMost(effectiveText.length)
        
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
        Log.d(tag, "SET_TEXT to: '$newText' (selection: $start-$end)")
        
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        
        if (success) {
            // Move cursor to after the inserted char
            val newPos = if (start <= end) start + 1 else newText.length
            val cursorBundle = Bundle()
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newPos)
            cursorBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newPos)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorBundle)
        }
        return success
    }

    private fun injectDel(node: AccessibilityNodeInfo): Boolean {
        val text = (node.text ?: "").toString()
        val hint = (node.hintText ?: "").toString()
        val effectiveText = if (text == hint && text.isNotEmpty() && node.isFocused) "" else text
        
        Log.d(tag, "injectDel | currentText: '$text' | effective: '$effectiveText' | focused: ${node.isFocused}")
        
        if (effectiveText.isEmpty()) return true

        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        
        val sb = StringBuilder(effectiveText)
        var newPos: Int
        
        try {
            if (selStart != -1 && selEnd != -1 && selStart != selEnd) {
                // Delete selection
                sb.delete(selStart.coerceAtMost(selEnd), selStart.coerceAtLeast(selEnd))
                newPos = selStart.coerceAtMost(selEnd)
            } else if (selStart > 0) {
                // Delete one char before cursor
                sb.deleteCharAt(selStart - 1)
                newPos = selStart - 1
            } else if (selStart == -1) {
                // No selection info, delete from end
                sb.deleteCharAt(sb.length - 1)
                newPos = sb.length
            } else {
                return true // cursor at 0, nothing to delete
            }
        } catch (e: Exception) {
            if (effectiveText.isNotEmpty()) {
                sb.setLength(effectiveText.length - 1)
                newPos = sb.length
            } else return true
        }

        val newText = sb.toString()
        Log.d(tag, "SET_TEXT (Del) to: '$newText'")
        
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        
        if (success) {
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
