package com.example.cfdremoteassist.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
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

    private var mediaProjection: MediaProjection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var localVideoTrack: VideoTrack? = null

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
        networkManager = NetworkManager(this, configManager)
        initWebRTC()
    }

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val eglBaseContext = EglBase.create().eglBaseContext
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .createPeerConnectionFactory()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PROCESS_SIGNAL -> {
                val signal = intent.getStringExtra(EXTRA_SIGNAL)
                if (signal != null) handleSignalingMessage(signal)
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
                val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundService()
                    startScreenCapture(resultCode, data)
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notification)
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)

        videoCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("ScreenShare", "MediaProjection stopped")
                stopSelf()
            }
        })

        videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(SurfaceTextureHelper.create("WebRTC-SurfaceHelper", EglBase.create().eglBaseContext), this, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(metrics.widthPixels / 2, metrics.heightPixels / 2, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("VIDEO_TRACK", videoSource)
        
        setupPeerConnection()
    }

    private fun setupPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val iceJson = JsonObject().apply {
                    addProperty("type", "webrtc")
                    addProperty("signal", "ice")
                    add("candidate", gson.toJsonTree(candidate))
                }
                networkManager.sendWebSocketMessage(gson.toJson(iceJson))
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        peerConnection?.addTrack(localVideoTrack)
    }

    private fun handleSignalingMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject.get("payload")?.asString ?: message, JsonObject::class.java)
            val signal = json.get("signal")?.asString
            
            when (signal) {
                "offer" -> {
                    val sdp = gson.fromJson(json.get("sdp"), SessionDescription::class.java)
                    peerConnection?.setRemoteDescription(SimpleSdpObserver {
                        peerConnection?.createAnswer(SimpleSdpObserver { answer ->
                            peerConnection?.setLocalDescription(SimpleSdpObserver {
                                val answerJson = JsonObject().apply {
                                    addProperty("type", "webrtc")
                                    addProperty("signal", "answer")
                                    add("sdp", gson.toJsonTree(answer))
                                }
                                networkManager.sendWebSocketMessage(gson.toJson(answerJson))
                            }, null)
                        }, MediaConstraints())
                    }, sdp)
                }
                "ice" -> {
                    val candidate = gson.fromJson(json.get("candidate"), IceCandidate::class.java)
                    peerConnection?.addIceCandidate(candidate)
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenShare", "Signaling error", e)
        }
    }

    private inner class SimpleSdpObserver(
        val onSuccessFunc: (SessionDescription) -> Unit
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            onSuccessFunc(desc)
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        peerConnection?.dispose()
        mediaProjection?.stop()
        serviceExecutor.shutdown()
    }
}