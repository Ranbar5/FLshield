package com.example.applocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.applocker.data.AppLockPreferences

/**
 * Monitors ALL foreground apps via Accessibility.
 * - If a Settings app opens → eject to HOME immediately
 * - If ANY non-whitelisted app opens → eject to HOME immediately
 * This is faster than UsageStats polling (event-driven, ~50ms).
 */
class SettingsMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "SettingsMonitor"
        @Volatile
        private var allowSettingsNavigationUntil = 0L

        @Volatile
        var isAuroraActive = false

        @Volatile
        private var isKioskPausedUntil = 0L

        fun allowSettingsNavigation(durationMs: Long = 30_000L) {
            allowSettingsNavigationUntil = System.currentTimeMillis() + durationMs
        }

        fun isSettingsNavigationAllowed(): Boolean {
            return System.currentTimeMillis() < allowSettingsNavigationUntil
        }

        fun pauseKiosk(context: android.content.Context, durationMs: Long) {
            isKioskPausedUntil = System.currentTimeMillis() + durationMs
            
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                isKioskPausedUntil = 0
                try {
                    val intent = android.content.Intent(context, com.example.applocker.KioskHomeActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                    Log.d(TAG, "Timer expired: Kiosk mode restarted automatically")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart kiosk automatically", e)
                }
            }, durationMs)
        }

        fun isKioskPaused(): Boolean {
            return System.currentTimeMillis() < isKioskPausedUntil
        }
    }

    private lateinit var prefs: AppLockPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        prefs = AppLockPreferences(this)
        Log.d(TAG, "Accessibility service connected — monitoring ALL packages")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (km.isKeyguardLocked || !pm.isInteractive) return

        if (isKioskPaused()) return
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // Ignore our own app and SystemUI
        if (pkg == "com.example.applocker" || pkg == "com.android.systemui") return

        // Update isAuroraActive state if we leave the workflow
        val isWorkflowApp = pkg == "com.aurora.store" || 
                            pkg == "com.android.settings" || 
                            pkg == "com.android.packageinstaller" || 
                            pkg == "com.google.android.packageinstaller" ||
                            AppLockPreferences.SETTINGS_PACKAGES.contains(pkg)

        if (!isWorkflowApp) {
            isAuroraActive = false
        }

        // Check if it's a known Settings package → immediate eject unless allowed
        if (AppLockPreferences.SETTINGS_PACKAGES.contains(pkg)) {
            if (isSettingsNavigationAllowed() || isAuroraActive) {
                Log.d(TAG, "Settings navigation allowed [$pkg] (auroraActive=$isAuroraActive)")
                return
            }
            Log.d(TAG, "Settings detected [$pkg] — ejecting to HOME")
            ejectToHome()
            return
        }

        // Check if it's any blocked package → immediate eject
        if (AppLockPreferences.ALWAYS_BLOCKED.contains(pkg)) {
            val isInstaller = pkg == "com.android.packageinstaller" || pkg == "com.google.android.packageinstaller"
            if (isInstaller && (isSettingsNavigationAllowed() || isAuroraActive)) {
                Log.d(TAG, "Package installer allowed [$pkg] (auroraActive=$isAuroraActive)")
                return
            }
            Log.d(TAG, "Blocked app detected [$pkg] — ejecting to HOME")
            ejectToHome()
            return
        }

        // Check whitelist (only if provisioned)
        if (prefs.isProvisioned && !prefs.isPackageAllowed(pkg) && !GrantManager.isGranted(pkg)) {
            Log.d(TAG, "Non-whitelisted app detected [$pkg] — scheduling eject to HOME")
            // Delay slightly to avoid flicker when an app closes and the launcher briefly appears
            val detected = pkg
            mainHandler.postDelayed({
                try {
                    // Re-evaluate package via the last observed event — if unchanged, eject
                    if (!prefs.isPackageAllowed(detected) && !GrantManager.isGranted(detected)) {
                        Log.d(TAG, "Non-whitelisted app still present [$detected] — ejecting")
                        ejectToHome()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during delayed eject check", e)
                }
            }, 250)
            return
        }
    }

    private fun ejectToHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "Ejected to home screen")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
