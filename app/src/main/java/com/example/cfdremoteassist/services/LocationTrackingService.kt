package com.example.cfdremoteassist.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
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
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.cfdremoteassist.receivers.RemoteAssistDeviceAdminReceiver
import com.example.cfdremoteassist.remote.RemoteSessionManager
import com.example.cfdremoteassist.utils.ManagedConfigManager
import com.example.cfdremoteassist.utils.NetworkManager
import com.google.android.gms.location.*
import com.google.gson.JsonObject
import org.json.JSONObject
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
        const val ACTION_RESYNC_DEVICE_INFO = "com.example.cfdremoteassist.RESYNC_DEVICE_INFO"
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
        networkManager = NetworkManager.getInstance(this, configManager)
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
                    commands.forEach { handleIncomingJsonCommand(it) }
                }
                pollHandler.postDelayed(this, TimeUnit.SECONDS.toMillis(30))
            }
        }
        pollHandler.postDelayed(pollRunnable!!, TimeUnit.SECONDS.toMillis(30))
    }

    private fun scheduleWSPulse() {
        wsRetryRunnable = object : Runnable {
            override fun run() {
                val secret = configManager.getConnectionSecret()
                if (secret.isNotEmpty()) {
                    if (!networkManager.isWebSocketConnected()) {
                        // CRITICAL: Do not reconnect if a remote session is actively negotiating
                        if (!networkManager.isSessionActive()) {
                            Log.i("LocationTracking", "WebSocket disconnected, attempting reconnect...")
                            connectRealTimeGateway() 
                        } else {
                            Log.w("LocationTracking", "WS disconnected during active session. Waiting for session cleanup or manual recovery.")
                        }
                    } else {
                        // Send keepalive every 30s as per spec
                        networkManager.sendKeepAlive()
                    }
                }
                wsRetryHandler.postDelayed(this, TimeUnit.SECONDS.toMillis(45))
            }
        }
        wsRetryHandler.postDelayed(wsRetryRunnable!!, TimeUnit.SECONDS.toMillis(45))
    }

    private fun connectRealTimeGateway() {
        val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val secret = configManager.getConnectionSecret()
        
        if (secret.isEmpty()) {
            Log.w("LocationTracking", "No connection secret, skipping WS gateway")
            return
        }

        networkManager.connectWebSocket(uid, secret) { json ->
            handleGenericWSMessage(json)
        }
    }

    private fun handleGenericWSMessage(json: JsonObject) {
        RemoteSessionManager.lastHeartbeatReceivedAt = System.currentTimeMillis()
        try {
            val secret = configManager.getConnectionSecret()
            val type = json.get("type")?.asString
            
            when (type) {
                "command" -> {
                    val incomingSecret = json.get("connection_secret")?.asString
                    if (incomingSecret == secret) {
                        handleIncomingJsonCommand(json)
                    }
                }
                "webrtc" -> {
                    val intent = Intent(this, ScreenShareService::class.java).apply {
                        action = ScreenShareService.ACTION_PROCESS_SIGNAL
                        putExtra(ScreenShareService.EXTRA_SIGNAL, json.toString())
                    }
                    startService(intent)
                }
                "control" -> {
                    handleRemoteControl(json)
                }
                "auth_ok" -> Log.i("LocationTracking", "WebSocket Authenticated")
                "pong" -> Log.d("LocationTracking", "WS Heartbeat received")
            }
        } catch (e: Exception) {
            Log.e("LocationTracking", "Error handling WS message: ${e.message}")
        }
    }

    private fun wakeUpDevice() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "RemoteAssist:WakeUp"
            )
            wakeLock.acquire(3000)
            Log.d("LocationTracking", "Device wake-up signal sent")
        } catch (e: Exception) {
            Log.e("LocationTracking", "Failed to wake up device: ${e.message}")
        }
    }

    private fun handleIncomingJsonCommand(json: JsonObject) {
        try {
            val cmd = json.get("command")?.asString ?: return
            Log.i("LocationTracking", "Executing Command: $cmd")
            
            // Standard commands often come with connection_secret for verification
            val cmdSecret = json.get("connection_secret")?.asString
            val mySecret = configManager.getConnectionSecret()
            if (!cmdSecret.isNullOrEmpty() && !mySecret.isNullOrEmpty() && cmdSecret != mySecret) {
                Log.w("LocationTracking", "Rejecting command $cmd: Secret mismatch")
                sendEventToServer("COMMAND_REJECTED", mapOf("command" to cmd, "error" to "Secret mismatch"))
                return
            }

            if (cmd == "START_REMOTE_ADMIN") {
                RemoteSessionManager.isSessionActive = true
                networkManager.setSessionActive(true)
                wakeUpDevice()
            } else if (cmd == "STOP_REMOTE_ADMIN") {
                RemoteSessionManager.isSessionActive = false
                networkManager.setSessionActive(false)
            } else if (cmd == "REMOTE_UNLOCK") {
                val pin = json.get("pin")?.asString
                if (!pin.isNullOrEmpty()) {
                    RemoteAssistAccessibilityService.instance?.performRemoteUnlock(pin)
                    sendEventToServer("COMMAND_HANDLED", mapOf("command" to cmd))
                    return
                }
            }
            
            val intent = Intent(this, LocationTrackingService::class.java).apply {
                action = when (cmd) {
                    "TRIGGER_PING" -> ACTION_TRIGGER_PING
                    "REQUEST_LOCATION" -> ACTION_REQUEST_LOCATION
                    "START_REMOTE_ADMIN" -> ACTION_START_REMOTE_ADMIN
                    "STOP_REMOTE_ADMIN" -> ACTION_STOP_REMOTE_ADMIN
                    "LOCK_DEVICE" -> ACTION_LOCK_DEVICE
                    "RESYNC_DEVICE_INFO" -> {
                        putExtra("is_manual", true)
                        ACTION_RESYNC_DEVICE_INFO
                    }
                    else -> null
                }
            }
            
            if (intent.action != null) {
                startService(intent)
                sendEventToServer("COMMAND_HANDLED", mapOf("command" to cmd))
            } else {
                Log.w("LocationTracking", "Unknown command: $cmd")
                sendEventToServer("COMMAND_FAILED", mapOf("command" to cmd, "error" to "Unknown command"))
            }
        } catch (e: Exception) {
            Log.e("LocationTracking", "Error processing command: ${e.message}")
        }
    }

    private fun sendEventToServer(event: String, payload: Map<String, Any> = emptyMap()) {
        networkManager.sendEvent(event, payload)
    }

    private fun handleRemoteControl(json: JsonObject) {
        val accessibilityService = RemoteAssistAccessibilityService.instance
        if (accessibilityService == null) {
            Log.w("LocationTracking", "Control rejected: Accessibility Service not running")
            return
        }

        try {
            val jsonString = json.toString()
            val jsonObject = JSONObject(jsonString)
            accessibilityService.onControlMessage(jsonObject)
        } catch (e: Exception) {
            Log.e("LocationTracking", "Error executing control input", e)
        }
    }

    private fun handleRemoteCommand(command: String) {
        // This old method is replaced by handleIncomingJsonCommand
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

    @SuppressLint("MissingPermission")
    private fun sendDeviceRegistration(isManualResync: Boolean = false) {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        
        val deviceInfo = mutableMapOf<String, String>()
        deviceInfo["uid"] = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        var phoneNumber: String? = null
        try {
            phoneNumber = telephonyManager.line1Number
            if (phoneNumber.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                if (!activeSubscriptions.isNullOrEmpty()) {
                    for (info in activeSubscriptions) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                val n = subscriptionManager.getPhoneNumber(info.subscriptionId)
                                if (n.isNotEmpty()) {
                                    phoneNumber = n
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                        @Suppress("DEPRECATION")
                        val n = info.number
                        if (!n.isNullOrEmpty()) {
                            phoneNumber = n
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        
        deviceInfo["phone_number"] = phoneNumber ?: "unknown"
        deviceInfo["device_name"] = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        deviceInfo["model"] = Build.MODEL
        deviceInfo["app_version"] = "1.2.1"

        val agency = configManager.getAgency()
        if (agency.isNotEmpty()) {
            deviceInfo["agency"] = agency
        }

        Log.d("LocationTracking", "Sending Device Pulse Update: $deviceInfo to ${configManager.getTrackingServerUrl()}")
        
        networkManager.register(deviceInfo) { success, error ->
            if (success) {
                configManager.setLastDeviceUpdate(System.currentTimeMillis())
                if (isManualResync) {
                    sendEventToServer("DEVICE_INFO_RESYNCED", mapOf(
                        "command" to "RESYNC_DEVICE_INFO",
                        "agency" to (deviceInfo["agency"] ?: ""),
                        "device_name" to (deviceInfo["device_name"] ?: "")
                    ))
                }
            } else {
                Log.e("LocationTracking", "Device registration failed: $error")
                if (isManualResync) {
                    sendEventToServer("COMMAND_FAILED", mapOf(
                        "command" to "RESYNC_DEVICE_INFO",
                        "error" to (error ?: "Unknown error")
                    ))
                }
            }
        }
    }

    private fun loadManagedConfigurations() {
        val restrictionsManager = getSystemService(RESTRICTIONS_SERVICE) as RestrictionsManager
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
            ACTION_RESYNC_DEVICE_INFO -> {
                val isManual = intent.getBooleanExtra("is_manual", false)
                sendDeviceRegistration(isManualResync = isManual)
            }
            else -> startLocationUpdates()
        }
        
        return START_STICKY
    }

    private fun startRemoteAdminIndicators() {
        Log.d("LocationTracking", "Starting remote admin indicators")
        val overlayIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }
        
        // Check if locked and notify server
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            sendEventToServer("DEVICE_LOCKED", mapOf("reason" to "PIN required for full access"))
            // Force a wakeup again just before swiping starts
            wakeUpDevice()
        }

        // Launch MainActivity to trigger the Screen Capture permission dialog
        val mainIntent = Intent(this, com.example.cfdremoteassist.MainActivity::class.java).apply {
            action = "TRIGGER_SCREEN_SHARE"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(mainIntent)

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Remote Admin: Requesting Screen Share Permission", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRemoteAdminIndicators() {
        Log.d("LocationTracking", "Stopping remote admin indicators")
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenShareService::class.java))
    }

    private fun lockDeviceNow() {
        Log.i("LocationTracking", "Executing Remote Lock")
        
        // 0. Tear down remote assist if active
        stopRemoteAdminIndicators()
        RemoteSessionManager.isSessionActive = false
        networkManager.setSessionActive(false)

        // 1. Go to home screen first to suppress active apps
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e("LocationTracking", "Failed to navigate to home screen before lock: ${e.message}")
        }

        // 2. Initiate lock
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, RemoteAssistDeviceAdminReceiver::class.java)
        
        if (dpm.isAdminActive(adminComponent)) {
            try {
                // Brief delay ensures the Home Screen intent has started its transition
                Handler(Looper.getMainLooper()).postDelayed({
                    dpm.lockNow()
                }, 500)
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
        payload["accuracy_m"] = location.accuracy
        payload["battery"] = batteryPct
        payload["is_charging"] = isCharging
        payload["timestamp"] = System.currentTimeMillis()

        Log.d("LocationTracking", "Sending telemetry: $payload")
        networkManager.sendTelemetry(payload)
    }

    private fun startAudiblePing() {
        Log.d("LocationTracking", "Starting 2-minute audible ping")
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
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

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(isPingActive = true))

        stopPingRunnable = Runnable { stopAudiblePing() }
        handler.postDelayed(stopPingRunnable!!, TimeUnit.MINUTES.toMillis(2))
    }

    private fun showPingPopup() {
        if (pingOverlayView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_RING, originalVolume, 0)
            originalVolume = -1
        }

        pingOverlayView?.let {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(it)
            } catch (e: Exception) {}
            pingOverlayView = null
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(isPingActive = false))
    }

    private fun createNotification(isPingActive: Boolean = false): Notification {
        val channelId = "location_tracking_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Tracking",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val mainIntent = Intent(this, com.example.cfdremoteassist.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isPingActive) "Device Ping Active" else "Location Tracking Active")
            .setContentText(if (isPingActive) "A remote administrator is pinging this device" else "Reporting location to management server")
            .setSmallIcon(if (isPingActive) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_menu_mylocation)
            .setPriority(if (isPingActive) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

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