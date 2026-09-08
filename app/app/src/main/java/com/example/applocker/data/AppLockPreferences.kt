package com.example.applocker.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

class AppLockPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ctx: Context = context

    companion object {
        private const val PREFS_NAME       = "app_locker_prefs_v2"
        private const val KEY_SERVER_URL   = "server_url"
        private const val KEY_ALLOWED_APPS = "allowed_apps_json"
        private const val KEY_BLOCK_GPS    = "block_gps"
        private const val KEY_BLOCK_DT     = "block_datetime"
        private const val KEY_PROVISIONED  = "is_provisioned"
        private const val KEY_LOCAL_PASS           = "local_password"
        private const val KEY_SERVER_LOCAL_PASS    = "server_local_password"
        private const val KEY_DEVICE_LOCAL_PASS    = "device_local_password"
        private const val KEY_DEVICE_ID            = "device_id"
        private const val KEY_DEVICE_KEY           = "device_key"
        private const val KEY_CLEAR_DATA_PASS      = "clear_data_password"
        private const val KEY_DEVICE_NAME          = "device_name"
        private const val KEY_FONT_SIZE           = "font_size"
        private const val KEY_DATA_OFF_PASS       = "data_off_password"

        /** Patrón de desbloqueo fijo, definido por defecto al instalar. No se puede cambiar. */
        const val FIXED_UNLOCK_PATTERN = "03678"

        // ── Siempre bloqueadas ──────────────────────────────────────────────
        val ALWAYS_BLOCKED = setOf(
            // Play Store / instaladores
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            // Settings — todos los fabricantes
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",          // Xiaomi/Redmi seguridad
            "com.xiaomi.misettings",            // Xiaomi/Redmi ajustes
            "com.huawei.systemmanager",         // Honor/Huawei
            "com.coloros.safecenter",            // Oppo/Realme
            "com.oplus.safecenter",             // OnePlus
            // Google Services (ruta indirecta a settings)
            "com.google.android.gms",
        )

        // ── Paquetes de Settings que el AccessibilityService debe bloquear ──
        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
            "com.xiaomi.misettings",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
        )

        // ── Nunca bloqueadas ────────────────────────────────────────────────
        val ALWAYS_ALLOWED = setOf(
            "com.example.applocker",
            // Teléfono
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
            // Mensajes
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            // Contactos
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.contacts",
            // System UI
            "com.android.systemui",
            // Teclados
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.swiftkey.swiftkeyapp",
            "com.touchtype.swiftkey",
            "com.google.android.inputmethod.korean",
            "com.android.inputmethod.latin",
            // Launchers de sistema (necesarios para HOME intent)
            "com.miui.home",                     // Xiaomi/Redmi
            "com.huawei.android.launcher",       // Honor/Huawei
            "com.motorola.launcher3",            // Motorola
            "com.motorola.dialer",
            "com.motorola.dialer.primary",
            "com.motorola.messaging",
            "com.motorola.messaging.primary",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.aurora.store",
        )

        // ── Apps visibles en el kiosk launcher ──────────────────────────────
        val ALWAYS_VISIBLE = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.motorola.dialer",
            "com.motorola.dialer.primary",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.contacts",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.motorola.messaging",
            "com.motorola.messaging.primary",
        )
    }

    /** Applies server provision / WebSocket config payload to local prefs. */
    init {
        migrateLegacyLocalPassword()
    }

    private fun migrateLegacyLocalPassword() {
        val legacy = prefs.getString(KEY_LOCAL_PASS, null)?.takeIf { it.isNotBlank() }
        val serverValue = prefs.getString(KEY_SERVER_LOCAL_PASS, null)?.takeIf { it.isNotBlank() }
        val deviceValue = prefs.getString(KEY_DEVICE_LOCAL_PASS, null)?.takeIf { it.isNotBlank() }
        if (serverValue.isNullOrBlank() && deviceValue.isNullOrBlank() && !legacy.isNullOrBlank()) {
            prefs.edit().putString(KEY_SERVER_LOCAL_PASS, legacy).remove(KEY_LOCAL_PASS).apply()
        }
    }

    fun applyServerConfig(json: JSONObject) {
        if (json.has("allowedApps")) {
            setAllowedApps(jsonArrayToSet(json.getJSONArray("allowedApps")))
        }
        if (json.has("localPassword")) {
            val pass = json.getString("localPassword")
            android.util.Log.d("FLShieldPrefs", "Received localPassword: $pass")
            if (pass.isNotBlank()) serverPassword = pass
        }
        if (json.has("blockGps")) blockGps = json.getBoolean("blockGps")
        if (json.has("blockDateTime")) blockDateTime = json.getBoolean("blockDateTime")
        if (json.has("clearDataPassword")) {
            val pass = json.getString("clearDataPassword")
            if (pass.isNotBlank()) clearDataPassword = pass
        }
        if (json.has("deviceName")) {
            val name = json.getString("deviceName")
            deviceName = name
        }
        if (json.has("dataOffPassword")) {
            val pass = json.getString("dataOffPassword")
            android.util.Log.d("FLShieldPrefs", "Received dataOffPassword: $pass")
            dataOffPassword = pass
        }
    }

    private fun jsonArrayToSet(arr: JSONArray): Set<String> {
        val out = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val pkg = arr.optString(i).trim()
            if (pkg.isNotEmpty()) out.add(pkg)
        }
        return out
    }

    // ── Server URL ────────────────────────────────────────────────────────────

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) { prefs.edit().putString(KEY_SERVER_URL, value).apply() }

    var serverPassword: String?
        get() = prefs.getString(KEY_SERVER_LOCAL_PASS, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_SERVER_LOCAL_PASS).apply()
            else prefs.edit().putString(KEY_SERVER_LOCAL_PASS, value).apply()
        }

    var devicePasswordOverride: String?
        get() = prefs.getString(KEY_DEVICE_LOCAL_PASS, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_DEVICE_LOCAL_PASS).apply()
            else prefs.edit().putString(KEY_DEVICE_LOCAL_PASS, value).apply()
        }

    fun clearDevicePasswordOverride() {
        prefs.edit().remove(KEY_DEVICE_LOCAL_PASS).apply()
    }

    val hasDevicePasswordOverride: Boolean
        get() = devicePasswordOverride != null

    var localPassword: String
        get() = devicePasswordOverride ?: serverPassword ?: "1234"
        set(value) { devicePasswordOverride = value }

    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val androidId = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            val model = android.os.Build.MODEL.replace(" ", "_")
            val id = androidId?.takeIf { it.isNotBlank() } ?: android.os.Build.FINGERPRINT.take(16)
            val newId = "${model}_${id}"
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }

    var deviceKey: String?
        get() = prefs.getString(KEY_DEVICE_KEY, null)
        set(value) { prefs.edit().putString(KEY_DEVICE_KEY, value).apply() }

    var clearDataPassword: String
        get() = prefs.getString(KEY_CLEAR_DATA_PASS, "5678") ?: "5678"
        set(value) { prefs.edit().putString(KEY_CLEAR_DATA_PASS, value).apply() }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE_NAME, value).apply() }

    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 16)
        set(value) { prefs.edit().putInt(KEY_FONT_SIZE, value).apply() }

    /** Fijo por diseño: patrón de desbloqueo predeterminado, no cambiable. */
    val unlockPattern: String
        get() = FIXED_UNLOCK_PATTERN

    var dataOffPassword: String
        get() = prefs.getString(KEY_DATA_OFF_PASS, "4321") ?: "4321"
        set(value) { prefs.edit().putString(KEY_DATA_OFF_PASS, value).apply() }

    var isProvisioned: Boolean
        get() = prefs.getBoolean(KEY_PROVISIONED, false)
        set(value) { prefs.edit().putBoolean(KEY_PROVISIONED, value).apply() }


    // ── Whitelist ─────────────────────────────────────────────────────────────

    fun getAllowedApps(): Set<String> {
        val json = prefs.getString(KEY_ALLOWED_APPS, "[]") ?: "[]"
        return parseJsonArray(json)
    }

    fun setAllowedApps(apps: Set<String>) {
        val json = apps.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
        prefs.edit().putString(KEY_ALLOWED_APPS, json).apply()
    }

    fun isPackageAllowed(packageName: String): Boolean {
        if (ALWAYS_ALLOWED.contains(packageName)) return true
        // Also treat ALWAYS_VISIBLE packages (launcher/dialer) as allowed
        if (ALWAYS_VISIBLE.contains(packageName)) return true
        
        // Allow Settings and Package Installer temporarily if admin settings navigation is active or Aurora workflow is active
        val isSettingsOrInstaller = SETTINGS_PACKAGES.contains(packageName) ||
                packageName == "com.android.packageinstaller" ||
                packageName == "com.google.android.packageinstaller"
        if (isSettingsOrInstaller &&
            (com.example.applocker.service.SettingsMonitorService.isSettingsNavigationAllowed() ||
             com.example.applocker.service.SettingsMonitorService.isAuroraActive)) {
            return true
        }

        if (ALWAYS_BLOCKED.contains(packageName)) return false
        // Treat the currently installed HOME (launcher) package as allowed
        try {
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            val res = ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            if (res?.activityInfo?.packageName == packageName) return true
        } catch (_: Exception) {
        }
        // Treat the default dialer as allowed
        try {
            val dial = android.content.Intent(android.content.Intent.ACTION_DIAL)
            val res2 = ctx.packageManager.resolveActivity(dial, PackageManager.MATCH_DEFAULT_ONLY)
            if (res2?.activityInfo?.packageName == packageName) return true
        } catch (_: Exception) {
        }
        if (!isProvisioned) return true
        val allowed = getAllowedApps()
        // Strict: empty whitelist → only essentials (no third-party apps)
        if (allowed.isEmpty()) return false
        return allowed.contains(packageName)
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    var blockGps: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_GPS, false)
        set(value) { prefs.edit().putBoolean(KEY_BLOCK_GPS, value).apply() }

    var blockDateTime: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_DT, true)
        set(value) { prefs.edit().putBoolean(KEY_BLOCK_DT, value).apply() }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseJsonArray(json: String): Set<String> {
        return try {
            json.trim().removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
