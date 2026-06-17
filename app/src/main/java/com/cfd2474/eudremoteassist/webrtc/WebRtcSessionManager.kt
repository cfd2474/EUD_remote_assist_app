package com.cfd2474.eudremoteassist.webrtc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cfd2474.eudremoteassist.network.NetworkManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.webrtc.*
import java.util.concurrent.ConcurrentLinkedQueue

class WebRtcSessionManager(
    private val context: Context,
    private val networkManager: NetworkManager,
    private val localVideoTrackProvider: () -> VideoTrack?
) {
    companion object {
        private const val TAG = "WebRtcSession"
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    
    @Volatile
    private var remoteDescriptionSet = false
    private val pendingRemoteIce = ConcurrentLinkedQueue<IceCandidate>()
    private var lastOfferSdp: String? = null

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        Log.i(TAG, "Initializing PeerConnectionFactory")
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        eglBase = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun getEglContext(): EglBase.Context? {
        return eglBase?.eglBaseContext
    }

    fun getFactory(): PeerConnectionFactory {
        return factory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
    }

    fun createPeerConnection() {
        Log.i(TAG, "Creating PeerConnection")
        if (peerConnection != null) {
            Log.w(TAG, "PeerConnection already exists. Disposing existing one first.")
            disposePeerConnection()
        }

        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "onIceConnectionChange: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    sendDeviceEvent("REMOTE_SESSION_STARTED")
                    networkManager.setRemoteSessionActive(true)
                    startStatsMonitoring()
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED) {
                    sendDeviceEvent("REMOTE_SESSION_STOPPED")
                    networkManager.setRemoteSessionActive(false)
                    stopStatsMonitoring()
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: $state")
            }

            // 12.5 Local ICE candidate signaling
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.i(TAG, "Local ICE Candidate: ${candidate.sdp}")
                val iceJson = JsonObject().apply {
                    addProperty("candidate", candidate.sdp)
                    addProperty("sdpMid", candidate.sdpMid)
                    addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                val outerJson = JsonObject().apply {
                    addProperty("type", "webrtc")
                    add("ice", iceJson)
                }
                val msgStr = gson.toJson(outerJson)
                networkManager.sendWebSocket(msgStr)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "onAddStream")
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "onRemoveStream")
            }

            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "onDataChannel")
            }

            override fun onRenegotiationNeeded() {
                Log.i(TAG, "onRenegotiationNeeded — Triggering new offer from portal")
                val readyJson = JsonObject().apply {
                    addProperty("type", "webrtc_ready")
                }
                networkManager.sendWebSocket(gson.toJson(readyJson))
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                Log.d(TAG, "onAddTrack")
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                super.onStandardizedIceConnectionChange(newState)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "onConnectionChange: $newState")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                super.onTrack(transceiver)
            }
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, pcObserver)
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        lastOfferSdp = null
    }

    // 12.2 Offer handling & Fix #1C sequence
    fun handleOffer(offerSdp: String) {
        val pc = peerConnection
        if (pc == null) {
            Log.e(TAG, "Cannot handle offer: PeerConnection is null")
            return
        }

        if (offerSdp == lastOfferSdp) {
            Log.i(TAG, "Ignoring duplicate offer SDP in same session")
            return
        }
        lastOfferSdp = offerSdp

        Log.i(TAG, "Processing portal offer")
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}

            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set successfully")
                remoteDescriptionSet = true
                flushPendingRemoteIce()
                
                // Bind track to transceiver (MUST happen after remote description is set)
                bindScreenTrackAfterOffer()

                // Create SDP answer
                createAnswerAndSend()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
        }, sessionDesc)
    }

    // 12.3 bindScreenTrackAfterOffer - MUST happen after setRemoteDescription
    private fun bindScreenTrackAfterOffer() {
        val pc = peerConnection ?: return
        val track = localVideoTrackProvider()
        if (track == null) {
            Log.e(TAG, "localVideoTrackProvider returned null, cannot bind track")
            return
        }

        val currentSenders = pc.senders
        val isTrackAlreadyAdded = currentSenders.any { it.track()?.id() == "VIDEO_TRACK" }
        if (!isTrackAlreadyAdded) {
            val sender = pc.addTrack(track, listOf("stream0"))
            Log.i(TAG, "Video track added to PeerConnection before Answer: senderId=${sender?.id()}")
        } else {
            Log.i(TAG, "Video track already present in PeerConnection")
        }
    }

    private fun createAnswerAndSend() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                Log.i(TAG, "Answer created successfully")
                
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(d: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    
                    override fun onSetSuccess() {
                        // 12.7 Verification log line
                        val hasSendOnly = desc.description.contains("a=sendonly")
                        val hasMsid = desc.description.contains("a=msid")
                        Log.i(TAG, "Answer created (sendonly=$hasSendOnly, msid=$hasMsid, len=${desc.description.length})")

                        val sdpJson = JsonObject().apply {
                            addProperty("type", "answer")
                            addProperty("sdp", desc.description)
                        }
                        val outerJson = JsonObject().apply {
                            addProperty("type", "webrtc")
                            add("sdp", sdpJson)
                        }
                        
                        networkManager.sendWebSocket(gson.toJson(outerJson))
                        minimizeApp()
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create SDP answer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    // 12.4 Buffer + apply remote ICE candidates
    fun handleRemoteIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null) {
            Log.w(TAG, "Cannot add remote ICE candidate: PeerConnection is null")
            return
        }

        if (!remoteDescriptionSet) {
            Log.i(TAG, "Buffering remote ICE candidate until remote description set")
            pendingRemoteIce.add(candidate)
        } else {
            Log.i(TAG, "Adding remote ICE candidate: ${candidate.sdp}")
            pc.addIceCandidate(candidate)
        }
    }

    private fun flushPendingRemoteIce() {
        val pc = peerConnection ?: return
        Log.i(TAG, "Flushing ${pendingRemoteIce.size} pending remote ICE candidates")
        var candidate = pendingRemoteIce.poll()
        while (candidate != null) {
            pc.addIceCandidate(candidate)
            candidate = pendingRemoteIce.poll()
        }
    }

    private fun disposePeerConnection() {
        peerConnection?.dispose()
        peerConnection = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        lastOfferSdp = null
    }

    fun dispose() {
        Log.i(TAG, "Disposing WebRtcSessionManager")
        stopStatsMonitoring()
        disposePeerConnection()
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
    }

    private fun startStatsMonitoring() {
        stopStatsMonitoring()
        Log.i(TAG, "Starting outbound RTP stats monitoring")
        val runnable = object : Runnable {
            override fun run() {
                val pc = peerConnection
                if (pc != null) {
                    try {
                        pc.getStats { report ->
                            try {
                                report.statsMap.values.forEach { stats ->
                                    if (stats.type == "outbound-rtp" && stats.members["kind"] == "video") {
                                        val framesEncoded = stats.members["framesEncoded"]
                                        val packetsSent = stats.members["packetsSent"]
                                        val bytesSent = stats.members["bytesSent"]
                                        Log.i(TAG, "Encoder stats: framesEncoded=$framesEncoded, packetsSent=$packetsSent, bytesSent=$bytesSent")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing stats: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting stats: ${e.message}")
                    } finally {
                        mainHandler.postDelayed(this, 5000L)
                    }
                }
            }
        }
        statsRunnable = runnable
        mainHandler.postDelayed(runnable, 5000L)
    }

    private fun stopStatsMonitoring() {
        statsRunnable?.let {
            Log.i(TAG, "Stopping outbound RTP stats monitoring")
            mainHandler.removeCallbacks(it)
        }
        statsRunnable = null
    }

    private fun minimizeApp() {
        Log.i(TAG, "Minimizing app to home screen after WebRTC Answer")
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to minimize app: ${e.message}")
        }
    }

    fun kickVideoEncoder() {
        val pc = peerConnection ?: return
        val track = localVideoTrackProvider() ?: return
        Log.i(TAG, "Kicking video encoder by detaching and re-attaching track")
        try {
            val sender = pc.senders.find { it.track()?.id() == "VIDEO_TRACK" }
            if (sender != null) {
                track.setEnabled(false)
                sender.setTrack(null, false)
                track.setEnabled(true)
                sender.setTrack(track, false)
                Log.i(TAG, "Video track successfully re-attached to sender")
            } else {
                Log.w(TAG, "Could not find video sender to kick encoder")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error kicking video encoder: ${e.message}")
        }
    }

    private fun sendDeviceEvent(eventName: String, payload: JsonObject = JsonObject()) {
        val eventJson = JsonObject().apply {
            addProperty("type", "device_event")
            addProperty("uid", networkManager.getAndroidId())
            addProperty("event", eventName)
            add("payload", payload)
        }
        networkManager.sendWebSocket(gson.toJson(eventJson))
    }
}
