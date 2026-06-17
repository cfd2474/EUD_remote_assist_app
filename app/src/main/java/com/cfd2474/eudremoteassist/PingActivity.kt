package com.cfd2474.eudremoteassist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import com.cfd2474.eudremoteassist.network.NetworkManager
import com.google.gson.JsonObject
import java.util.concurrent.Executors

class PingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PingActivity"
        private const val RING_TIMEOUT_MS = 120000L // 2 minutes
    }

    private var ringtone: Ringtone? = null
    private var audioManager: AudioManager? = null
    private var originalVolume: Int = -1
    private var originalRingerMode: Int = -1
    private var isCleanedUp = false

    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private lateinit var config: ManagedConfigManager
    private lateinit var networkManager: NetworkManager

    private val dismissRunnable = Runnable {
        Log.i(TAG, "Ringing timeout reached (2 minutes). Dismissing alert.")
        dismissAndStop(timeout = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "PingActivity onCreate")

        config = ManagedConfigManager(this)
        networkManager = NetworkManager.getInstance(this, config)

        // Dismiss keyguard and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Initialize audio settings and start play
        setupAndPlayAudio()

        setContent {
            PingScreen(
                onAcknowledge = {
                    Log.i(TAG, "User clicked Acknowledge Alert button.")
                    dismissAndStop(timeout = false)
                }
            )
        }

        // Schedule timeout self-dismiss
        handler.postDelayed(dismissRunnable, RING_TIMEOUT_MS)
    }

    private fun setupAndPlayAudio() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.let { am ->
                // Capture original settings
                originalVolume = am.getStreamVolume(AudioManager.STREAM_RING)
                originalRingerMode = am.ringerMode

                // Override silent/vibrate mode if active
                if (originalRingerMode != AudioManager.RINGER_MODE_NORMAL) {
                    Log.i(TAG, "Overriding silent/vibrate mode to RINGER_MODE_NORMAL")
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }

                // Set stream volume to maximum
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                Log.i(TAG, "Setting stream volume to MAX ($maxVolume). Original: $originalVolume")
                am.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            }

            // Load and play standard ringtone
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }

            ringtone?.play()
            Log.i(TAG, "Ringtone playing successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup or play audio: ${e.message}", e)
        }
    }

    private fun dismissAndStop(timeout: Boolean) {
        if (isCleanedUp) return
        isCleanedUp = true

        handler.removeCallbacks(dismissRunnable)

        // Stop Ringtone
        try {
            if (ringtone?.isPlaying == true) {
                ringtone?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
        ringtone = null

        // Restore Audio Settings
        try {
            audioManager?.let { am ->
                if (originalVolume != -1) {
                    Log.i(TAG, "Restoring ringtone volume to: $originalVolume")
                    am.setStreamVolume(AudioManager.STREAM_RING, originalVolume, 0)
                }
                if (originalRingerMode != -1 && originalRingerMode != am.ringerMode) {
                    Log.i(TAG, "Restoring ringer mode to: $originalRingerMode")
                    am.ringerMode = originalRingerMode
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring audio settings: ${e.message}")
        }

        // Post events to Server
        backgroundExecutor.execute {
            try {
                if (!timeout) {
                    Log.i(TAG, "Sending PING_ACKNOWLEDGED event")
                    val ackPayload = JsonObject().apply {
                        addProperty("status", "dismissed_by_user")
                    }
                    networkManager.sendEvent("PING_ACKNOWLEDGED", ackPayload)
                }

                Log.i(TAG, "Sending PING_COMPLETED event")
                val completedPayload = JsonObject().apply {
                    addProperty("reason", if (timeout) "timeout" else "user_acknowledged")
                }
                networkManager.sendEvent("PING_COMPLETED", completedPayload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send server event notifications: ${e.message}")
            }
        }

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "PingActivity onDestroy")
        dismissAndStop(timeout = false)
        backgroundExecutor.shutdown()
    }

    @Composable
    fun PingScreen(onAcknowledge: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A), // Slate 900
                            Color(0xFF1E293B)  // Slate 800
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight()
                    .border(1.dp, Color(0xFF38BDF8), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x1F1E293B)) // Glassmorphic translucent Slate
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Custom Pulsing Bell Indicator
                    PulsingBellIcon()

                    Text(
                        text = "DEVICE LOCATOR ACTIVE",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.5.sp
                        ),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Device locator activated.\nRinging at maximum volume.",
                        color = Color.LightGray,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onAcknowledge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7) // Sky 600
                        )
                    ) {
                        Text(
                            text = "Acknowledge Alert",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PulsingBellIcon() {
        val infiniteTransition = rememberInfiniteTransition(label = "bell_pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .alpha(alpha)
                .background(Color(0x1A38BDF8), shape = CircleShape)
                .border(2.dp, Color(0xFF38BDF8), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(44.dp)) {
                val w = size.width
                val h = size.height

                // Draw Bell Body Path
                val bellPath = Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    // Bell Top Dome
                    cubicTo(w * 0.38f, h * 0.12f, w * 0.28f, h * 0.24f, w * 0.28f, h * 0.44f)
                    // Left curve down
                    lineTo(w * 0.20f, h * 0.68f)
                    // Bell lip bottom
                    lineTo(w * 0.80f, h * 0.68f)
                    // Right line up
                    lineTo(w * 0.72f, h * 0.44f)
                    // Right curve up to dome
                    cubicTo(w * 0.72f, h * 0.24f, w * 0.62f, h * 0.12f, w * 0.5f, h * 0.12f)
                    close()
                }
                drawPath(bellPath, color = Color(0xFF38BDF8))

                // Bell loop/handle
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = w * 0.08f,
                    center = Offset(w * 0.5f, h * 0.10f),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Bell clapper
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = w * 0.10f,
                    center = Offset(w * 0.5f, h * 0.78f)
                )
            }
        }
    }
}
