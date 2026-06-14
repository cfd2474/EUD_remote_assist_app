package com.example.cfdremoteassist.utils

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle

class ManagedConfigManager(context: Context) {
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun getSettingsPassword(): String? {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedPassword = appRestrictions.getString("settings_password")
        if (!managedPassword.isNullOrEmpty()) {
            return managedPassword
        }
        return prefs.getString("manual_settings_password", null)
    }

    fun isPasswordSet(): Boolean = getSettingsPassword() != null

    fun isPasswordManaged(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return !appRestrictions.getString("settings_password").isNullOrEmpty()
    }

    fun setManualPassword(password: String) {
        prefs.edit().putString("manual_settings_password", password).apply()
    }

    fun getTrackingServerUrl(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedUrl = appRestrictions.getString("registration_server_url")
        if (!managedUrl.isNullOrEmpty()) {
            return managedUrl.trimEnd('/')
        }
        return prefs.getString("manual_server_url", "") ?: ""
    }

    fun isServerUrlManaged(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return !appRestrictions.getString("registration_server_url").isNullOrEmpty()
    }

    fun setManualServerUrl(url: String) {
        var formattedUrl = url.trim()
        if (formattedUrl.isNotEmpty() && !formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        prefs.edit().putString("manual_server_url", formattedUrl.trimEnd('/')).apply()
    }

    fun getConnectionSecret(): String {
        // Connection secret is now strictly internal (received from server)
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

    fun getAgency(): String {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedAgency = appRestrictions.getString("agency")
        if (!managedAgency.isNullOrEmpty()) {
            return managedAgency
        }
        return prefs.getString("manual_agency", "") ?: ""
    }

    fun isAgencyManaged(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return !appRestrictions.getString("agency").isNullOrEmpty()
    }

    fun setManualAgency(agency: String) {
        prefs.edit().putString("manual_agency", agency.trim()).apply()
    }

    fun isRegistered(): Boolean = prefs.getBoolean("is_registered", false)

    fun setRegistered(registered: Boolean) {
        prefs.edit().putBoolean("is_registered", registered).apply()
    }

    fun getLastDeviceUpdate(): Long = prefs.getLong("last_device_update", 0L)

    fun setLastDeviceUpdate(timestamp: Long) {
        prefs.edit().putLong("last_device_update", timestamp).apply()
    }

    fun isBootStartEnabled(): Boolean = prefs.getBoolean("boot_start_enabled", false)

    fun setBootStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("boot_start_enabled", enabled).apply()
    }
}