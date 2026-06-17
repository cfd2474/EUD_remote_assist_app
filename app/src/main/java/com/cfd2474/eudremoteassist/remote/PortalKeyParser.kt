package com.cfd2474.eudremoteassist.remote

import android.view.KeyEvent
import java.util.Locale

object PortalKeyParser {
    data class ParsedKey(val keyCode: Int, val metaState: Int)

    private val aliases = mapOf(
        "BACK" to KeyEvent.KEYCODE_BACK,
        "HOME" to KeyEvent.KEYCODE_HOME,
        "RECENTS" to KeyEvent.KEYCODE_APP_SWITCH,
        "DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "KEYCODE_DEL" to KeyEvent.KEYCODE_DEL,
        "BACKSPACE" to KeyEvent.KEYCODE_DEL,
        "KEYCODE_FORWARD_DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
        "KEYCODE_ENTER" to KeyEvent.KEYCODE_ENTER,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "KEYCODE_TAB" to KeyEvent.KEYCODE_TAB,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "KEYCODE_ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "KEYCODE_SPACE" to KeyEvent.KEYCODE_SPACE,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "KEYCODE_MOVE_END" to KeyEvent.KEYCODE_MOVE_END,
        "KEYCODE_PAGE_UP" to KeyEvent.KEYCODE_PAGE_UP,
        "KEYCODE_PAGE_DOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "KEYCODE_INSERT" to KeyEvent.KEYCODE_INSERT,
        "KEYCODE_CAPS_LOCK" to KeyEvent.KEYCODE_CAPS_LOCK,
    )

    fun parse(raw: String): ParsedKey? {
        var meta = 0
        var token = raw.trim()

        if (token.contains("+")) {
            val parts = token.split("+").map { it.trim() }
            val keyPart = parts.last()
            for (mod in parts.dropLast(1)) {
                meta = meta or when (mod.lowercase(Locale.US)) {
                    "ctrl" -> KeyEvent.META_CTRL_ON
                    "alt" -> KeyEvent.META_ALT_ON
                    "shift" -> KeyEvent.META_SHIFT_ON
                    "meta" -> KeyEvent.META_META_ON
                    else -> 0
                }
            }
            token = keyPart
        }

        val code = resolveKeyCode(token) ?: return null
        return ParsedKey(code, meta)
    }

    private fun resolveKeyCode(token: String): Int? {
        aliases[token.uppercase(Locale.US)]?.let { return it }

        if (token.startsWith("KEYCODE_", ignoreCase = true)) {
            val suffix = token.substring(8)
            if (suffix.length == 1 && suffix[0] in 'A'..'Z') {
                return KeyEvent.keyCodeFromString("KEYCODE_${suffix.uppercase(Locale.US)}")
            }
            if (suffix.length == 1 && suffix[0] in '0'..'9') {
                return KeyEvent.keyCodeFromString("KEYCODE_$suffix")
            }
            if (suffix.startsWith("F") && suffix.length <= 3) {
                return KeyEvent.keyCodeFromString("KEYCODE_${suffix.uppercase(Locale.US)}")
            }
            return KeyEvent.keyCodeFromString(token.uppercase(Locale.US))
        }

        if (token.length == 1) {
            val upper = token.uppercase(Locale.US)
            if (upper[0] in 'A'..'Z') return KeyEvent.keyCodeFromString("KEYCODE_$upper")
            if (token[0] in '0'..'9') return KeyEvent.keyCodeFromString("KEYCODE_$token")
        }

        return null
    }
}
