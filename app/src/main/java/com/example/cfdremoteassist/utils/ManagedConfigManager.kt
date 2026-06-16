package com.example.cfdremoteassist.utils

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle

class ManagedConfigManager(context: Context) {
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private fun addDefaultPortIfMissing(url: String): String {
        if (url.isEmpty()) return url

        // Check if URL already has a port specified
        // Pattern: scheme://host:port or scheme://host/path
        val urlWithoutScheme = url.replace("https://", "").replace("http://", "")
        val hostPart = urlWithoutScheme.split("/")[0]

        // If host already has a port (contains :), return as-is
        if (hostPart.contains(":")) {
            return url
        }

        // Add default port 8448
        val scheme = when {
            url.startsWith("https://") -> "https://"
            url.startsWith("http://") -> "http://"
            else -> ""
        }

        return if (scheme.isNotEmpty()) {
            scheme + hostPart + ":8448" + urlWithoutScheme.removePrefix(hostPart)
        } else {
            url
        }
    }

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
        // Priority 1: MDM Managed URL (Should always override manual settings in managed environments)
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        val managedUrl = appRestrictions.getString("tracking_server_url")
        if (!managedUrl.isNullOrEmpty()) {
            return addDefaultPortIfMissing(managedUrl.trimEnd('/'))
        }

        // Priority 2: Manual URL (Set during manual setup OR from the server's registration response)
        val manualUrl = prefs.getString("manual_server_url", "") ?: ""
        if (manualUrl.isNotEmpty()) {
            return addDefaultPortIfMissing(manualUrl.trimEnd('/'))
        }

        return ""
    }

    fun isServerUrlManaged(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        return !appRestrictions.getString("tracking_server_url").isNullOrEmpty()
    }

    fun setManualServerUrl(url: String) {
        var formattedUrl = url.trim()
        if (formattedUrl.isNotEmpty() && !formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        formattedUrl = addDefaultPortIfMissing(formattedUrl.trimEnd('/'))
        prefs.edit().putString("manual_server_url", formattedUrl).commit()
    }

    fun getConnectionSecret(): String {
        // Connection secret is now strictly internal (received from server)
        return prefs.getString("cached_connection_secret", "") ?: ""
    }

    fun setConnectionSecret(secret: String) {
        prefs.edit().putString("cached_connection_secret", secret).commit()
    }

    fun clearConnectionSecret() {
        prefs.edit().remove("cached_connection_secret").commit()
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
        prefs.edit().putBoolean("is_registered", registered).commit()
    }

    fun getLastDeviceUpdate(): Long = prefs.getLong("last_device_update", 0L)

    fun setLastDeviceUpdate(timestamp: Long) {
        prefs.edit().putLong("last_device_update", timestamp).commit()
    }

    fun isBootStartEnabled(): Boolean = prefs.getBoolean("boot_start_enabled", false)

    fun setBootStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("boot_start_enabled", enabled).apply()
    }

    fun isAutoGrantEnabled(): Boolean {
        val appRestrictions: Bundle = restrictionsManager.applicationRestrictions
        // Default to true in code if not explicitly denied by MDM
        return if (appRestrictions.containsKey("auto_grant_permissions")) {
            appRestrictions.getBoolean("auto_grant_permissions")
        } else {
            true
        }
    }
}