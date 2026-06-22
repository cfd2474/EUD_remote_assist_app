package com.cfd2474.eudremoteassist.service

import android.Manifest
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cfd2474.eudremoteassist.BuildInfo
import com.cfd2474.eudremoteassist.MainActivity
import com.cfd2474.eudremoteassist.PingActivity
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import com.cfd2474.eudremoteassist.network.NetworkManager
import com.cfd2474.eudremoteassist.network.WebSocketMessageListener
import com.cfd2474.eudremoteassist.receiver.DeviceAdminReceiver
import com.cfd2474.eudremoteassist.remote.RemoteControlHandler
import com.cfd2474.eudremoteassist.session.RemoteSessionState
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.google.gson.JsonObject

class DeviceGatewayService : Service(), WebSocketMessageListener {

    companion object {
        private const val TAG = "DeviceGateway"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "device_gateway"
        private const val ACTION_RESTRICTIONS_CHANGED = Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED
        const val ACTION_SEND_TELEMETRY = "com.cfd2474.eudremoteassist.ACTION_SEND_TELEMETRY"

        @Volatile
        var instance: DeviceGatewayService? = null
            private set
    }

    private lateinit var config: ManagedConfigManager
    private lateinit var networkManager: NetworkManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    private var lastConnectedTime = 0L
    private var isRecoveryInProgress = false
    private var recoveryRunnable: Runnable? = null

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Application restrictions changed, reloading config")
            setupTelemetrySchedule()
            // Reconnect websocket to pick up new configurations if needed
            networkManager.disconnectWebSocket()
            networkManager.connectWebSocket()
        }
    }

    // Telemetry runnables
    private val telemetryRunnable = object : Runnable {
        override fun run() {
            sendTelemetryReport()
            val intervalMinutes = config.getTrackingInterval()
            handler.postDelayed(this, intervalMinutes * 60 * 1000L)
        }
    }

    // Reconnect & Heartbeat scheduler (45 seconds)
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (networkManager.isWebSocketConnected()) {
                Log.d(TAG, "Sending WebSocket keepalive ping")
                val pingMsg = JsonObject().apply { addProperty("type", "ping") }
                networkManager.sendWebSocket(gson.toJson(pingMsg))
            } else {
                val isSessionActive = RemoteSessionState.isSessionActive || networkManager.isRemoteSessionActive()
                if (!isSessionActive && !isRecoveryInProgress) {
                    Log.i(TAG, "WebSocket disconnected. Reconnecting...")
                    networkManager.connectWebSocket()
                } else if (isRecoveryInProgress) {
                    Log.i(TAG, "WebSocket disconnected. Reconnect suspended during health check recovery.")
                } else {
                    Log.i(TAG, "WebSocket disconnected during active remote session. Log and wait.")
                }
            }
            // Repeat command poll fallback every 30s if WebSocket is offline
            if (!networkManager.isWebSocketConnected()) {
                pollCommandsFallback()
            }
            handler.postDelayed(this, 45000L)
        }
    }

    // Internal health check runnable (runs every 30 seconds)
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            runHealthCheck()
            handler.postDelayed(this, 30000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DeviceGatewayService onCreate")
        instance = this
        config = ManagedConfigManager(this)
        networkManager = NetworkManager.getInstance(this, config)
        networkManager.setWebSocketMessageListener(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        // Register application restrictions change receiver
        ContextCompat.registerReceiver(this, restrictionsReceiver, IntentFilter(ACTION_RESTRICTIONS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Connect WebSocket and launch schedulers
        networkManager.connectWebSocket()
        handler.post(heartbeatRunnable)
        setupTelemetrySchedule()

        // Initialize health check tracking
        lastConnectedTime = System.currentTimeMillis()
        handler.postDelayed(healthCheckRunnable, 30000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "DeviceGatewayService onStartCommand")
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "ForegroundServiceStartNotAllowedException caught, stopping service.")
            }
            stopSelf()
        }

        if (intent?.action == ACTION_SEND_TELEMETRY) {
            Log.i(TAG, "ACTION_SEND_TELEMETRY intent received. Triggering immediate telemetry report.")
            sendTelemetryReport()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "DeviceGatewayService onDestroy")
        if (instance === this) {
            instance = null
        }
        unregisterReceiver(restrictionsReceiver)
        handler.removeCallbacksAndMessages(null)
        networkManager.setWebSocketMessageListener(null)
        networkManager.disconnectWebSocket()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved called. Scheduling restart of DeviceGatewayService.")
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // 10.3 WebSocket Message Dispatcher
    override fun onMessageReceived(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            when (type) {
                "auth_ok" -> {
                    Log.i(TAG, "WebSocket authenticated successfully")
                    lastConnectedTime = System.currentTimeMillis()
                    cancelHealthRecovery()
                }
                "pong" -> {
                    Log.d(TAG, "Received pong keepalive")
                    lastConnectedTime = System.currentTimeMillis()
                    cancelHealthRecovery()
                }
                "command" -> {
                    val command = json.get("command")?.asString
                    if (command != null) {
                        Log.i(TAG, "Validated command: $command")
                        handleCommand(command, json)
                    }
                }
                "webrtc" -> {
                    // Forward signaling directly to ScreenShareService
                    forwardSignaling(text)
                }
                "signaling_hint" -> {
                    Log.i(TAG, "Received signaling hint: $text")
                }
                "control" -> {
                    // Handle touch and keyboard injection
                    RemoteControlHandler.handleControlMessage(json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
        }
    }

    override fun onStatusChanged(connected: Boolean) {
        Log.i(TAG, "WebSocket status changed: connected=$connected")
        if (connected) {
            lastConnectedTime = System.currentTimeMillis()
            cancelHealthRecovery()
        }
    }

    private fun runHealthCheck() {
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()

        if (serverUrl.isBlank() || secret.isNullOrBlank()) {
            return
        }

        if (networkManager.isWebSocketConnected()) {
            lastConnectedTime = System.currentTimeMillis()
            cancelHealthRecovery()
            return
        }

        if (isRecoveryInProgress) {
            return
        }

        val offlineDuration = System.currentTimeMillis() - lastConnectedTime
        if (offlineDuration > 10 * 60 * 1000L) {
            Log.w(TAG, "Health Check: WebSocket offline for ${offlineDuration / 1000}s. Initiating recovery...")
            startHealthRecovery()
        }
    }

    private fun startHealthRecovery() {
        isRecoveryInProgress = true
        Log.i(TAG, "Health Check Recovery: Refreshing connection...")
        networkManager.disconnectWebSocket()
        networkManager.connectWebSocket()
        scheduleRecoveryStep(1)
    }

    private fun scheduleRecoveryStep(step: Int) {
        recoveryRunnable?.let { handler.removeCallbacks(it) }
        val delayMs = when (step) {
            1 -> 60000L
            2 -> 60000L
            3 -> 300000L
            else -> 15 * 60 * 1000L
        }
        val runnable = Runnable {
            executeRecoveryStep(step)
        }
        recoveryRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun executeRecoveryStep(step: Int) {
        if (!isRecoveryInProgress) return

        if (networkManager.isWebSocketConnected()) {
            Log.i(TAG, "Health Check Recovery: WebSocket is back online. Recovery succeeded.")
            isRecoveryInProgress = false
            lastConnectedTime = System.currentTimeMillis()
            recoveryRunnable = null
            return
        }

        when (step) {
            1 -> {
                Log.w(TAG, "Health Check Recovery: WebSocket still offline. Initiating re-registration attempt 1...")
                attemptReRegistration(1)
            }
            2 -> {
                Log.w(TAG, "Health Check Recovery: Re-registration failed. Wait complete. Initiating re-registration attempt 2...")
                attemptReRegistration(2)
            }
            3 -> {
                Log.w(TAG, "Health Check Recovery: Re-registration failed. Wait complete. Initiating re-registration attempt 3...")
                attemptReRegistration(3)
            }
            else -> {
                Log.w(TAG, "Health Check Recovery: Wait complete. Initiating periodic re-registration (Attempt $step)...")
                attemptReRegistration(step)
            }
        }
    }

    private fun attemptReRegistration(attemptNum: Int) {
        val friendlyDeviceName = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        } catch (e: Exception) {
            Build.MODEL
        }

        networkManager.registerDevice(
            deviceName = friendlyDeviceName,
            model = Build.MODEL,
            appVersion = BuildInfo.VERSION_NAME,
            agency = config.getAgency()
        ) { success, error ->
            handler.post {
                if (!isRecoveryInProgress) return@post

                if (success) {
                    Log.i(TAG, "Health Check Recovery: Re-registration succeeded on attempt $attemptNum. Reconnecting WebSocket...")
                    isRecoveryInProgress = false
                    lastConnectedTime = System.currentTimeMillis()
                    recoveryRunnable = null

                    networkManager.disconnectWebSocket()
                    networkManager.connectWebSocket()

                    // Send telemetry report immediately upon successful re-registration
                    sendTelemetryReport()
                } else {
                    Log.w(TAG, "Health Check Recovery: Re-registration failed on attempt $attemptNum: $error")
                    scheduleRecoveryStep(attemptNum + 1)
                }
            }
        }
    }

    private fun cancelHealthRecovery() {
        if (isRecoveryInProgress) {
            Log.i(TAG, "Health Check Recovery: Cancelling recovery because WebSocket is connected.")
            isRecoveryInProgress = false
        }
        recoveryRunnable?.let {
            handler.removeCallbacks(it)
            recoveryRunnable = null
        }
    }

    // Helper to send device events over WebSocket
    private fun sendDeviceEvent(event: String, payload: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("type", "device_event")
            addProperty("event", event)
            add("payload", payload)
        }
        networkManager.sendWebSocket(gson.toJson(msg))
    }

    private var unlockAttempt = 0
    private val checkUnlockRunnable = object : Runnable {
        override fun run() {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isKeyguardLocked) {
                Log.i(TAG, "Device successfully unlocked via REMOTE_UNLOCK")
                sendDeviceEvent("COMMAND_HANDLED", JsonObject().apply {
                    addProperty("command", "REMOTE_UNLOCK")
                })
                // Keyguard is now unlocked, resume screen share / remote session setup automatically
                handleCommand("START_REMOTE_ADMIN")
            } else if (unlockAttempt < 10) {
                unlockAttempt++
                handler.postDelayed(this, 500L)
            } else {
                Log.w(TAG, "Device failed to unlock after 5 seconds")
                sendDeviceEvent("COMMAND_FAILED", JsonObject().apply {
                    addProperty("command", "REMOTE_UNLOCK")
                    addProperty("error", "Incorrect PIN or unlock timeout")
                })
            }
        }
    }

    fun notifyPasswordFailed() {
        handler.post {
            Log.w(TAG, "notifyPasswordFailed: Incorrect PIN detected!")
            handler.removeCallbacks(checkUnlockRunnable)
            sendDeviceEvent("COMMAND_FAILED", JsonObject().apply {
                addProperty("command", "REMOTE_UNLOCK")
                addProperty("error", "Incorrect PIN or password")
            })
        }
    }

    // 10.4 Command Executions
    private fun handleCommand(command: String, json: JsonObject = JsonObject()) {
        Log.i(TAG, "Handling command: $command")
        when (command) {
            "START_REMOTE_ADMIN" -> {
                val iceServersJson = json.getAsJsonArray("iceServers")?.toString()
                wakeDevice()
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguardManager.isKeyguardLocked) {
                    Log.i(TAG, "Device is locked. Reporting DEVICE_LOCKED event to portal.")
                    sendDeviceEvent("DEVICE_LOCKED", JsonObject().apply {
                        addProperty("reason", "PIN required for remote access")
                    })
                    return
                }

                RemoteSessionState.isSessionActive = true
                networkManager.setRemoteSessionActive(true)

                // Start OverlayService
                val overlayIntent = Intent(this, OverlayService::class.java)
                startService(overlayIntent)

                // Launch MainActivity for MediaProjection
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    action = "com.cfd2474.eudremoteassist.ACTION_REQUEST_PROJECTION"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    if (iceServersJson != null) {
                        putExtra("iceServers", iceServersJson)
                    }
                }
                startActivity(mainIntent)
            }
            "REMOTE_UNLOCK" -> {
                val encryptedPin = json.get("pin")?.asString
                if (!encryptedPin.isNullOrEmpty()) {
                    try {
                        val pin = com.cfd2474.eudremoteassist.crypto.CryptoManager.decryptPayload(this, encryptedPin)
                        wakeDevice()
                        val accessibilityService = RemoteAssistAccessibilityService.instance
                        if (accessibilityService != null) {
                            handler.postDelayed({
                                accessibilityService.performRemoteUnlock(pin)
                                unlockAttempt = 0
                                handler.removeCallbacks(checkUnlockRunnable)
                                handler.post(checkUnlockRunnable)
                            }, 500L)
                        } else {
                            Log.w(TAG, "Accessibility service not running, cannot unlock")
                            sendDeviceEvent("COMMAND_FAILED", JsonObject().apply {
                                addProperty("command", "REMOTE_UNLOCK")
                                addProperty("error", "Accessibility service not enabled")
                            })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decrypt PIN payload", e)
                        sendDeviceEvent("COMMAND_FAILED", JsonObject().apply {
                            addProperty("command", "REMOTE_UNLOCK")
                            addProperty("error", "Decryption failed")
                        })
                    }
                } else {
                    Log.w(TAG, "REMOTE_UNLOCK received but pin is null or empty")
                    sendDeviceEvent("COMMAND_FAILED", JsonObject().apply {
                        addProperty("command", "REMOTE_UNLOCK")
                        addProperty("error", "PIN is empty")
                    })
                }
            }
            "STOP_REMOTE_ADMIN" -> {
                stopRemoteAssist()
            }
            "LOCK_DEVICE" -> {
                lockDevice()
            }
            "REQUEST_LOCATION" -> {
                sendTelemetryReport()
            }
            "TRIGGER_PING" -> {
                val pingIntent = Intent(this, PingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(pingIntent)
            }
            "RESYNC_DEVICE_INFO" -> {
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
                val friendlyDeviceName = try {
                    Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
                } catch (e: Exception) {
                    Build.MODEL
                }
                networkManager.registerDevice(
                    deviceName = friendlyDeviceName,
                    model = Build.MODEL,
                    appVersion = BuildInfo.VERSION_NAME,
                    agency = config.getAgency()
                ) { success, error ->
                    Log.i(TAG, "Register resync result: success=$success, error=$error")
                    if (success) {
                        sendTelemetryReport()
                    }
                }
            }
        }
    }

    private fun wakeDevice() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EUDRemoteAssist:WakeLock"
        )
        wakeLock.acquire(10000) // Acquire for 10 seconds
    }

    private fun stopRemoteAssist() {
        Log.i(TAG, "Stopping Remote Admin Session")
        val overlayIntent = Intent(this, OverlayService::class.java)
        stopService(overlayIntent)

        val shareIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        startService(shareIntent)

        RemoteSessionState.reset()
        networkManager.setRemoteSessionActive(false)
    }

    private fun lockDevice() {
        Log.i(TAG, "Locking device")
        stopRemoteAssist()

        // Go to Home screen (first press)
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(homeIntent)

        // Second Home press after 300ms to return to home page
        handler.postDelayed({
            try {
                startActivity(homeIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send second home intent: ${e.message}")
            }
        }, 300L)

        // Lock using device policy manager after a short delay to ensure Home transition completes
        handler.postDelayed({
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                try {
                    dpm.lockNow()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to lock device (admin active but failed): ${e.message}")
                }
            } else {
                Log.w(TAG, "DeviceAdminReceiver is not active. Cannot lock device.")
            }
        }, 1000L) // 1 second delay to allow home transition
    }

    private fun forwardSignaling(messageText: String) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked && ScreenShareService.instance == null) {
            Log.w(TAG, "Device is locked and ScreenShareService is not running. Ignoring signaling message to avoid premature WebRTC connection.")
            return
        }

        val activeService = ScreenShareService.instance
        if (activeService != null) {
            Log.d(TAG, "Forwarding signaling message directly in-memory")
            activeService.handleSignalingFromGateway(messageText)
        } else {
            Log.w(TAG, "ScreenShareService is not running. Falling back to Intent-based start.")
            val forwardIntent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_SIGNAL
                putExtra(ScreenShareService.EXTRA_SIGNAL, messageText)
            }
            startService(forwardIntent)
        }
    }

    // Command Polling Fallback (every 30 seconds if WebSocket is down)
    private fun pollCommandsFallback() {
        Log.d(TAG, "Polling commands via REST fallback...")
        networkManager.pollCommands { success, response ->
            if (success && !response.isNullOrBlank()) {
                try {
                    val json = gson.fromJson(response, JsonObject::class.java)
                    val commandsArray = json.getAsJsonArray("commands")
                    if (commandsArray != null) {
                        for (element in commandsArray) {
                            val cmdObj = element.asJsonObject
                            val command = cmdObj.get("command")?.asString
                            if (command != null) {
                                Log.i(TAG, "Received command via poll fallback: $command")
                                handler.post { handleCommand(command, cmdObj) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse command poll response: ${e.message}")
                }
            }
        }
    }

    // Telemetry reporting logic
    private fun setupTelemetrySchedule() {
        handler.removeCallbacks(telemetryRunnable)
        val intervalMinutes = config.getTrackingInterval()
        handler.postDelayed(telemetryRunnable, intervalMinutes * 60 * 1000L)
    }

    private fun sendTelemetryReport() {
        val batteryLevel = getBatteryLevel()
        val isCharging = getBatteryChargingStatus()
        Log.i(TAG, "Initiating immediate telemetry report. Battery: $batteryLevel%, Charging: $isCharging")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permissions not granted, sending telemetry with 0.0, 0.0")
            networkManager.sendTelemetry(0.0, 0.0, 0f, batteryLevel, isCharging) { success, error ->
                Log.i(TAG, "Telemetry sending result (no location permission): success=$success, error=$error")
            }
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d(TAG, "Using last known location for telemetry: ${location.latitude}, ${location.longitude}")
                    networkManager.sendTelemetry(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        batteryLevel,
                        isCharging
                    ) { success, error ->
                        Log.i(TAG, "Telemetry sending result (last location): success=$success, error=$error")
                    }
                } else {
                    Log.i(TAG, "Last location is null. Requesting fresh location update...")
                    // Force a single location update request
                    val cancellationTokenSource = CancellationTokenSource()
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                        .addOnSuccessListener { loc: Location? ->
                            val lat = loc?.latitude ?: 0.0
                            val lon = loc?.longitude ?: 0.0
                            val acc = loc?.accuracy ?: 0f
                            Log.d(TAG, "Fresh location retrieved: $lat, $lon")
                            networkManager.sendTelemetry(lat, lon, acc, batteryLevel, isCharging) { success, error ->
                                Log.i(TAG, "Telemetry sending result (fresh location): success=$success, error=$error")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to get current location: ${e.message}")
                            networkManager.sendTelemetry(0.0, 0.0, 0f, batteryLevel, isCharging) { success, error ->
                                Log.i(TAG, "Telemetry sending result (location request failed): success=$success, error=$error")
                            }
                        }
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get last location: ${e.message}")
                networkManager.sendTelemetry(0.0, 0.0, 0f, batteryLevel, isCharging) { success, error ->
                    Log.i(TAG, "Telemetry sending result (last location request failed): success=$success, error=$error")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during location check: ${e.message}")
            networkManager.sendTelemetry(0.0, 0.0, 0f, batteryLevel, isCharging) { success, error ->
                Log.i(TAG, "Telemetry sending result (security exception): success=$success, error=$error")
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            50
        }
    }

    private fun getBatteryChargingStatus(): Boolean {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    // Foreground Notifications Channel
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps device connection active with Remote Assist portal"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EUD Remote Assist Active")
            .setContentText("Maintaining connection to portal server")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
