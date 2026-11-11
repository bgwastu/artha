package net.wastu.artha.data.providers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a short secret before it is written to preferences. */
interface PinCipher {
    fun encrypt(plain: String): String
    /** The original text, or null when [token] cannot be opened (for example after the key was lost). */
    fun decrypt(token: String): String?
}

/**
 * AES-GCM with a key that lives in the Android Keystore and never leaves it, so the stored token is
 * useless to anyone who only has the preferences file, a backup, or a copy of the app's data.
 */
class KeystorePinCipher(private val alias: String = "artha_jago_pin") : PinCipher {
    override fun encrypt(plain: String): String = try {
        seal(plain)
    } catch (_: Exception) {
        // The Keystore can keep an entry it can no longer use ("Invalid key blob"). Nothing sealed with it can be
        // opened anyway, so replace it with a fresh key and seal again.
        discardKey()
        seal(plain)
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun discardKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    override fun decrypt(token: String): String? = runCatching {
        val bytes = Base64.decode(token, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
