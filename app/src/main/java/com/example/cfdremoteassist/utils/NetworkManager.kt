package com.example.cfdremoteassist.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkManager(private val context: Context, private val configManager: ManagedConfigManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var webSocket: WebSocket? = null

    fun register(deviceInfo: Map<String, String>, callback: (Boolean, String?) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        if (baseUrl.isEmpty()) {
            callback(false, "Server URL not configured")
            return
        }

        val url = "$baseUrl/api/v1/register"
        val json = gson.toJson(deviceInfo)
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
                        if (!secret.isNullOrEmpty()) {
                            configManager.setConnectionSecret(secret)
                            configManager.setRegistered(true)
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

    fun sendTelemetry(payload: Map<String, Any>) {
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
                }
                response.close()
            }
        })
    }

    fun connectWebSocket(uid: String, secret: String, onCommand: (String) -> Unit) {
        val baseUrl = configManager.getTrackingServerUrl()
        if (baseUrl.isEmpty()) return
        
        // If already connected, don't re-create unless necessary
        // For now, let's keep it simple and rely on the service to manage lifecycle
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
                    when (json.get("type")?.asString) {
                        "auth_ok" -> Log.i("NetworkManager", "WS Auth Successful")
                        "webrtc" -> onCommand("WEBRTC_SIGNAL:$text")
                        "command" -> {
                            val cmd = json.get("command")?.asString
                            val incomingSecret = json.get("connection_secret")?.asString
                            if (cmd != null && incomingSecret == secret) {
                                onCommand(cmd)
                            } else {
                                Log.w("NetworkManager", "WS Command rejected: Invalid secret or missing cmd")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NetworkManager", "Error parsing WS message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.d("NetworkManager", "WebSocket Closing: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("NetworkManager", "WebSocket Failure: ${t.message}")
            }
        })
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "App Service Stopping")
        webSocket = null
    }

    fun sendWebSocketMessage(json: String) {
        webSocket?.send(json)
    }

    fun pollCommands(callback: (List<String>) -> Unit) {
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
                        val commands = json.getAsJsonArray("commands")?.map { it.asString } ?: emptyList()
                        callback(commands)
                    } catch (e: Exception) {}
                }
                response.close()
            }
        })
    }
}