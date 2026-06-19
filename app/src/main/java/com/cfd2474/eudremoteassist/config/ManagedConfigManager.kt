package com.cfd2474.eudremoteassist.config

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle

class ManagedConfigManager(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("eud_remote_assist_prefs", Context.MODE_PRIVATE)
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    fun getTrackingServerUrl(): String {
        val mdmUrl = getMdmString("tracking_server_url")
        val url = if (!mdmUrl.isNullOrBlank()) {
            mdmUrl
        } else {
            sharedPrefs.getString("tracking_server_url", "") ?: ""
        }
        return url.trimEnd('/')
    }

    fun setTrackingServerUrl(url: String) {
        sharedPrefs.edit().putString("tracking_server_url", url).apply()
    }

    fun formatServerUrl(input: String): String {
        val url = input.trim()
        if (url.isEmpty()) return ""
        
        val hasScheme = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        val urlToParse = if (hasScheme) url else "https://$url"
        
        val schemeDelimiter = urlToParse.indexOf("://")
        val hostPart = if (schemeDelimiter != -1) urlToParse.substring(schemeDelimiter + 3) else urlToParse
        val hasPort = hostPart.contains(":")
        
        return if (hasPort) {
            if (hasScheme) url else "https://$url"
        } else {
            if (hasScheme) "$url:8448" else "https://$url:8448"
        }
    }

    fun getConnectionSecret(): String? {
        val mdmSecret = getMdmString("connection_secret")
        if (!mdmSecret.isNullOrBlank()) {
            return mdmSecret
        }
        return sharedPrefs.getString("cached_connection_secret", null)
    }

    fun setConnectionSecret(secret: String) {
        sharedPrefs.edit().putString("cached_connection_secret", secret).apply()
    }

    fun clearConnectionSecret() {
        sharedPrefs.edit().remove("cached_connection_secret").apply()
    }

    fun getTrackingInterval(): Int {
        val mdmInterval = getMdmInteger("tracking_interval")
        if (mdmInterval != null && mdmInterval > 0) {
            return mdmInterval
        }
        return sharedPrefs.getInt("tracking_interval", 15)
    }

    fun setTrackingInterval(minutes: Int) {
        sharedPrefs.edit().putInt("tracking_interval", minutes).apply()
    }

    fun getSettingsPassword(): String? {
        val mdmPassword = getMdmString("settings_password")
        if (!mdmPassword.isNullOrBlank()) {
            return mdmPassword
        }
        return sharedPrefs.getString("settings_password", null)
    }

    fun setSettingsPassword(password: String) {
        sharedPrefs.edit().putString("settings_password", password).apply()
    }

    fun getAgency(): String {
        val mdmAgency = getMdmString("agency")
        if (!mdmAgency.isNullOrBlank()) {
            return mdmAgency
        }
        return sharedPrefs.getString("agency", "") ?: ""
    }

    fun setAgency(agency: String) {
        sharedPrefs.edit().putString("agency", agency).apply()
    }

    fun isMdmManaged(): Boolean {
        val bundle = restrictionsManager.applicationRestrictions
        return bundle != null && (
                bundle.containsKey("tracking_server_url") ||
                bundle.containsKey("agency") ||
                bundle.containsKey("settings_password") ||
                bundle.containsKey("tracking_interval")
        )
    }

    fun isServerMdmManaged(): Boolean {
        val bundle = restrictionsManager.applicationRestrictions
        return bundle != null && bundle.containsKey("tracking_server_url")
    }

    fun isAgencyMdmManaged(): Boolean {
        val bundle = restrictionsManager.applicationRestrictions
        return bundle != null && bundle.containsKey("agency")
    }

    fun isPasswordMdmManaged(): Boolean {
        val bundle = restrictionsManager.applicationRestrictions
        return bundle != null && bundle.containsKey("settings_password")
    }

    fun isBootStartEnabled(): Boolean {
        return sharedPrefs.getBoolean("boot_start_enabled", true)
    }

    fun setBootStartEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("boot_start_enabled", enabled).apply()
    }

    private fun getMdmString(key: String): String? {
        val bundle: Bundle? = restrictionsManager.applicationRestrictions
        return bundle?.getString(key)
    }

    private fun getMdmInteger(key: String): Int? {
        val bundle: Bundle? = restrictionsManager.applicationRestrictions
        return if (bundle != null && bundle.containsKey(key)) {
            bundle.getInt(key)
        } else {
            null
        }
    }
}
