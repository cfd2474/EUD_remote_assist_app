package com.cfd2474.eudremoteassist.config

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ManagedConfigManager(private val context: Context) {

    companion object {
        @Volatile
        private var sharedPrefsInstance: android.content.SharedPreferences? = null

        fun getPrefs(context: Context): android.content.SharedPreferences {
            return sharedPrefsInstance ?: synchronized(this) {
                sharedPrefsInstance ?: createPrefs(context).also { sharedPrefsInstance = it }
            }
        }

        private fun createPrefs(context: Context): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "eud_remote_assist_prefs_enc",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            val oldPrefs = context.getSharedPreferences("eud_remote_assist_prefs", Context.MODE_PRIVATE)
            if (oldPrefs.all.isNotEmpty()) {
                val editor = encryptedPrefs.edit()
                oldPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Long -> editor.putLong(key, value)
                    }
                }
                editor.commit()
                oldPrefs.edit().clear().commit()
            }
            return encryptedPrefs
        }
    }

    private val sharedPrefs: android.content.SharedPreferences
        get() = getPrefs(context)
    
    private val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    fun isMdmOverridden(): Boolean {
        return sharedPrefs.getBoolean("mdm_overridden", false)
    }

    fun setMdmOverride(override: Boolean) {
        sharedPrefs.edit().putBoolean("mdm_overridden", override).commit()
    }

    fun getTrackingServerUrl(): String {
        if (isMdmOverridden()) {
            return sharedPrefs.getString("tracking_server_url", "")?.trimEnd('/') ?: ""
        }
        val mdmUrl = getMdmString("tracking_server_url")
        val url = if (!mdmUrl.isNullOrBlank()) {
            mdmUrl
        } else {
            sharedPrefs.getString("tracking_server_url", "") ?: ""
        }
        return url.trimEnd('/')
    }

    fun setTrackingServerUrl(url: String) {
        sharedPrefs.edit().putString("tracking_server_url", url).commit()
    }

    fun getTlsPinHash(): String? {
        if (isMdmOverridden()) {
            return sharedPrefs.getString("tls_pin_hash", null)
        }
        val mdmPin = getMdmString("tls_pin_hash")
        if (!mdmPin.isNullOrBlank()) {
            return mdmPin
        }
        return sharedPrefs.getString("tls_pin_hash", null)
    }

    fun setTlsPinHash(hash: String) {
        sharedPrefs.edit().putString("tls_pin_hash", hash).commit()
    }

    fun getEnrollmentToken(): String? {
        if (isMdmOverridden()) {
            return sharedPrefs.getString("enrollment_token", null)
        }
        val mdmToken = getMdmString("enrollment_token")
        if (!mdmToken.isNullOrBlank()) {
            return mdmToken
        }
        return sharedPrefs.getString("enrollment_token", null)
    }

    fun setEnrollmentToken(token: String) {
        sharedPrefs.edit().putString("enrollment_token", token).commit()
    }

    fun clearEnrollmentToken() {
        sharedPrefs.edit().remove("enrollment_token").commit()
    }

    fun getCachedDeviceUid(): String? {
        return sharedPrefs.getString("cached_device_uid", null)
    }

    fun setCachedDeviceUid(uid: String) {
        sharedPrefs.edit().putString("cached_device_uid", uid).commit()
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
        if (isMdmOverridden()) {
            return sharedPrefs.getString("cached_connection_secret", null)
        }
        val mdmSecret = getMdmString("connection_secret")
        if (!mdmSecret.isNullOrBlank()) {
            return mdmSecret
        }
        return sharedPrefs.getString("cached_connection_secret", null)
    }

    fun setConnectionSecret(secret: String) {
        sharedPrefs.edit().putString("cached_connection_secret", secret).commit()
    }

    fun clearConnectionSecret() {
        sharedPrefs.edit().remove("cached_connection_secret").commit()
    }

    fun getTrackingInterval(): Int {
        val mdmInterval = getMdmInteger("tracking_interval")
        if (mdmInterval != null && mdmInterval > 0) {
            return mdmInterval
        }
        return sharedPrefs.getInt("tracking_interval", 15)
    }

    fun setTrackingInterval(minutes: Int) {
        sharedPrefs.edit().putInt("tracking_interval", minutes).commit()
    }

    fun getAgency(): String {
        if (isMdmOverridden()) {
            return sharedPrefs.getString("agency", "") ?: ""
        }
        val mdmAgency = getMdmString("agency")
        if (!mdmAgency.isNullOrBlank()) {
            return mdmAgency
        }
        return sharedPrefs.getString("agency", "") ?: ""
    }

    fun setAgency(agency: String) {
        sharedPrefs.edit().putString("agency", agency).commit()
    }

    fun isMdmManaged(): Boolean {
        val bundle = restrictionsManager.applicationRestrictions
        return bundle != null && (
                bundle.containsKey("tracking_server_url") ||
                bundle.containsKey("agency") ||
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
