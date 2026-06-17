package com.example.applocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import com.example.applocker.data.AppLockPreferences

class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ProvisioningMode", "onCreate called, setting fully managed device mode")
        
        // Extract and save the server URL from the provisioning extras
        try {
            val extras = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                    PersistableBundle::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
                ) as? PersistableBundle
            }
            if (extras != null) {
                val serverUrl = extras.getString("server_url")
                if (!serverUrl.isNullOrBlank()) {
                    val prefs = AppLockPreferences(this)
                    prefs.serverUrl = serverUrl.trim().trimEnd('/')
                    Log.d("ProvisioningMode", "Saved serverUrl: ${prefs.serverUrl}")
                }
            }
        } catch (e: Exception) {
            Log.e("ProvisioningMode", "Error extracting extras", e)
        }

        val resultIntent = Intent()
        intent?.let { resultIntent.putExtras(it) }
        resultIntent.putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
