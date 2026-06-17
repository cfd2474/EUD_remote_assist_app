package com.cfd2474.eudremoteassist.session

object RemoteSessionState {
    @Volatile var isSessionActive: Boolean = false
    @Volatile var captureWidth: Int = 0      // WebRTC buffer (typically display/2)
    @Volatile var captureHeight: Int = 0
    @Volatile var displayWidth: Int = 0      // Physical pixels — for touch + ORIENTATION_CHANGED
    @Volatile var displayHeight: Int = 0

    fun reset() {
        isSessionActive = false
        captureWidth = 0
        captureHeight = 0
        displayWidth = 0
        displayHeight = 0
    }
}
