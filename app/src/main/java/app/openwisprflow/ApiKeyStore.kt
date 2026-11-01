package app.openwisprflow

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's AI Gateway API key, encrypted with an AES key held in the Android Keystore
 * (HLD section 5, API key). The Keystore key never leaves the device, so a copied preferences
 * file is useless elsewhere. The API key is never logged.
 */
object ApiKeyStore {

    private const val PREFS = "secrets"
    private const val PREF_KEY = "gateway_api_key"
    private const val KEY_ALIAS = "openwisprflow_gateway_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun get(context: Context): String? {
        val stored = prefs(context).getString(PREF_KEY, null) ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // The Keystore key was lost (for example after a restore); the user enters the key again.
            null
        }
    }

    fun set(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(PREF_KEY, Base64.getEncoder().encodeToString(encrypted)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_KEY).apply()
    }

    fun has(context: Context) = prefs(context).contains(PREF_KEY)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
