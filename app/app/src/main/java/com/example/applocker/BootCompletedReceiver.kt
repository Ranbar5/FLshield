package com.example.applocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.applocker.data.AppLockPreferences
import com.example.applocker.service.AppLockService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val prefs = AppLockPreferences(context)
        if (!prefs.isProvisioned) return
        Log.d(TAG, "Boot completed — starting FLShield service")
        context.startService(Intent(context, AppLockService::class.java).apply {
            action = AppLockService.ACTION_START
        })
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
