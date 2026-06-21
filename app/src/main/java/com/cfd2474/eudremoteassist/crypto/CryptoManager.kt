package com.cfd2474.eudremoteassist.crypto

import android.content.Context
import android.util.Base64
import com.cfd2474.eudremoteassist.config.ManagedConfigManager
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

object CryptoManager {
    private var cachedPublicKey: String? = null
    private var cachedPrivateKey: PrivateKey? = null

    fun getOrGeneratePublicKey(context: Context): String {
        cachedPublicKey?.let { return it }

        val prefs = ManagedConfigManager.getPrefs(context)
        val storedPublic = prefs.getString("e2ee_public_key", null)
        val storedPrivate = prefs.getString("e2ee_private_key", null)

        if (!storedPublic.isNullOrEmpty() && !storedPrivate.isNullOrEmpty()) {
            cachedPublicKey = storedPublic
            val privateKeyBytes = Base64.decode(storedPrivate, Base64.DEFAULT)
            cachedPrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            return storedPublic
        }

        // Generate software KeyPair
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privateKeyBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

        prefs.edit().putString("e2ee_public_key", publicKeyBase64).commit()
        prefs.edit().putString("e2ee_private_key", privateKeyBase64).commit()

        cachedPublicKey = publicKeyBase64
        cachedPrivateKey = keyPair.private
        return publicKeyBase64
    }

    fun decryptPayload(context: Context, encryptedBase64: String): String {
        getOrGeneratePublicKey(context) // Ensure keys are loaded
        val privateKey = cachedPrivateKey ?: throw IllegalStateException("Private key not found")

        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        val spec = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.DECRYPT_MODE, privateKey, spec)

        val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}

