package com.example.applocker.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Resolves which packages appear on the kiosk home grid and must be launchable.
 */
object KioskAppResolver {

    private const val TAG = "KioskAppResolver"

    fun packagesForKiosk(context: Context, prefs: AppLockPreferences): Set<String> {
        val pm = context.packageManager
        val packages = linkedSetOf<String>()

        packages.addAll(AppLockPreferences.ALWAYS_VISIBLE)
        packages.addAll(resolveDynamicEssentials(pm))

        if (!prefs.isProvisioned) {
            return packages.filterLaunchable(pm, context.packageName)
        }

        val allowed = prefs.getAllowedApps()
        if (allowed.isNotEmpty()) {
            packages.addAll(allowed)
        }
        // Strict mode: empty whitelist → only essentials above (no "all user apps")

        return packages.filterLaunchable(pm, context.packageName)
    }

    /** Packages with a launcher activity (needs QUERY_ALL_PACKAGES on Android 11+). */
    fun discoverLaunchablePackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return activities.map { it.activityInfo.packageName }.toSet()
    }

    private fun resolveDynamicEssentials(pm: PackageManager): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { out.add(it) }
        } catch (_: Exception) {
        }
        try {
            pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { out.add(it) }
        } catch (_: Exception) {
        }
        try {
            pm.resolveActivity(
                Intent(Intent.ACTION_VIEW).apply { type = "vnd.android-dir/mms-sms" },
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName?.let { out.add(it) }
        } catch (_: Exception) {
        }
        return out
    }

    private fun Set<String>.filterLaunchable(pm: PackageManager, selfPackage: String): Set<String> {
        val result = linkedSetOf<String>()
        for (pkg in this) {
            if (pkg == selfPackage) continue
            if (!isLaunchable(pm, pkg)) {
                Log.d(TAG, "Skip (no launcher): $pkg")
                continue
            }
            try {
                pm.getApplicationInfo(pkg, 0)
                result.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Skip (not installed): $pkg")
            }
        }
        Log.i(TAG, "Kiosk grid: ${result.size} app(s): ${result.joinToString()}")
        return result
    }

    private fun isLaunchable(pm: PackageManager, packageName: String): Boolean =
        pm.getLaunchIntentForPackage(packageName) != null
}
