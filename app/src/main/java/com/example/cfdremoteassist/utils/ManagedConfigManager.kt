package com.example.cfdremoteassist.utils

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle

class ManagedConfigManager(context: Context) {
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    fun getSettingsPassword(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getString("settings_password", "3757")
    }

    fun getConnectionSecret(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getString("connection_secret", "3757")
    }

    fun getTrackingServerUrl(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedUrl = appRestrictions.getString("tracking_server_url")
        if (!managedUrl.isNullOrEmpty()) {
            return managedUrl
        }
        val address = prefs.getString("manual_server_address", "") ?: ""
        val port = prefs.getString("manual_server_port", "") ?: ""
        if (address.isEmpty()) return ""
        return "https://$address${if (port.isNotEmpty()) ":$port" else ""}/track"
    }

    fun getManualAddress(): String = prefs.getString("manual_server_address", "") ?: ""
    fun getManualPort(): String = prefs.getString("manual_server_port", "") ?: ""

    fun setManualServerConfig(address: String, port: String) {
        prefs.edit()
            .putString("manual_server_address", address)
            .putString("manual_server_port", port)
            .apply()
    }

    fun hasManagedConfig(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return !appRestrictions.isEmpty
    }

    fun getTrackingInterval(): Int {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getInt("tracking_interval", 15)
    }

    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun isRegistered(): Boolean = prefs.getBoolean("is_registered", false)

    fun setRegistered(registered: Boolean) {
        prefs.edit().putBoolean("is_registered", registered).apply()
    }

    fun getLastDeviceUpdate(): Long = prefs.getLong("last_device_update", 0L)

    fun setLastDeviceUpdate(timestamp: Long) {
        prefs.edit().putLong("last_device_update", timestamp).apply()
    }
}