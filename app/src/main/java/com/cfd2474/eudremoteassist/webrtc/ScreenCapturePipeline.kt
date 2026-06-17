package com.cfd2474.eudremoteassist.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.cfd2474.eudremoteassist.session.RemoteSessionState
import org.webrtc.*

class ScreenCapturePipeline(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val projectionData: Intent,
    private val eglContext: EglBase.Context?,
    private val onFirstFrame: () -> Unit
) {
    companion object {
        private const val TAG = "ScreenCapture"
    }

    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoTrack: VideoTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var firstFrameSeen = false

    fun getLocalVideoTrack(): VideoTrack? = videoTrack

    fun start() {
        Log.i(TAG, "Starting ScreenCapturePipeline")
        
        // 11.3 Determine physical and capture resolutions
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val physicalWidth = metrics.widthPixels
        val physicalHeight = metrics.heightPixels

        RemoteSessionState.displayWidth = physicalWidth
        RemoteSessionState.displayHeight = physicalHeight

        var captureWidth = physicalWidth / 2
        var captureHeight = physicalHeight / 2
        if (captureWidth % 2 != 0) captureWidth--
        if (captureHeight % 2 != 0) captureHeight--

        RemoteSessionState.captureWidth = captureWidth
        RemoteSessionState.captureHeight = captureHeight

        Log.i(TAG, "Display: ${physicalWidth}x${physicalHeight}, Capture: ${captureWidth}x${captureHeight}")

        try {
            // 11.2 Wiring sequence (mandatory order)
            
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection.Callback onStop triggered")
                }
            }
            
            capturer = ScreenCapturerAndroid(projectionData, mediaProjectionCallback)
            
            // isScreencast MUST be true
            videoSource = factory.createVideoSource(true)
            
            surfaceHelper = SurfaceTextureHelper.create("ScreenCapture", eglContext)
            
            var frameCount = 0
            val frameForwardingObserver = object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) {
                    Log.i(TAG, "Capturer started successfully: $success")
                    videoSource?.capturerObserver?.onCapturerStarted(success)
                }

                override fun onCapturerStopped() {
                    Log.i(TAG, "Capturer stopped")
                    videoSource?.capturerObserver?.onCapturerStopped()
                }

                override fun onFrameCaptured(frame: VideoFrame) {
                    frameCount++
                    if (!firstFrameSeen) {
                        firstFrameSeen = true
                        Log.i(TAG, "FIRST FRAME ${frame.rotatedWidth}x${frame.rotatedHeight}")
                        onFirstFrame()
                    }
                    if (frameCount % 30 == 0) {
                        Log.d(TAG, "Frame capture progress: $frameCount frames delivered to VideoSource. Track state: ${videoTrack?.state()}, enabled: ${videoTrack?.enabled()}")
                    }
                    videoSource?.capturerObserver?.onFrameCaptured(frame)
                }
            }

            capturer?.initialize(surfaceHelper, context, frameForwardingObserver)
            capturer?.startCapture(captureWidth, captureHeight, 30)
            
            videoTrack = factory.createVideoTrack("VIDEO_TRACK", videoSource)
            videoTrack?.setEnabled(true)
            
            Log.i(TAG, "ScreenCapturePipeline started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ScreenCapturePipeline: ${e.message}", e)
            stop()
        }
    }

    fun changeFormat(newWidth: Int, newHeight: Int) {
        Log.i(TAG, "Changing capture format to ${newWidth}x${newHeight}")
        try {
            capturer?.changeCaptureFormat(newWidth, newHeight, 30)
            videoSource?.adaptOutputFormat(newWidth, newHeight, 30)
            
            // Encoder kick
            videoTrack?.setEnabled(false)
            videoTrack?.setEnabled(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error changing capture format: ${e.message}")
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping ScreenCapturePipeline")
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer: ${e.message}")
        }
        
        capturer?.dispose()
        capturer = null

        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceHelper?.dispose()
        surfaceHelper = null

        firstFrameSeen = false
    }
}
