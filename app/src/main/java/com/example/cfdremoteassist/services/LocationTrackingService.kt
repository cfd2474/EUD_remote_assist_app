package com.example.cfdremoteassist.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.cfdremoteassist.receivers.RemoteAssistDeviceAdminReceiver
import com.example.cfdremoteassist.utils.ManagedConfigManager
import com.example.cfdremoteassist.utils.NetworkManager
import com.google.android.gms.location.*
import java.util.concurrent.TimeUnit

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var configManager: ManagedConfigManager
    private lateinit var networkManager: NetworkManager
    private var trackingServerUrl: String? = null
    private var trackingIntervalMinutes: Int = 15

    private var ringtone: Ringtone? = null
    private var originalVolume: Int = -1
    private var pingOverlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopPingRunnable: Runnable? = null
    
    private val deviceUpdateHandler = Handler(Looper.getMainLooper())
    private var deviceUpdateRunnable: Runnable? = null
    
    private val wsRetryHandler = Handler(Looper.getMainLooper())
    private var wsRetryRunnable: Runnable? = null
    
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    companion object {
        const val ACTION_STOP_PING = "com.example.cfdremoteassist.STOP_PING"
        const val ACTION_TRIGGER_PING = "com.example.cfdremoteassist.TRIGGER_PING"
        const val ACTION_REQUEST_LOCATION = "com.example.cfdremoteassist.REQUEST_LOCATION"
        const val ACTION_START_REMOTE_ADMIN = "com.example.cfdremoteassist.START_REMOTE_ADMIN"
        const val ACTION_STOP_REMOTE_ADMIN = "com.example.cfdremoteassist.STOP_REMOTE_ADMIN"
        const val ACTION_LOCK_DEVICE = "com.example.cfdremoteassist.LOCK_DEVICE"
    }

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadManagedConfigurations()
            startLocationUpdates() // Restart with new interval
        }
    }

    override fun onCreate() {
        super.onCreate()
        configManager = ManagedConfigManager(this)
        networkManager = NetworkManager(this, configManager)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        loadManagedConfigurations()
        
        registerReceiver(restrictionsReceiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED))
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    sendLocationToServer(location)
                    checkDeviceUpdatePulse()
                }
            }
        }
        
        scheduleDeviceUpdatePulse()
        connectRealTimeGateway()
        scheduleWSPulse()
        schedulePollPulse()
    }

    private fun schedulePollPulse() {
        pollRunnable = object : Runnable {
            override fun run() {
                networkManager.pollCommands { commands ->
                    commands.forEach { handleRemoteCommand(it) }
                }
                pollHandler.postDelayed(this, TimeUnit.SECONDS.toMillis(30))
            }
        }
        pollHandler.postDelayed(pollRunnable!!, TimeUnit.SECONDS.toMillis(30))
    }

    private fun scheduleWSPulse() {
        wsRetryRunnable = object : Runnable {
            override fun run() {
                // Ensure WS is connected if secret is present
                val secret = configManager.getConnectionSecret()
                if (secret.isNotEmpty()) {
                    connectRealTimeGateway() // Logic inside NetworkManager handles existing connections
                }
                wsRetryHandler.postDelayed(this, TimeUnit.MINUTES.toMillis(1))
            }
        }
        wsRetryHandler.postDelayed(wsRetryRunnable!!, TimeUnit.MINUTES.toMillis(1))
    }

    private fun connectRealTimeGateway() {
        val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val secret = configManager.getConnectionSecret()
        
        if (secret.isEmpty()) {
            Log.w("LocationTracking", "No connection secret, skipping WS gateway")
            return
        }

        networkManager.connectWebSocket(uid, secret) { command ->
            handleRemoteCommand(command)
        }
    }

    private fun handleRemoteCommand(command: String) {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = when (command) {
                "TRIGGER_PING" -> ACTION_TRIGGER_PING
                "REQUEST_LOCATION" -> ACTION_REQUEST_LOCATION
                "START_REMOTE_ADMIN" -> ACTION_START_REMOTE_ADMIN
                "STOP_REMOTE_ADMIN" -> ACTION_STOP_REMOTE_ADMIN
                "LOCK_DEVICE" -> ACTION_LOCK_DEVICE
                else -> null
            }
        }
        if (intent.action != null) {
            startService(intent)
        }
    }

    private fun scheduleDeviceUpdatePulse() {
        deviceUpdateRunnable = object : Runnable {
            override fun run() {
                checkDeviceUpdatePulse()
                deviceUpdateHandler.postDelayed(this, TimeUnit.MINUTES.toMillis(15))
            }
        }
        deviceUpdateHandler.post(deviceUpdateRunnable!!)
    }

    private fun checkDeviceUpdatePulse() {
        val lastUpdate = configManager.getLastDeviceUpdate()
        val now = System.currentTimeMillis()
        val twentyFourHours = TimeUnit.HOURS.toMillis(24)

        if (now - lastUpdate >= twentyFourHours) {
            sendDeviceRegistration()
        }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun sendDeviceRegistration() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val deviceInfo = mutableMapOf<String, String>()
        deviceInfo["uid"] = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        deviceInfo["serial"] = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else "unknown"
        } catch (e: SecurityException) {
            "permission_denied"
        }
        deviceInfo["imei"] = try { telephonyManager.deviceId ?: "unknown" } catch (e: Exception) { "permission_denied" }
        deviceInfo["phone_number"] = try { telephonyManager.line1Number ?: "unknown" } catch (e: Exception) { "unknown" }
        deviceInfo["device_name"] = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        deviceInfo["model"] = Build.MODEL
        deviceInfo["app_version"] = "1.0.0"

        Log.d("LocationTracking", "Sending Device Pulse Update: $deviceInfo to ${configManager.getTrackingServerUrl()}")
        
        networkManager.register(deviceInfo) { success, error ->
            if (success) {
                configManager.setLastDeviceUpdate(System.currentTimeMillis())
            } else {
                Log.e("LocationTracking", "Device pulse update failed: $error")
            }
        }
    }

    private fun loadManagedConfigurations() {
        val restrictionsManager = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val appRestrictions = restrictionsManager.applicationRestrictions
        
        trackingServerUrl = appRestrictions.getString("tracking_server_url", "https://example.com/track")
        trackingIntervalMinutes = appRestrictions.getInt("tracking_interval", 15)
        
        Log.d("LocationTracking", "Config loaded: URL=$trackingServerUrl, Interval=$trackingIntervalMinutes")
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            Log.e("LocationTracking", "Failed to start foreground service: ${e.message}")
            // Fallback: stop the service if we can't start foreground to avoid ANR/Crash
            stopSelf()
            return START_NOT_STICKY
        }
        
        when (intent?.action) {
            ACTION_TRIGGER_PING -> startAudiblePing()
            ACTION_STOP_PING -> stopAudiblePing()
            ACTION_REQUEST_LOCATION -> requestImmediateLocation()
            ACTION_START_REMOTE_ADMIN -> startRemoteAdminIndicators()
            ACTION_STOP_REMOTE_ADMIN -> stopRemoteAdminIndicators()
            ACTION_LOCK_DEVICE -> lockDeviceNow()
            else -> startLocationUpdates()
        }
        
        return START_STICKY
    }

    private fun startRemoteAdminIndicators() {
        Log.d("LocationTracking", "Starting remote admin indicators")
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRemoteAdminIndicators() {
        Log.d("LocationTracking", "Stopping remote admin indicators")
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun lockDeviceNow() {
        Log.d("LocationTracking", "Remote lock request received")
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, RemoteAssistDeviceAdminReceiver::class.java)
        
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.lockNow()
            } catch (e: SecurityException) {
                Log.e("LocationTracking", "Failed to lock device. Ensure app is Device Admin.", e)
            }
        } else {
            Log.e("LocationTracking", "App is not an active device admin. Cannot lock screen.")
        }
    }

    private fun requestImmediateLocation() {
        Log.d("LocationTracking", "Immediate location request received")
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let { sendLocationToServer(it) }
            }
        } catch (e: SecurityException) {
            Log.e("LocationTracking", "Permission denied for immediate location", e)
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 
            TimeUnit.MINUTES.toMillis(trackingIntervalMinutes.toLong())
        ).setMinUpdateIntervalMillis(TimeUnit.MINUTES.toMillis(1))
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            Log.e("LocationTracking", "Lost location permission. Could not request updates. $unlikely")
        }
    }

    private fun sendLocationToServer(location: Location) {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            registerReceiver(null, ifilter)
        }
        val batteryLevel: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (batteryScale > 0) (batteryLevel * 100 / batteryScale) else -1

        val chargingStatus = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || 
                         chargingStatus == BatteryManager.BATTERY_STATUS_FULL

        val payload = mutableMapOf<String, Any>()
        payload["uid"] = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        payload["lat"] = location.latitude
        payload["lon"] = location.longitude
        payload["battery"] = batteryPct
        payload["is_charging"] = isCharging
        payload["timestamp"] = System.currentTimeMillis()

        Log.d("LocationTracking", "Sending telemetry: $payload")
        networkManager.sendTelemetry(payload)
    }

    private fun startAudiblePing() {
        Log.d("LocationTracking", "Starting 2-minute audible ping")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
        
        ringtone?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }

        showPingPopup()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(isPingActive = true))

        stopPingRunnable = Runnable { stopAudiblePing() }
        handler.postDelayed(stopPingRunnable!!, TimeUnit.MINUTES.toMillis(2))
    }

    private fun showPingPopup() {
        if (pingOverlayView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(40, 40, 40, 40)
            elevation = 20f
        }

        val textView = TextView(this).apply {
            text = "Locating Phone"
            setTextColor(Color.BLACK)
            textSize = 20f
            setPadding(0, 0, 0, 30)
            gravity = Gravity.CENTER
        }

        val button = Button(this).apply {
            text = "Acknowledge"
            setOnClickListener {
                stopAudiblePing()
            }
        }

        layout.addView(textView)
        layout.addView(button)

        pingOverlayView = layout
        try {
            wm.addView(pingOverlayView, params)
        } catch (e: Exception) {
            Log.e("LocationTracking", "Failed to add ping overlay", e)
            pingOverlayView = null
        }
    }

    private fun stopAudiblePing() {
        Log.d("LocationTracking", "Stopping audible ping")
        ringtone?.stop()
        ringtone = null
        
        stopPingRunnable?.let { handler.removeCallbacks(it) }
        
        if (originalVolume != -1) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_RING, originalVolume, 0)
            originalVolume = -1
        }

        pingOverlayView?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(it)
            } catch (e: Exception) {}
            pingOverlayView = null
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(isPingActive = false))
    }

    private fun createNotification(isPingActive: Boolean = false): Notification {
        val channelId = "location_tracking_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isPingActive) "Device Ping Active" else "Location Tracking Active")
            .setContentText(if (isPingActive) "A remote administrator is pinging this device" else "Reporting location to management server")
            .setSmallIcon(if (isPingActive) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_menu_mylocation)
            .setPriority(if (isPingActive) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        if (isPingActive) {
            val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_PING
            }
            val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Acknowledge & Silence", pendingStopIntent)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(restrictionsReceiver)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        deviceUpdateRunnable?.let { deviceUpdateHandler.removeCallbacks(it) }
        wsRetryRunnable?.let { wsRetryHandler.removeCallbacks(it) }
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        networkManager.disconnectWebSocket()
    }
}