package com.example.cfdremoteassist.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var statusBarOverlay: View? = null
    private var bannerOverlay: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(100, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(100, createNotification())
        }

        showOverlays()
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun showOverlays() {
        // 1. Status Bar Overlay (Light Blue)
        val statusBarHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarHeightId > 0) resources.getDimensionPixelSize(statusBarHeightId) else 100

        val statusBarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        )
        statusBarParams.gravity = Gravity.TOP

        statusBarOverlay = View(this).apply {
            setBackgroundColor(Color.parseColor("#80ADD8E6")) // Light Blue with 50% alpha to keep icons visible
        }

        // 2. "Remote Assist" Banner
        val bannerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        bannerParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bannerParams.y = statusBarHeight + 10 // Just below status bar

        val bannerView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FFADD8E6")) // Solid Light Blue
            setPadding(20, 10, 20, 10)
        }
        val textView = TextView(this).apply {
            text = "Remote Assist"
            setTextColor(Color.BLACK)
            textSize = 12f
        }
        bannerView.addView(textView)
        bannerOverlay = bannerView

        try {
            windowManager?.addView(statusBarOverlay, statusBarParams)
            windowManager?.addView(bannerOverlay, bannerParams)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "overlay_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Active Remote Session", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Administration Active")
            .setContentText("A remote administrator is currently managing this device.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusBarOverlay?.let { windowManager?.removeView(it) }
        bannerOverlay?.let { windowManager?.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}