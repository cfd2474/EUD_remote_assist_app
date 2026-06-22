package com.cfd2474.eudremoteassist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.cfd2474.eudremoteassist.MainActivity
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import com.cfd2474.eudremoteassist.network.NetworkManager
import com.cfd2474.eudremoteassist.session.RemoteSessionState
import com.cfd2474.eudremoteassist.webrtc.ScreenCapturePipeline
import com.cfd2474.eudremoteassist.webrtc.WebRtcSessionManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.webrtc.IceCandidate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShare"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "screen_share"

        const val ACTION_START = "com.cfd2474.eudremoteassist.action.START"
        const val ACTION_STOP = "com.cfd2474.eudremoteassist.action.STOP"
        const val ACTION_SIGNAL = "com.cfd2474.eudremoteassist.action.SIGNAL"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SIGNAL = "signal"

        @Volatile
        var instance: ScreenShareService? = null
            private set
    }

    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private lateinit var config: ManagedConfigManager
    private lateinit var networkManager: NetworkManager
    private var sessionManager: WebRtcSessionManager? = null
    private var pipeline: ScreenCapturePipeline? = null

    @Volatile
    private var isPipelineReady = false
    private val signalingBuffer = ConcurrentLinkedQueue<String>()

    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handleDisplayRotation()
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (RemoteSessionState.isSessionActive) {
                pollSignalingMessages()
                handler.postDelayed(this, 2000L) // Poll every 2 seconds
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenShareService onCreate")
        instance = this
        config = ManagedConfigManager(this)
        networkManager = NetworkManager.getInstance(this, config)
        createNotificationChannel()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "ScreenShareService onStartCommand action: $action")
        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val iceServersJson = intent.getStringExtra("iceServers")
                
                if (resultCode != 0 && resultData != null) {
                    startSession(resultCode, resultData, iceServersJson)
                } else {
                    Log.e(TAG, "Invalid result code or data. Cannot start session.")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                teardownSession()
                stopSelf()
            }
            ACTION_SIGNAL -> {
                val signal = intent.getStringExtra(EXTRA_SIGNAL)
                if (signal != null) {
                    handleSignalingMessage(signal)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ScreenShareService onDestroy")
        if (instance === this) {
            instance = null
        }
        handler.removeCallbacksAndMessages(null)
        teardownSession()
        displayManager?.unregisterDisplayListener(displayListener)
        backgroundExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "onConfigurationChanged orientation: ${newConfig.orientation}")
        handleDisplayRotation()
    }

    fun handleSignalingFromGateway(message: String) {
        handleSignalingMessage(message)
    }

    private fun handleDisplayRotation() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        // Skip if dimensions haven't changed
        if (w == RemoteSessionState.displayWidth && h == RemoteSessionState.displayHeight) {
            return
        }

        Log.i(TAG, "Display rotation detected: ${w}x${h}")

        RemoteSessionState.displayWidth = w
        RemoteSessionState.displayHeight = h

        var capW = w / 2
        var capH = h / 2
        if (capW % 2 != 0) capW--
        if (capH % 2 != 0) capH--

        RemoteSessionState.captureWidth = capW
        RemoteSessionState.captureHeight = capH

        if (!backgroundExecutor.isShutdown) {
            backgroundExecutor.execute {
                pipeline?.changeFormat(capW, capH)
            }
        }

        // Send ORIENTATION_CHANGED device event
        val orientationStr = if (w > h) "landscape" else "portrait"
        val payload = JsonObject().apply {
            addProperty("width", w)
            addProperty("height", h)
            addProperty("orientation", orientationStr)
        }
        sendDeviceEvent("ORIENTATION_CHANGED", payload)

        // Force a renegotiation 500ms after display rotation
        handler.postDelayed({
            if (!backgroundExecutor.isShutdown) {
                backgroundExecutor.execute {
                    Log.d(TAG, "Sending WEBRTC_READY after rotation to force fresh offer")
                    val readyJson = JsonObject().apply {
                        addProperty("type", "webrtc_ready")
                    }
                    networkManager.sendWebSocket(gson.toJson(readyJson))
                }
            }
        }, 500L)
    }

    // 13.1 Start WebRTC and capture flow
    private fun startSession(resultCode: Int, resultData: Intent, iceServersJson: String?) {
        Log.i(TAG, "Starting screen share session (fresh projection)")
        
        // Show foreground notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!backgroundExecutor.isShutdown) {
            backgroundExecutor.execute {
                try {
                    sessionManager = WebRtcSessionManager(this, networkManager, iceServersJson) {
                        pipeline?.getLocalVideoTrack()
                    }

                    val factory = sessionManager!!.getFactory()
                    
                    pipeline = ScreenCapturePipeline(this, factory, resultData, sessionManager?.getEglContext()) {
                        onFirstFrameCaptured()
                    }
                    
                    pipeline?.start()
                    sessionManager?.createPeerConnection()

                    // Start polling signaling Fallback
                    handler.post(pollRunnable)

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting ScreenShare session: ${e.message}", e)
                    handler.post { stopSelf() }
                }
            }
        }
    }

    private fun onFirstFrameCaptured() {
        Log.i(TAG, "First frame captured. Sending webrtc_ready.")
        
        // Send webrtc_ready ONCE
        val readyJson = JsonObject().apply {
            addProperty("type", "webrtc_ready")
        }
        networkManager.sendWebSocket(gson.toJson(readyJson))

        // Send WEBRTC_READY device event
        sendDeviceEvent("WEBRTC_READY")

        isPipelineReady = true
        flushSignalingBuffer()
    }

    // 13.3 Signaling Message Router
    private fun handleSignalingMessage(signalText: String) {
        if (!isPipelineReady) {
            Log.i(TAG, "Pipeline not ready yet. Buffering signaling message: $signalText")
            signalingBuffer.add(signalText)
            return
        }

        if (!backgroundExecutor.isShutdown) {
            backgroundExecutor.execute {
                try {
                    val json = gson.fromJson(signalText, JsonObject::class.java)
                    val type = json.get("type")?.asString
                    if (type == "webrtc") {
                        if (json.has("sdp")) {
                            val sdpObj = json.getAsJsonObject("sdp")
                            val sdpType = sdpObj.get("type")?.asString
                            val sdpStr = sdpObj.get("sdp")?.asString
                            if (sdpType == "offer" && sdpStr != null) {
                                sessionManager?.handleOffer(sdpStr)
                            }
                        } else if (json.has("ice")) {
                            val iceObj = json.getAsJsonObject("ice")
                            val candidate = iceObj.get("candidate")?.asString
                            val sdpMid = iceObj.get("sdpMid")?.asString
                            val sdpMLineIndex = iceObj.get("sdpMLineIndex")?.asInt
                            if (candidate != null && sdpMid != null && sdpMLineIndex != null) {
                                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                                sessionManager?.handleRemoteIceCandidate(iceCandidate)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling signaling message: ${e.message}")
                }
            }
        }
    }

    private fun flushSignalingBuffer() {
        Log.i(TAG, "Flushing buffered signaling messages (${signalingBuffer.size})")
        var signal = signalingBuffer.poll()
        while (signal != null) {
            handleSignalingMessage(signal)
            signal = signalingBuffer.poll()
        }
    }

    // 13.2 Signaling Fallback Polling (every 2 seconds)
    private fun pollSignalingMessages() {
        networkManager.pollSignaling { success, response ->
            if (success && !response.isNullOrBlank()) {
                try {
                    val element = gson.fromJson(response, JsonElement::class.java)
                    if (element.isJsonArray) {
                        for (msg in element.asJsonArray) {
                            handleSignalingMessage(msg.toString())
                        }
                    } else if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        val messages = obj.getAsJsonArray("messages") ?: obj.getAsJsonArray("signaling")
                        if (messages != null) {
                            for (msg in messages) {
                                handleSignalingMessage(msg.toString())
                            }
                        } else if (obj.has("type") && obj.get("type").asString == "webrtc") {
                            handleSignalingMessage(response)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse HTTP signaling response: ${e.message}")
                }
            }
        }
    }

    // 13.5 Teardown (Idempotent)
    private fun teardownSession() {
        Log.i(TAG, "Tearing down screen share session")
        handler.removeCallbacks(pollRunnable)

        pipeline?.stop()
        pipeline = null

        sessionManager?.dispose()
        sessionManager = null

        isPipelineReady = false
        signalingBuffer.clear()

        RemoteSessionState.reset()
        networkManager.setRemoteSessionActive(false)
    }

    // Foreground Notifications Channel
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps screen sharing session active"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Remote Admin is viewing your screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun sendDeviceEvent(eventName: String, payload: JsonObject = JsonObject()) {
        val eventJson = JsonObject().apply {
            addProperty("type", "device_event")
            addProperty("uid", networkManager.getDeviceUid())
            addProperty("event", eventName)
            add("payload", payload)
        }
        networkManager.sendWebSocket(gson.toJson(eventJson))
    }
}
