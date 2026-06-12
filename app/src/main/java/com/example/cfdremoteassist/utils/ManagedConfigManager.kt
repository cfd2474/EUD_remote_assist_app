package com.example.cfdremoteassist.utils

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle

class ManagedConfigManager(context: Context) {
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    fun getSettingsPassword(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getString("settings_password", "admin123")
    }

    fun getConnectionSecret(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getString("connection_secret", "3757")
    }

    fun getTrackingServerUrl(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return appRestrictions.getString("tracking_server_url", "https://example.com/track")
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