package com.cfd2474.eudremoteassist.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import com.cfd2474.eudremoteassist.crypto.CryptoManager
import com.cfd2474.eudremoteassist.session.RemoteSessionState
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

interface WebSocketMessageListener {
    fun onMessageReceived(text: String)
    fun onStatusChanged(connected: Boolean)
}

class NetworkManager private constructor(
    private val context: Context,
    private val config: ManagedConfigManager
) {
    companion object {
        private const val TAG = "NetworkManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: NetworkManager? = null

        fun getInstance(context: Context, config: ManagedConfigManager): NetworkManager {
            return instance ?: synchronized(this) {
                instance ?: NetworkManager(context.applicationContext, config).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private var _client: OkHttpClient? = null
    private var _lastPinHash: String? = null
    private var _lastServerUrl: String? = null

    @Synchronized
    private fun getClient(): OkHttpClient {
        val currentPinHash = config.getTlsPinHash()
        val currentServerUrl = config.getTrackingServerUrl()
        
        if (_client != null && currentPinHash == _lastPinHash && currentServerUrl == _lastServerUrl) {
            return _client!!
        }
        
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            
        if (!currentPinHash.isNullOrBlank() && !currentServerUrl.isNullOrBlank()) {
            try {
                val host = java.net.URL(currentServerUrl).host
                if (host.isNotEmpty()) {
                    val pinner = okhttp3.CertificatePinner.Builder()
                        .add(host, "sha256/$currentPinHash")
                        .build()
                    builder.certificatePinner(pinner)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Invalid tracking server URL or Pin Hash", e)
            }
        }
        
        _lastPinHash = currentPinHash
        _lastServerUrl = currentServerUrl
        _client = builder.build()
        return _client!!
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var messageListener: WebSocketMessageListener? = null
    
    @Volatile
    private var isSessionActive = false

    @Volatile
    private var lastHeartbeatTime: Long = 0L

    fun getLastHeartbeatTime(): Long {
        return lastHeartbeatTime
    }

    fun resetLastHeartbeatTime() {
        lastHeartbeatTime = 0L
    }

    fun setRemoteSessionActive(active: Boolean) {
        isSessionActive = active
    }

    fun isRemoteSessionActive(): Boolean {
        return isSessionActive
    }

    fun setWebSocketMessageListener(listener: WebSocketMessageListener?) {
        this.messageListener = listener
    }

    fun isWebSocketConnected(): Boolean {
        return isConnected
    }

    fun getDeviceUid(): String {
        // Return cached UID if we have it
        val cachedUid = config.getCachedDeviceUid()
        if (!cachedUid.isNullOrBlank()) {
            return cachedUid
        }

        var newUid = "unknown_uid"

        // 1. Try Build.getSerial() (requires READ_PHONE_STATE, works on Android 9 and below or Android 10+ if Device Owner)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                @Suppress("MissingPermission")
                val serial = android.os.Build.getSerial()
                if (!serial.isNullOrBlank() && serial != android.os.Build.UNKNOWN) {
                    newUid = "serial_$serial"
                }
            } else {
                @Suppress("DEPRECATION")
                val serial = android.os.Build.SERIAL
                if (!serial.isNullOrBlank() && serial != android.os.Build.UNKNOWN) {
                    newUid = "serial_$serial"
                }
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "Build.getSerial() SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "Build.getSerial() error: ${e.message}")
        }

        // 2. Try TelephonyManager IMEI/MEID (requires READ_PHONE_STATE, works if Device Owner or on older Android versions)
        if (newUid == "unknown_uid") {
            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                if (telephonyManager != null) {
                    @Suppress("MissingPermission")
                    val imei = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        telephonyManager.imei ?: telephonyManager.meid
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.deviceId
                    }
                    if (!imei.isNullOrBlank() && imei != "unknown") {
                        newUid = "imei_$imei"
                    }
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "TelephonyManager getImei() SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "TelephonyManager error: ${e.message}")
            }
        }

        // 3. Try Widevine MediaDrm ID (reliable hardware-backed identifier across reinstallation/signing keys, no permission needed)
        if (newUid == "unknown_uid") {
            try {
                val widevineUuid = java.util.UUID.fromString("ed90f895-d5cd-45d8-a341-5d40a2d14747")
                val mediaDrm = android.media.MediaDrm(widevineUuid)
                val widevineId = mediaDrm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                mediaDrm.close()
                if (widevineId != null && widevineId.isNotEmpty()) {
                    val hexString = widevineId.joinToString("") { String.format("%02x", it) }
                    if (hexString.isNotBlank()) {
                        newUid = "wv_$hexString"
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Widevine DRM unique ID error: ${e.message}")
            }
        }

        // 4. Fallback to Settings.Secure.ANDROID_ID
        if (newUid == "unknown_uid") {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrBlank()) {
                newUid = androidId
            }
        }

        // Cache the newly acquired UID so it never randomly changes again
        if (newUid != "unknown_uid") {
            config.setCachedDeviceUid(newUid)
        }
        
        return newUid
    }

    @Suppress("MissingPermission")
    private fun getPhoneNumber(): String {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            var number = telephonyManager.line1Number
            if (number.isNullOrEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                if (!activeSubscriptions.isNullOrEmpty()) {
                    for (info in activeSubscriptions) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            try {
                                val n = subscriptionManager.getPhoneNumber(info.subscriptionId)
                                if (n.isNotEmpty()) {
                                    number = n
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                        @Suppress("DEPRECATION")
                        val n = info.number
                        if (!n.isNullOrEmpty()) {
                            number = n
                            break
                        }
                    }
                }
            }
            return if (!number.isNullOrEmpty()) number else "unknown"
        } catch (e: Exception) {
            return "unknown"
        }
    }

    // 8.1 REST Endpoints
    
    // POST /api/v1/register
    fun registerDevice(deviceName: String, model: String, appVersion: String, agency: String, callback: (Boolean, String?) -> Unit) {
        val serverUrl = config.getTrackingServerUrl()
        val url = "$serverUrl/api/v1/register"

        val bodyJson = JsonObject().apply {
            addProperty("uid", getDeviceUid())
            addProperty("device_name", deviceName)
            addProperty("model", model)
            addProperty("app_version", appVersion)
            addProperty("phone_number", getPhoneNumber())
            addProperty("agency", agency)
            addProperty("public_key", CryptoManager.getOrGeneratePublicKey(context))
            
            val token = config.getEnrollmentToken()
            if (!token.isNullOrBlank()) {
                addProperty("enrollment_token", token)
            } else {
                Log.w(TAG, "registerDevice: getEnrollmentToken() returned null or blank! mdmOverridden=${config.isMdmOverridden()}")
            }
        }

        Log.i(TAG, "registerDevice JSON Body: $bodyJson")

        val requestBuilder = Request.Builder()
            .url(url)
            .post(gson.toJson(bodyJson).toRequestBody(JSON_MEDIA_TYPE))

        val existingSecret = config.getConnectionSecret()
        if (!existingSecret.isNullOrBlank()) {
            requestBuilder.addHeader("x-connection-secret", existingSecret)
        }

        val request = requestBuilder.build()

        getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Registration failed: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val resBody = response.body?.string()
                            val json = gson.fromJson(resBody, JsonObject::class.java)
                            val secret = json.get("connection_secret")?.asString
                            if (!secret.isNullOrBlank()) {
                                config.setConnectionSecret(secret)
                                Log.i(TAG, "Device registered successfully, cached connection secret")
                                callback(true, null)
                            } else {
                                callback(false, "No secret returned in response")
                            }
                        } catch (e: Exception) {
                            callback(false, "Failed to parse register response: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Registration failed with status code: ${response.code}")
                        callback(false, "HTTP ${response.code}: ${response.message}")
                    }
                }
            }
        })
    }

    // POST /api/v1/telemetry
    fun sendTelemetry(
        lat: Double,
        lon: Double,
        accuracy: Float,
        battery: Int,
        isCharging: Boolean,
        callback: (Boolean, String?) -> Unit
    ) {
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()
        if (secret.isNullOrBlank()) {
            callback(false, "No connection secret configured")
            return
        }

        val url = "$serverUrl/api/v1/telemetry"

        val bodyJson = JsonObject().apply {
            addProperty("uid", getDeviceUid())
            addProperty("lat", lat)
            addProperty("lon", lon)
            addProperty("accuracy_m", accuracy)
            addProperty("battery", battery)
            addProperty("is_charging", isCharging)
        }

        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .post(gson.toJson(bodyJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Telemetry POST failed: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401) {
                        Log.w(TAG, "Telemetry unauthorized (401), clearing secret")
                        config.clearConnectionSecret()
                        callback(false, "Unauthorized")
                    } else if (response.isSuccessful) {
                        val body = response.body?.string()
                        callback(true, body)
                    } else {
                        callback(false, "HTTP ${response.code}")
                    }
                }
            }
        })
    }

    // POST /api/v1/event
    fun sendEvent(event: String, payload: JsonObject, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()
        if (secret.isNullOrBlank()) {
            callback(false, "No connection secret configured")
            return
        }

        val url = "$serverUrl/api/v1/event"

        val bodyJson = JsonObject().apply {
            addProperty("uid", getDeviceUid())
            addProperty("event", event)
            add("payload", payload)
        }

        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .post(gson.toJson(bodyJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Event post failed: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401) {
                        config.clearConnectionSecret()
                        callback(false, "Unauthorized")
                    } else {
                        callback(response.isSuccessful, null)
                    }
                }
            }
        })
    }

    // GET /api/v1/commands
    fun pollCommands(callback: (Boolean, String?) -> Unit) {
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()
        if (secret.isNullOrBlank()) {
            callback(false, "No secret")
            return
        }

        val url = "$serverUrl/api/v1/commands?uid=${getDeviceUid()}"
        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .get()
            .build()

        getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401) {
                        config.clearConnectionSecret()
                        callback(false, "Unauthorized")
                    } else if (response.isSuccessful) {
                        callback(true, response.body?.string())
                    } else {
                        callback(false, "HTTP ${response.code}")
                    }
                }
            }
        })
    }

    // GET /api/v1/signaling (for HTTP fallback signaling polling)
    fun pollSignaling(callback: (Boolean, String?) -> Unit) {
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()
        if (secret.isNullOrBlank()) {
            callback(false, "No secret")
            return
        }

        val url = "$serverUrl/api/v1/signaling?uid=${getDeviceUid()}"
        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .get()
            .build()

        getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        callback(true, response.body?.string())
                    } else {
                        if (response.code == 401) {
                            Log.w(TAG, "Signaling unauthorized (401), clearing secret")
                            config.clearConnectionSecret()
                        }
                        callback(false, "HTTP ${response.code}")
                    }
                }
            }
        })
    }

    // POST /api/v1/signaling (post SDP answer + device ICE candidates only)
    fun postSignaling(messageJson: JsonObject, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()
        if (secret.isNullOrBlank()) {
            callback(false, "No secret")
            return
        }

        val url = "$serverUrl/api/v1/signaling"

        // Ensure uid is included in the payload
        messageJson.addProperty("uid", getDeviceUid())

        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .post(gson.toJson(messageJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST signaling failed: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401) {
                        Log.w(TAG, "POST signaling unauthorized (401), clearing secret")
                        config.clearConnectionSecret()
                    }
                    callback(response.isSuccessful, null)
                }
            }
        })
    }

    // 8.2 WebSocket Integration
    
    fun connectWebSocket() {
        if (isConnected) {
            Log.d(TAG, "WebSocket is already connected.")
            return
        }
        val serverUrl = config.getTrackingServerUrl()
        val secret = config.getConnectionSecret()
        if (secret.isNullOrBlank()) {
            Log.w(TAG, "Cannot connect WebSocket: connection secret is null or blank.")
            return
        }

        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/device"

        Log.i(TAG, "Connecting to WebSocket: $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = getClient().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open — sending auth within 10 seconds")
                isConnected = true
                
                // First message must be auth within 10s
                val authMsg = JsonObject().apply {
                    addProperty("type", "auth")
                    addProperty("uid", getDeviceUid())
                    addProperty("connection_secret", secret)
                }
                webSocket.send(gson.toJson(authMsg))
                
                messageListener?.onStatusChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message received: ${redactSensitive(text)}")
                if (text.contains("\"pong\"") || text.contains("\"auth_ok\"")) {
                    lastHeartbeatTime = System.currentTimeMillis()
                }
                messageListener?.onMessageReceived(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                isConnected = false
                lastHeartbeatTime = 0L
                messageListener?.onStatusChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                lastHeartbeatTime = 0L
                messageListener?.onStatusChanged(false)
            }
        })
    }

    fun disconnectWebSocket() {
        Log.i(TAG, "Disconnecting WebSocket")
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
        lastHeartbeatTime = 0L
    }

    // 8.3 sendWebSocket fallback policy
    fun sendWebSocket(message: String) {
        val socket = webSocket
        if (socket != null && isConnected) {
            socket.send(message)
            return
        }

        // WS is down, check fallback policy
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val type = json.get("type")?.asString
            Log.w(TAG, "WebSocket is offline. Processing fallback for message type: $type")
            when (type) {
                "webrtc" -> {
                    // Fallback to HTTP POST /api/v1/signaling
                    postSignaling(json)
                }
                "webrtc_ready" -> {
                    // Drop - must not POST (server rejects misuse)
                    Log.w(TAG, "Dropped webrtc_ready as WebSocket is offline")
                }
                "device_event" -> {
                    // Drop - use REST /api/v1/event instead
                    Log.w(TAG, "Dropped device_event for WS send. Posting to events REST API instead")
                    val event = json.get("event")?.asString ?: "UNKNOWN"
                    val payload = json.get("payload")?.asJsonObject ?: JsonObject()
                    sendEvent(event, payload)
                }
                else -> {
                    Log.w(TAG, "Dropped message of type: $type as WebSocket is offline")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse fallback message: ${e.message}")
        }
    }

    private fun redactSensitive(json: String): String {
        return try {
            val element = com.google.gson.JsonParser.parseString(json)
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                if (obj.has("connection_secret")) obj.addProperty("connection_secret", "***REDACTED***")
                if (obj.has("pin")) obj.addProperty("pin", "***REDACTED***")
                if (obj.has("password")) obj.addProperty("password", "***REDACTED***")
                return obj.toString()
            }
            json
        } catch (e: Exception) {
            json
        }
    }

    fun isNetworkConstrained(): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return true
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true

            // If the network does not have the un-constrained capability, it is constrained.
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)) {
                return true
            }
            
            // Check for satellite transport (API 35+, TRANSPORT_SATELLITE = 10)
            if (capabilities.hasTransport(10)) {
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check network constraints: ${e.message}")
            return false
        }
    }
}
