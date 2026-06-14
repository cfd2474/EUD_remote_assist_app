package com.example.cfdremoteassist.utils

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkManager private constructor(private val context: Context, private val configManager: ManagedConfigManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var webSocket: WebSocket? = null
    private var isSessionActive = false

    fun setSessionActive(active: Boolean) {
        isSessionActive = active
    }

    fun isSessionActive(): Boolean = isSessionActive

    fun register(deviceInfo: Map<String, String>, callback: (Boolean, String?) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        if (baseUrl.isEmpty()) {
            callback(false, "Server URL not configured")
            return
        }

        val url = "$baseUrl/api/v1/register"
        val json = gson.toJson(deviceInfo)
        Log.i("NetworkManager", "Registering device with payload: $json")
        val body = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NetworkManager", "Registration failed: ${e.message}")
                callback(false, "Connection error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if ((response.code == 200 || response.code == 201) && responseBody != null) {
                    try {
                        val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                        val secret = jsonObject.get("connection_secret")?.asString
                        val serverUrl = jsonObject.get("tracking_server_url")?.asString
                        
                        if (!secret.isNullOrEmpty()) {
                            configManager.setConnectionSecret(secret)
                            configManager.setRegistered(true)
                            
                            if (!serverUrl.isNullOrEmpty()) {
                                configManager.setManualServerUrl(serverUrl)
                            }

                            callback(true, null)
                        } else {
                            callback(false, "Server response missing connection secret")
                        }
                    } catch (e: Exception) {
                        callback(false, "Failed to parse server response")
                    }
                } else {
                    callback(false, "Server error: ${response.code}")
                }
            }
        })
    }

    fun sendEvent(event: String, payload: Map<String, Any> = emptyMap()) {
        val baseUrl = configManager.getTrackingServerUrl()
        val secret = configManager.getConnectionSecret()
        if (baseUrl.isEmpty() || secret.isEmpty()) return

        val url = "$baseUrl/api/v1/event"
        val bodyMap = mutableMapOf<String, Any>()
        bodyMap["uid"] = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        bodyMap["event"] = event
        bodyMap["payload"] = payload

        val json = gson.toJson(bodyMap)
        val body = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-Connection-Secret", secret)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NetworkManager", "Event failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    fun ping(uid: String, callback: (Boolean, String?) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        if (baseUrl.isEmpty()) {
            callback(false, "Server URL not configured")
            return
        }

        val url = "$baseUrl/api/v1/ping?uid=$uid"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, "Connection error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.code == 200 && responseBody != null) {
                    val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                    val ok = jsonObject.get("ok")?.asBoolean == true
                    if (ok) {
                        callback(true, null)
                    } else {
                        callback(false, "Device recognized as false by server")
                    }
                } else if (response.code == 404) {
                    configManager.clearConnectionSecret()
                    callback(false, "Device not recognized. Re-registration required.")
                } else {
                    callback(false, "Server error: ${response.code}")
                }
            }
        })
    }

    fun sendTelemetry(payload: Map<String, Any>, onCommandsReceived: (List<JsonObject>) -> Unit = {}) {
        val baseUrl = configManager.getTrackingServerUrl()
        val secret = configManager.getConnectionSecret()
        if (baseUrl.isEmpty() || secret.isEmpty()) return

        val url = "$baseUrl/api/v1/telemetry"
        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-Connection-Secret", secret)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NetworkManager", "Telemetry failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 401) {
                    Log.w("NetworkManager", "Telemetry auth failed. Clearing secret.")
                    configManager.clearConnectionSecret()
                } else if (response.code == 200) {
                    val bodyString = response.body?.string()
                    if (bodyString != null) {
                        try {
                            val jsonObject = gson.fromJson(bodyString, JsonObject::class.java)
                            val commandsArray = jsonObject.getAsJsonArray("commands")
                            if (commandsArray != null && commandsArray.size() > 0) {
                                val commands = mutableListOf<JsonObject>()
                                commandsArray.forEach { commands.add(it.asJsonObject) }
                                onCommandsReceived(commands)
                            }
                        } catch (e: Exception) {
                            Log.e("NetworkManager", "Error parsing telemetry commands", e)
                        }
                    }
                }
                response.close()
            }
        })
    }

    fun connectWebSocket(uid: String, secret: String, onMessageReceived: (JsonObject) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        if (baseUrl.isEmpty()) return
        
        if (webSocket != null) return

        val wsUrl = baseUrl.replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/device"

        val request = Request.Builder().url(wsUrl).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("NetworkManager", "WebSocket Opened. Sending auth...")
                val auth = JsonObject().apply {
                    addProperty("type", "auth")
                    addProperty("uid", uid)
                    addProperty("connection_secret", secret)
                }
                webSocket.send(gson.toJson(auth))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("NetworkManager", "WS Message: $text")
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)
                    onMessageReceived(json)
                } catch (e: Exception) {
                    Log.e("NetworkManager", "Error parsing WS message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("NetworkManager", "WebSocket Closing ($code): $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w("NetworkManager", "WebSocket Closed ($code): $reason")
                this@NetworkManager.webSocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("NetworkManager", "WebSocket Failure: ${t.message}. Response: ${response?.code}", t)
                this@NetworkManager.webSocket = null
            }
        })
    }

    fun sendWebSocketMessage(json: String) {
        val currentWs = webSocket
        if (currentWs != null) {
            Log.d("NetworkManager", "Sending WS Message (size: ${json.length})")
            val sent = currentWs.send(json)
            if (sent) return
        }
        
        Log.w("NetworkManager", "WebSocket send failed (or null), using HTTP fallback signaling")
        postSignaling(json)
    }

    fun isWebSocketConnected(): Boolean = webSocket != null

    fun sendKeepAlive() {
        val currentWs = webSocket
        if (currentWs != null) {
            val ping = JsonObject().apply {
                addProperty("type", "ping")
            }
            currentWs.send(gson.toJson(ping))
        }
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "App Service Stopping")
        webSocket = null
    }

    fun pollCommands(onCommandsReceived: (List<JsonObject>) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        val secret = configManager.getConnectionSecret()
        if (baseUrl.isEmpty() || secret.isEmpty()) return

        val url = "$baseUrl/api/v1/commands"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Connection-Secret", secret)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 200) {
                    val body = response.body?.string() ?: return
                    try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val commandsArray = json.getAsJsonArray("commands")
                        if (commandsArray != null && commandsArray.size() > 0) {
                            val commands = mutableListOf<JsonObject>()
                            commandsArray.forEach { commands.add(it.asJsonObject) }
                            onCommandsReceived(commands)
                        }
                    } catch (e: Exception) {}
                } else if (response.code == 401) {
                    configManager.clearConnectionSecret()
                }
                response.close()
            }
        })
    }

    fun pollSignaling(onMessagesReceived: (List<JsonObject>) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        val secret = configManager.getConnectionSecret()
        if (baseUrl.isEmpty() || secret.isEmpty()) return

        val url = "$baseUrl/api/v1/signaling"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Connection-Secret", secret)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 200) {
                    val body = response.body?.string() ?: return
                    try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val messagesArray = json.getAsJsonArray("messages")
                        if (messagesArray != null && messagesArray.size() > 0) {
                            val messages = mutableListOf<JsonObject>()
                            messagesArray.forEach { messages.add(it.asJsonObject) }
                            onMessagesReceived(messages)
                        }
                    } catch (e: Exception) {}
                }
                response.close()
            }
        })
    }

    fun postSignaling(json: String) {
        val baseUrl = configManager.getTrackingServerUrl()
        val secret = configManager.getConnectionSecret()
        if (baseUrl.isEmpty() || secret.isEmpty()) return

        val url = "$baseUrl/api/v1/signaling"
        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-Connection-Secret", secret)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    companion object {
        @Volatile
        private var INSTANCE: NetworkManager? = null

        fun getInstance(context: Context, configManager: ManagedConfigManager): NetworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext, configManager).also { INSTANCE = it }
            }
        }
    }
}