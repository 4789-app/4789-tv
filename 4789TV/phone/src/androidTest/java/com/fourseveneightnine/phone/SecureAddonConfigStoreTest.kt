package com.fourseveneightnine.phone

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureAddonConfigStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = SecureAddonConfigStore(
        context,
        preferenceName = "secure-addon-config-instrumentation",
        keyAlias = "com.fourseveneightnine.phone.addon-config.instrumentation",
    )

    @After
    fun cleanup() = store.destroyForTesting()

    @Test
    fun encryptedRoundTripNeverStoresPlaintextAndClearIsDurable() {
        val manifest = "https://addon.example/private/manifest.json"
        assertTrue(store.save(manifest))
        assertEquals(SecureAddonConfigState.Available(manifest), store.load())

        val raw = context.getSharedPreferences("secure-addon-config-instrumentation", 0)
            .getString("manifest-ciphertext", null)
        assertFalse(requireNotNull(raw).contains("addon.example"))

        assertTrue(store.clear())
        assertEquals(SecureAddonConfigState.Missing, store.load())
    }
}
