package com.example.applocker.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLockPreferencesTest {

    private lateinit var prefs: AppLockPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppLockPreferences(context)
        prefs.isProvisioned = true
        prefs.setAllowedApps(emptySet())
    }

    @Test
    fun strictMode_emptyWhitelist_blocksThirdPartyApp() {
        assertFalse(prefs.isPackageAllowed("com.whatsapp"))
    }

    @Test
    fun strictMode_alwaysAllowed_permitsDialer() {
        assertTrue(prefs.isPackageAllowed("com.google.android.dialer"))
    }

    @Test
    fun applyServerConfig_updatesWhitelist() {
        val json = JSONObject(
            """{"allowedApps":["com.android.chrome","com.whatsapp"],"localPassword":"9999","blockGps":false}"""
        )
        prefs.applyServerConfig(json)
        assertEquals(setOf("com.android.chrome", "com.whatsapp"), prefs.getAllowedApps())
        assertEquals("9999", prefs.localPassword)
        assertFalse(prefs.blockGps)
        assertTrue(prefs.isPackageAllowed("com.whatsapp"))
    }

    @Test
    fun applyServerConfig_keepsDeviceOverrideWhenServerPasswordChanges() {
        prefs.localPassword = "owner123"
        val json = JSONObject(
            """{"localPassword":"9999","blockGps":false}"""
        )
        prefs.applyServerConfig(json)
        assertEquals("owner123", prefs.localPassword)
        assertTrue(prefs.hasDevicePasswordOverride)
    }

    @Test
    fun clearDevicePasswordOverride_restoresServerPassword() {
        prefs.serverPassword = "9999"
        prefs.localPassword = "owner123"
        prefs.clearDevicePasswordOverride()
        assertEquals("9999", prefs.localPassword)
        assertFalse(prefs.hasDevicePasswordOverride)
    }
}
