package net.wastu.artha.data.analysis

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class StoredSecret(private val prefs: SharedPreferences, private val alias: String, private val legacyKey: String) {
    private val encryptedKey = "${legacyKey}_encrypted"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun save(value: String) {
        if (value.isBlank()) {
            check(prefs.edit().remove(legacyKey).remove(encryptedKey).commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(encryptedKey, Base64.encodeToString(bytes, Base64.NO_WRAP)).remove(legacyKey).commit()) { "Unable to store AI credentials securely." }
    }
    fun read(): String {
        val legacy = prefs.getString(legacyKey, null)
        if (legacy != null) {
            // Preserve recoverable legacy credentials if KeyStore is temporarily unavailable.
            runCatching { save(legacy) }
            return legacy
        }
        val encoded = prefs.getString(encryptedKey, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }
}
