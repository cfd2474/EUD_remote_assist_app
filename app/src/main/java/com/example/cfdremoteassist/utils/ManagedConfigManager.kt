package com.example.cfdremoteassist.utils

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle

class ManagedConfigManager(context: Context) {
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun getSettingsPassword(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getString("settings_password", "3757")
    }

    fun getTrackingServerUrl(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedUrl = appRestrictions.getString("tracking_server_url")
        if (!managedUrl.isNullOrEmpty()) {
            return managedUrl.trimEnd('/')
        }
        return prefs.getString("manual_server_url", "https://remote.tak-solutions.com") ?: "https://remote.tak-solutions.com"
    }

    fun setManualServerUrl(url: String) {
        var formattedUrl = url.trim()
        if (formattedUrl.isNotEmpty() && !formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        prefs.edit().putString("manual_server_url", formattedUrl.trimEnd('/')).apply()
    }

    fun getConnectionSecret(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedSecret = appRestrictions.getString("connection_secret")
        if (!managedSecret.isNullOrEmpty()) {
            return managedSecret
        }
        return prefs.getString("cached_connection_secret", "") ?: ""
    }

    fun setConnectionSecret(secret: String) {
        prefs.edit().putString("cached_connection_secret", secret).apply()
    }

    fun clearConnectionSecret() {
        prefs.edit().remove("cached_connection_secret").apply()
        setRegistered(false)
    }

    fun hasManagedConfig(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return !appRestrictions.isEmpty
    }

    fun getTrackingInterval(): Int {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getInt("tracking_interval", 15)
    }

    fun isRegistered(): Boolean = prefs.getBoolean("is_registered", false)

    fun setRegistered(registered: Boolean) {
        prefs.edit().putBoolean("is_registered", registered).apply()
    }

    fun getLastDeviceUpdate(): Long = prefs.getLong("last_device_update", 0L)

    fun setLastDeviceUpdate(timestamp: Long) {
        prefs.edit().putLong("last_device_update", timestamp).apply()
    }
}