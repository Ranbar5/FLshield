package com.example.applocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import com.example.applocker.data.AppLockPreferences

class AppLockerDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "AppLockerAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile provisioning complete")
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, AppLockerDeviceAdminReceiver::class.java)

            try {
                dpm.setProfileName(adminComponent, "FLShield Profile")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting profile name", e)
            }

            val extras = intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            ) as? PersistableBundle
            
            if (extras != null) {
                val serverUrl = extras.getString("server_url")
                Log.d(TAG, "Provisioning extras server_url: $serverUrl")
                if (!serverUrl.isNullOrBlank()) {
                    val prefs = AppLockPreferences(context)
                    prefs.serverUrl = serverUrl.trim().trimEnd('/')
                    Log.d(TAG, "Saved server_url from provisioning extras: ${prefs.serverUrl}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL error in onProfileProvisioningComplete", t)
        }
    }
}
