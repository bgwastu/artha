package net.wastu.artha.data.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredSecretTest {
    @Test fun credentialsMigrateToKeystoreAndSurviveReopening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "artha-secret-test"
        val alias = "artha-secret-test-only"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("test_key", "fake-test-credential").commit()
            val secret = StoredSecret(prefs, alias, "test_key")
            assertEquals("fake-test-credential", secret.read())
            assertFalse(prefs.contains("test_key"))
            assertFalse(prefs.getString("test_key_encrypted", "")!!.contains("fake-test-credential"))
            assertEquals("fake-test-credential", StoredSecret(prefs, alias, "test_key").read())
            secret.save("updated-test-credential")
            assertEquals("updated-test-credential", StoredSecret(prefs, alias, "test_key").read())
            secret.save("")
            assertEquals("", secret.read())
        } finally {
            prefs.edit().clear().commit()
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
