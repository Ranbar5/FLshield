package com.example.applocker.service

object GrantManager {
    private val grantedApps = mutableMapOf<String, Long>()

    @Synchronized
    fun grant(packageName: String, durationMs: Long) {
        grantedApps[packageName] = System.currentTimeMillis() + durationMs
    }

    @Synchronized
    fun isGranted(packageName: String): Boolean {
        val expiresAt = grantedApps[packageName] ?: return false
        if (expiresAt < System.currentTimeMillis()) {
            grantedApps.remove(packageName)
            return false
        }
        return true
    }

    @Synchronized
    fun cleanup() {
        val now = System.currentTimeMillis()
        grantedApps.entries.removeIf { it.value < now }
    }
}
