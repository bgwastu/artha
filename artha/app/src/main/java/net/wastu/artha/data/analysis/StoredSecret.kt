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

/** commit() can fail after updating its in-process cache; restore the affected keys too. */
@android.annotation.SuppressLint("ApplySharedPref", "UseKtx") // commit() is deliberate: the caller needs to know whether the write landed.
internal fun checkedPreferenceEdit(prefs: SharedPreferences, keys: List<String>, message: String,
    changes: SharedPreferences.Editor.() -> Unit) {
    val before = keys.distinct().associateWith { prefs.getString(it, null) }
    fun restore() {
        val editor = prefs.edit()
        before.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.commit()
    }
    val saved = try { prefs.edit().apply(changes).commit() } catch (error: Exception) {
        runCatching { restore() }
        throw error
    }
    if (!saved) {
        runCatching { restore() }
        error(message)
    }
}

internal class StoredSecret(private val prefs: SharedPreferences, private val alias: String, private val legacyKey: String) {
    private val encryptedKey = "${legacyKey}_encrypted"
    val storageKeys: List<String> get() = listOf(legacyKey, encryptedKey)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun save(value: String) {
        checkedPreferenceEdit(prefs, storageKeys, "Unable to store credentials securely.") { stage(value, this) }
    }
    /** Allows secret and configuration metadata to be committed as one preference edit. */
    fun stage(value: String, editor: SharedPreferences.Editor) {
        if (value.isBlank()) {
            editor.remove(legacyKey).remove(encryptedKey)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        editor.putString(encryptedKey, Base64.encodeToString(bytes, Base64.NO_WRAP)).remove(legacyKey)
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
