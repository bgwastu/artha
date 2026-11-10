package net.wastu.artha.data.providers

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JagoPinStorageTest {
    /** Reversible stand-in for the Keystore: the token never contains the PIN as written. */
    private class FakeCipher(var usable: Boolean = true) : PinCipher {
        override fun encrypt(plain: String) = "sealed:" + plain.reversed()
        override fun decrypt(token: String) = if (usable && token.startsWith("sealed:")) token.removePrefix("sealed:").reversed() else null
    }

    private class Store(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap()
        private val preferences = proxy<SharedPreferences> { name, args -> when (name) {
            "getString", "getLong", "getBoolean" -> values[args[0]] ?: args[1]
            "contains" -> (args[0] as String) in values
            "getAll" -> values
            "edit" -> editor()
            else -> error("Unexpected preference call: $name")
        } }
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { name, args -> when (name) {
                "putString", "putLong", "putBoolean" -> { changes[args[0] as String] = args[1]; editor }
                "remove" -> { changes[args[0] as String] = null; editor }
                "apply" -> { changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; null }
                else -> error("Unexpected editor call: $name")
            } }
            return editor
        }
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args.orEmpty())
            } as T
    }

    @Test fun aSavedPinIsStoredSealedAndReadsBack() {
        val store = Store()
        val preferences = ProviderPreferences(store.context, FakeCipher())
        assertFalse(preferences.isJagoPinSet())
        preferences.setJagoPin(" 123456 ")
        assertEquals("123456", preferences.getJagoPin())
        assertTrue(preferences.isJagoPinSet())
        assertTrue(preferences.jagoPinSet.value)
        assertFalse("no plaintext key", "JAGO_pin" in store.values)
        assertFalse("the PIN is not in any stored value", store.values.values.any { "123456" == it || "123456" in it.toString() })
    }

    @Test fun aPlaintextPinFromAnOlderVersionIsSealedAtStartupAndRemoved() {
        val store = Store(mapOf("JAGO_pin" to "654321", "JAGO_pin_check" to "VERIFIED|ok"))
        val preferences = ProviderPreferences(store.context, FakeCipher())
        assertFalse("JAGO_pin" in store.values)
        assertTrue("JAGO_pin_enc" in store.values)
        assertEquals("654321", preferences.getJagoPin())
        assertEquals("the earlier verification still applies", JagoPinStatus.VERIFIED, preferences.jagoPinCheck.value.status)
    }

    @Test fun anUnreadableSealedPinCountsAsNoPinAndIsDropped() {
        val cipher = FakeCipher()
        val store = Store()
        ProviderPreferences(store.context, cipher).setJagoPin("111222")
        cipher.usable = false
        val reopened = ProviderPreferences(store.context, cipher)
        assertNull(reopened.getJagoPin())
        assertFalse("JAGO_pin_enc" in store.values)
        assertFalse(reopened.isJagoPinSet())
    }

    @Test fun clearingOrChangingThePinResetsItsCheck() {
        val store = Store()
        val preferences = ProviderPreferences(store.context, FakeCipher())
        preferences.setJagoPin("111222")
        preferences.recordJagoPinCheck(JagoPinStatus.WRONG, "Bank Jago rejected the PIN")
        preferences.setJagoPin("111222")
        assertEquals("same PIN keeps its verdict", JagoPinStatus.WRONG, preferences.jagoPinCheck.value.status)
        preferences.setJagoPin("333444")
        assertEquals(JagoPinStatus.UNCHECKED, preferences.jagoPinCheck.value.status)
        preferences.setJagoPin(null)
        assertNull(preferences.getJagoPin())
        assertFalse(preferences.jagoPinSet.value)
        assertTrue(store.values.keys.none { it.startsWith("JAGO_pin") })
    }
}
