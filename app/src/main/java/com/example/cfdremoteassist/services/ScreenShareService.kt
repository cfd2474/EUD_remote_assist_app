package com.example.cfdremoteassist.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.cfdremoteassist.utils.ManagedConfigManager
import com.example.cfdremoteassist.utils.NetworkManager
import com.example.cfdremoteassist.remote.RemoteSessionManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenShareService : Service() {

    private lateinit var configManager: ManagedConfigManager
    private lateinit var networkManager: NetworkManager
    private val gson = Gson()
    private val serviceExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null
    private var lastProcessedOfferSdp: String? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var screenWakeLock: PowerManager.WakeLock? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var statsMonitorRunnable: Runnable? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "STOP_SCREEN_SHARE"
        const val ACTION_PROCESS_SIGNAL = "PROCESS_SIGNAL"
        const val EXTRA_SIGNAL = "signal_json"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        configManager = ManagedConfigManager(this)
        networkManager = NetworkManager.getInstance(this, configManager)
        initWebRTC()
    }

    private fun initWebRTC() {
        serviceExecutor.execute {
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
                )

                eglBase = EglBase.create()
                val options = PeerConnectionFactory.Options()
                
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
                    .createPeerConnectionFactory()
                Log.d("ScreenShare", "WebRTC Factory Initialized")
            } catch (e: Exception) {
                Log.e("ScreenShare", "WebRTC init error: ${e.message}")
            }
        }
    }

    private var isInitialized = false
    private val signalingBuffer = mutableListOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i("ScreenShare", "Stop signal received, tearing down...")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROCESS_SIGNAL -> {
                val signal = intent.getStringExtra(EXTRA_SIGNAL)
                if (signal != null) {
                    if (isInitialized) {
                        handleSignalingMessage(signal)
                    } else {
                        Log.d("ScreenShare", "Buffering signal (service warming up)")
                        signalingBuffer.add(signal)
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
                val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (videoCapturer == null) {
                        startForegroundService()
                        // Small delay to ensure FGS is established before projection starts
                        handler.post {
                            try {
                                startScreenCapture(resultCode, data)
                            } catch (e: Exception) {
                                Log.e("ScreenShare", "Projection error: ${e.message}")
                                stopSelf()
                            }
                        }
                    } else {
                        Log.d("ScreenShare", "Already capturing, re-signaling WEBRTC_READY")
                        if (firstFrameCaptured) {
                            val readyJson = JsonObject().apply {
                                addProperty("type", "webrtc_ready")
                            }
                            networkManager.sendWebSocketMessage(gson.toJson(readyJson))
                        }
                    }
                } else if (videoCapturer == null) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun startForegroundService() {
        val channelId = "screen_share_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen Sharing", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Your screen is being shared for technical assistance.")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(2, notification)
            }
        } catch (e: Exception) {
            Log.e("ScreenShare", "Failed to start foreground service: ${e.message}")
            stopSelf()
        }
    }

    private var firstFrameCaptured = false

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (peerConnectionFactory == null) {
            Log.w("ScreenShare", "Factory not ready, delaying capture")
            handler.postDelayed({ startScreenCapture(resultCode, data) }, 500)
            return
        }
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)

        networkManager.setSessionActive(true)
        RemoteSessionManager.isSessionActive = true

        videoCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("ScreenShare", "MediaProjection stopped")
                networkManager.setSessionActive(false)
                RemoteSessionManager.isSessionActive = false
                stopSelf()
            }
        })

        videoSource = peerConnectionFactory?.createVideoSource(true)
        
        surfaceTextureHelper = SurfaceTextureHelper.create("WebRTC-SurfaceHelper", eglBase!!.eglBaseContext)
        
        // Wrap the observer to detect the first frame
        val originalObserver = videoSource!!.capturerObserver
        var frameCount = 0
        val wrappingObserver = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                originalObserver.onCapturerStarted(success)
                Log.d("ScreenShare", "Capturer started: $success")
            }
            override fun onCapturerStopped() {
                originalObserver.onCapturerStopped()
                Log.d("ScreenShare", "Capturer stopped")
            }
            override fun onFrameCaptured(frame: VideoFrame) {
                originalObserver.onFrameCaptured(frame)
                frameCount++
                if (!firstFrameCaptured) {
                    firstFrameCaptured = true
                    Log.i("ScreenShare", "FIRST FRAME CAPTURED! ${frame.rotatedWidth}x${frame.rotatedHeight}")

                    // ONLY signal ready after we have actual video data
                    handler.post {
                        Log.d("ScreenShare", "Signaling WEBRTC_READY to portal")
                        val readyJson = JsonObject().apply {
                            addProperty("type", "webrtc_ready")
                        }
                        networkManager.sendWebSocketMessage(gson.toJson(readyJson))
                        sendDeviceEvent("WEBRTC_READY")

                        // Ensure track is enabled
                        localVideoTrack?.setEnabled(true)

                        // Process any buffered signals now that we are fully ready
                        isInitialized = true
                        Log.d("ScreenShare", "Service initialized, processing ${signalingBuffer.size} buffered signals")
                        signalingBuffer.forEach { handleSignalingMessage(it) }
                        signalingBuffer.clear()
                    }
                }
                // Log frame capture rate every 30 frames (~1 second)
                if (frameCount % 30 == 0) {
                    Log.d("ScreenShare", "Frame capture progress: $frameCount frames delivered to VideoSource. Track state: ${localVideoTrack?.state()}, enabled: ${localVideoTrack?.enabled()}")
                    
                    // Periodically check if track is added to PeerConnection
                    val isAdded = peerConnection?.senders?.any { it.track()?.id() == "VIDEO_TRACK" } ?: false
                    if (!isAdded && localVideoTrack != null && isInitialized) {
                        Log.w("ScreenShare", "Track NOT found in PeerConnection senders, attempting to add...")
                        try {
                            peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
                        } catch (e: Exception) {
                            Log.e("ScreenShare", "Failed to add track in progress: ${e.message}")
                        }
                    }
                }
            }
        }

        videoCapturer!!.initialize(surfaceTextureHelper, this, wrappingObserver)
        
        // Use half resolution for better performance and faster start
        captureWidth = metrics.widthPixels / 2
        captureHeight = metrics.heightPixels / 2
        
        if (captureWidth % 2 != 0) captureWidth--
        if (captureHeight % 2 != 0) captureHeight--
        RemoteSessionManager.captureWidth = captureWidth
        RemoteSessionManager.captureHeight = captureHeight
        RemoteSessionManager.displayWidth = metrics.widthPixels
        RemoteSessionManager.displayHeight = metrics.heightPixels
        Log.d("ScreenShare", "Starting capture at ${captureWidth}x${captureHeight}")
        
        // Keep screen on during active capture
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            screenWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "RemoteAssist:ScreenShare")
            screenWakeLock?.acquire()
        } catch (e: Exception) {
            Log.e("ScreenShare", "Failed to acquire screen wake lock: ${e.message}")
        }

        videoCapturer!!.startCapture(captureWidth, captureHeight, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("VIDEO_TRACK", videoSource)
        localVideoTrack?.setEnabled(true)
        Log.d("ScreenShare", "VideoTrack created: enabled=${localVideoTrack?.enabled()}, state=${localVideoTrack?.state()}")

        setupPeerConnection()
        startSignalingPoll()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("ScreenShare", "Configuration changed, checking orientation")
        onDisplayRotated()
    }

    private fun onDisplayRotated() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val newW = metrics.widthPixels / 2
        val newH = metrics.heightPixels / 2
        
        if (newW == captureWidth && newH == captureHeight) return
        
        Log.i("ScreenShare", "Orientation changed: ${captureWidth}x${captureHeight} -> ${newW}x${newH}. Triggering renegotiation.")
        captureWidth = if (newW % 2 != 0) newW - 1 else newW
        captureHeight = if (newH % 2 != 0) newH - 1 else newH
        
        RemoteSessionManager.captureWidth = captureWidth
        RemoteSessionManager.captureHeight = captureHeight
        RemoteSessionManager.displayWidth = metrics.widthPixels
        RemoteSessionManager.displayHeight = metrics.heightPixels
        
        // On many devices, changeCaptureFormat leads to a black screen if the encoder 
        // doesn't support dynamic resolution changes. Instead, we signal a 
        // renegotiation to let the portal start fresh with new dimensions.
        videoCapturer?.changeCaptureFormat(captureWidth, captureHeight, 30)
        
        val payload = JsonObject().apply {
            addProperty("width", metrics.widthPixels)
            addProperty("height", metrics.heightPixels)
            addProperty("orientation", if (newW > newH) "landscape" else "portrait")
        }
        sendDeviceEvent("ORIENTATION_CHANGED", payload)

        // Force a renegotiation to ensure the transport picks up new dimensions
        handler.postDelayed({
            Log.d("ScreenShare", "Sending WEBRTC_READY after rotation to force fresh offer")
            val readyJson = JsonObject().apply {
                addProperty("type", "webrtc_ready")
            }
            networkManager.sendWebSocketMessage(gson.toJson(readyJson))
        }, 500)
    }

    private fun startSignalingPoll() {
        pollRunnable = object : Runnable {
            override fun run() {
                // Requirement: stop HTTP signaling poll while the device WebSocket is connected and healthy
                if (!networkManager.isWebSocketConnected()) {
                    networkManager.pollSignaling { messages ->
                        if (messages.isNotEmpty()) {
                            Log.d("ScreenShare", "Received ${messages.size} signaling messages via HTTP poll")
                            messages.forEach { handleSignalingJsonObject(it) }
                        }
                    }
                }
                pollHandler.postDelayed(this, 2000) // Poll every 2 seconds during active session
            }
        }
        pollHandler.postDelayed(pollRunnable!!, 2000)
    }

    private fun handleSignalingJsonObject(json: JsonObject) {
        handleSignalingMessage(json.toString())
    }

    private fun setupPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d("ScreenShare", "Local ICE Candidate Gathered: ${candidate.sdp}")
                val iceJson = JsonObject().apply {
                    addProperty("type", "webrtc")
                    val iceObj = JsonObject().apply {
                        addProperty("candidate", candidate.sdp)
                        addProperty("sdpMid", candidate.sdpMid)
                        addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                    add("ice", iceObj)
                }
                networkManager.sendWebSocketMessage(gson.toJson(iceJson))
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d("ScreenShare", "Signaling State: $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d("ScreenShare", "ICE Connection State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    sendDeviceEvent("REMOTE_SESSION_STARTED")
                    networkManager.setSessionActive(true)
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED || 
                           state == PeerConnection.IceConnectionState.FAILED || 
                           state == PeerConnection.IceConnectionState.CLOSED) {
                    sendDeviceEvent("REMOTE_SESSION_STOPPED")
                    networkManager.setSessionActive(false)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d("ScreenShare", "ICE Gathering State: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {
                Log.d("ScreenShare", "Renegotiation Needed - Triggering new offer from portal")
                val readyJson = JsonObject().apply {
                    addProperty("type", "webrtc_ready")
                }
                networkManager.sendWebSocketMessage(gson.toJson(readyJson))
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })
    }

    private fun sendDeviceEvent(eventName: String, payload: JsonObject = JsonObject()) {
        val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val eventJson = JsonObject().apply {
            addProperty("type", "device_event")
            addProperty("uid", uid)
            addProperty("event", eventName)
            add("payload", payload)
        }
        networkManager.sendWebSocketMessage(gson.toJson(eventJson))
    }

    private fun handleSignalingMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val signalType = json.get("type")?.asString
            
            // spec says "webrtc" type is used for relaying
            if (signalType == "webrtc") {
                val sdpObj = json.getAsJsonObject("sdp")
                val iceObj = json.getAsJsonObject("ice")

                if (sdpObj != null) {
                    val sdpType = sdpObj.get("type")?.asString
                    val sdpDesc = sdpObj.get("sdp")?.asString
                    if (sdpType == "offer" && sdpDesc != null) {
                        // Requirement: ignore same offer if already handled
                        if (sdpDesc == lastProcessedOfferSdp) {
                            Log.d("ScreenShare", "Skipping already processed WebRTC Offer")
                            return
                        }
                        
                        Log.d("ScreenShare", "Processing new WebRTC Offer")
                        lastProcessedOfferSdp = sdpDesc
                        val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpDesc)
                        
                        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                Log.d("ScreenShare", "Remote Description Set")

                                // Ensure track is added before creating answer
                                // Per spec: "Add screen-capture VideoTrack to PeerConnection after setRemoteDescription, before createAnswer()"
                                try {
                                    val currentSenders = peerConnection?.senders ?: emptyList()
                                    val isTrackAlreadyAdded = currentSenders.any { it.track()?.id() == "VIDEO_TRACK" }
                                    
                                    if (!isTrackAlreadyAdded && localVideoTrack != null) {
                                        val sender = peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
                                        Log.d("ScreenShare", "Video track added to PeerConnection before Answer: senderId=${sender?.id()}")
                                    } else {
                                        Log.d("ScreenShare", "Video track already present in PeerConnection")
                                    }
                                } catch (e: Exception) {
                                    Log.e("ScreenShare", "Failed to add track to PeerConnection: ${e.message}")
                                }

                                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(desc: SessionDescription) {
                                        Log.d("ScreenShare", "Answer Created. Local SDP: ${desc.description.take(50)}...")
                                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                                            override fun onSetSuccess() {
                                                Log.d("ScreenShare", "Local Description Set")
                                                val answerJson = JsonObject().apply {
                                                    addProperty("type", "webrtc")
                                                    val sdpAnswer = JsonObject().apply {
                                                        addProperty("type", "answer")
                                                        addProperty("sdp", desc.description)
                                                    }
                                                    add("sdp", sdpAnswer)
                                                }
                                                Log.d("ScreenShare", "Sending WebRTC Answer. WS Connected: ${networkManager.isWebSocketConnected()}")
                                                networkManager.sendWebSocketMessage(gson.toJson(answerJson))

                                                // Start monitoring encoder stats to verify video is flowing
                                                startStatsMonitoring()

                                                // Minimize app and go to home screen after handshake initiated
                                                minimizeApp()
                                            }
                                        }, desc)
                                    }
                                }, MediaConstraints())
                            }
                        }, sdp)
                    }
                } else if (iceObj != null) {
                    val candidate = IceCandidate(
                        iceObj.get("sdpMid").asString,
                        iceObj.get("sdpMLineIndex").asInt,
                        iceObj.get("candidate").asString
                    )
                    Log.d("ScreenShare", "Processing Remote ICE Candidate")
                    peerConnection?.addIceCandidate(candidate)
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenShare", "Signaling error", e)
        }
    }

    private fun startStatsMonitoring() {
        // Stop any existing monitoring first
        statsMonitorRunnable?.let { handler.removeCallbacks(it) }

        statsMonitorRunnable = object : Runnable {
            override fun run() {
                val pc = peerConnection
                if (pc != null) {
                    try {
                        pc.getStats { report ->
                            report.statsMap.values.forEach { stats ->
                                if (stats.type == "outbound-rtp" && stats.members["kind"] == "video") {
                                    val framesEncoded = stats.members["framesEncoded"]
                                    val packetsSent = stats.members["packetsSent"]
                                    val bytesSent = stats.members["bytesSent"]
                                    Log.i("ScreenShare", "Encoder stats: framesEncoded=$framesEncoded, packetsSent=$packetsSent, bytesSent=$bytesSent")
                                }
                            }
                        }
                        // Schedule next check only if PeerConnection is still valid
                        handler.postDelayed(this, 5000)
                    } catch (e: Exception) {
                        Log.e("ScreenShare", "Error getting stats: ${e.message}")
                    }
                }
            }
        }
        handler.postDelayed(statsMonitorRunnable!!, 5000)
    }

    private fun minimizeApp() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e("ScreenShare", "SDP Create Failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e("ScreenShare", "SDP Set Failure: $p0") }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (screenWakeLock?.isHeld == true) {
            screenWakeLock?.release()
        }
        screenWakeLock = null
        lastProcessedOfferSdp = null
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        statsMonitorRunnable?.let { handler.removeCallbacks(it) }
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            videoSource?.dispose()
            peerConnection?.dispose()
            peerConnectionFactory?.dispose()
            eglBase?.release()
        } catch (e: Exception) {
            Log.e("ScreenShare", "Error during disposal: ${e.message}")
        }
        serviceExecutor.shutdown()
    }
}
