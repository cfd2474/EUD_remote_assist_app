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
    
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

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
    }

    private var isInitialized = false
    private val signalingBuffer = mutableListOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
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
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)

        networkManager.setSessionActive(true)

        videoCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("ScreenShare", "MediaProjection stopped")
                networkManager.setSessionActive(false)
                stopSelf()
            }
        })

        videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
        
        surfaceTextureHelper = SurfaceTextureHelper.create("WebRTC-SurfaceHelper", eglBase!!.eglBaseContext)
        
        // Wrap the observer to detect the first frame
        val originalObserver = videoSource!!.capturerObserver
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
                        
                        // Process any buffered signals now that we are fully ready
                        isInitialized = true
                        Log.d("ScreenShare", "Service initialized, processing ${signalingBuffer.size} buffered signals")
                        signalingBuffer.forEach { handleSignalingMessage(it) }
                        signalingBuffer.clear()
                    }
                }
            }
        }

        videoCapturer!!.initialize(surfaceTextureHelper, this, wrappingObserver)
        
        // Use half resolution for better performance and faster start
        captureWidth = metrics.widthPixels / 2
        captureHeight = metrics.heightPixels / 2
        Log.d("ScreenShare", "Starting capture at ${captureWidth}x${captureHeight}")
        videoCapturer!!.startCapture(captureWidth, captureHeight, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("VIDEO_TRACK", videoSource)
        localVideoTrack?.setEnabled(true)
        
        setupPeerConnection()
        
        startSignalingPoll()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("ScreenShare", "Configuration changed, checking orientation")
        onDisplayRotated()
    }

    private fun onDisplayRotated() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val newW = metrics.widthPixels / 2
        val newH = metrics.heightPixels / 2
        
        if (newW == captureWidth && newH == captureHeight) return
        
        Log.i("ScreenShare", "Orientation changed: ${captureWidth}x${captureHeight} -> ${newW}x${newH}")
        captureWidth = newW
        captureHeight = newH
        
        videoCapturer?.changeCaptureFormat(newW, newH, 30)
        
        val payload = JsonObject().apply {
            addProperty("width", metrics.widthPixels)
            addProperty("height", metrics.heightPixels)
            addProperty("orientation", if (newW > newH) "landscape" else "portrait")
        }
        sendDeviceEvent("ORIENTATION_CHANGED", payload)
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
                Log.d("ScreenShare", "Renegotiation Needed")
                if (peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
                    renegotiate()
                }
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        Log.d("ScreenShare", "Adding local video track to PeerConnection via Transceiver")
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("stream0"))
        peerConnection?.addTransceiver(localVideoTrack, init)
    }

    private fun renegotiate() {
        Log.d("ScreenShare", "Initiating renegotiation answer")
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val answerJson = JsonObject().apply {
                            addProperty("type", "webrtc")
                            val sdpAnswer = JsonObject().apply {
                                addProperty("type", "answer")
                                addProperty("sdp", desc.description)
                            }
                            add("sdp", sdpAnswer)
                        }
                        networkManager.sendWebSocketMessage(gson.toJson(answerJson))
                    }
                }, desc)
            }
        }, MediaConstraints())
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
                        // Requirement: ignore same offer if already handled, or if it's stale
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
                                
                                // Requirement: Add track only after receiving offer
                                Log.d("ScreenShare", "Adding local video track to PeerConnection (Post-Offer)")
                                peerConnection?.addTrack(localVideoTrack, listOf("stream0"))

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
        lastProcessedOfferSdp = null
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
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
