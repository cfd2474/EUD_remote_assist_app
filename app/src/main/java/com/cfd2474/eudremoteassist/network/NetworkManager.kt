package com.cfd2474.eudremoteassist.network

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

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

    fun getAndroidId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_uid"
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
            addProperty("uid", getAndroidId())
            addProperty("device_name", deviceName)
            addProperty("model", model)
            addProperty("app_version", appVersion)
            addProperty("phone_number", getPhoneNumber())
            addProperty("agency", agency)
        }

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(bodyJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
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
            addProperty("uid", getAndroidId())
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

        client.newCall(request).enqueue(object : Callback {
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
            addProperty("uid", getAndroidId())
            addProperty("event", event)
            add("payload", payload)
        }

        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .post(gson.toJson(bodyJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
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

        val url = "$serverUrl/api/v1/commands"
        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
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

        val url = "$serverUrl/api/v1/signaling?uid=${getAndroidId()}"
        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        callback(true, response.body?.string())
                    } else {
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
        messageJson.addProperty("uid", getAndroidId())

        val request = Request.Builder()
            .url(url)
            .header("X-Connection-Secret", secret)
            .post(gson.toJson(messageJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST signaling failed: ${e.message}")
                callback(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
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

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open — sending auth within 10 seconds")
                isConnected = true
                
                // First message must be auth within 10s
                val authMsg = JsonObject().apply {
                    addProperty("type", "auth")
                    addProperty("uid", getAndroidId())
                    addProperty("connection_secret", secret)
                }
                webSocket.send(gson.toJson(authMsg))
                
                messageListener?.onStatusChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message received: $text")
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
}
