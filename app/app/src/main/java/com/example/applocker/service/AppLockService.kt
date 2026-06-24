package com.example.applocker.service

import android.app.*
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.applocker.AppLockerDeviceAdminReceiver
import com.example.applocker.KioskHomeActivity
import com.example.applocker.data.AppLockPreferences
import com.example.applocker.ui.LockOverlayHelper
import com.example.applocker.ui.StatusBarBlocker
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppLockService : Service() {

    companion object {
        private const val TAG        = "FLShieldService"
        private const val NOTIF_ID   = 1001
        private const val CHANNEL_ID = "flshield_channel"
        const val ACTION_START       = "ACTION_START"
        const val ACTION_STOP        = "ACTION_STOP"
        const val ACTION_BLOCK_SETTINGS = "ACTION_BLOCK_SETTINGS"
        const val BROADCAST_CONFIG_UPDATED = "com.example.applocker.CONFIG_UPDATED"
        const val BROADCAST_SEND_UNLOCK_REQUEST = "com.example.applocker.SEND_UNLOCK_REQUEST"
        const val BROADCAST_SEND_BLOCK_REQUEST = "com.example.applocker.SEND_BLOCK_REQUEST"
    }

    private lateinit var prefs: AppLockPreferences
    private lateinit var overlay: LockOverlayHelper
    private lateinit var usageStats: UsageStatsManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var statusBarBlocker: StatusBarBlocker
    private lateinit var apkSyncManager: ApkSyncManager


    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var webSocket: WebSocket? = null
    private val wsConnected = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastForeground: String? = null
    private val overlayVisible = AtomicBoolean(false)

    // Broadcast receiver for home screen requests
    private val unlockRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BROADCAST_SEND_UNLOCK_REQUEST -> {
                    Log.d(TAG, "Received unlock request from home screen")
                    sendUnlockRequest()
                }
                BROADCAST_SEND_BLOCK_REQUEST -> {
                    Log.d(TAG, "Received block request from home screen")
                    sendBlockRequest()
                }
            }
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_PACKAGE_ADDED || 
                action == Intent.ACTION_PACKAGE_REMOVED || 
                action == Intent.ACTION_PACKAGE_REPLACED) {
                val pkgName = intent.data?.schemeSpecificPart ?: return
                Log.d(TAG, "Package change detected: $pkgName (action=$action). Sending updated list to server.")
                sendInstalledAppsUpdate()
                if (action != Intent.ACTION_PACKAGE_REMOVED && prefs.isPackageAllowed(pkgName)) {
                    scope.launch(Dispatchers.IO) {
                        grantPermissionsToPackage(pkgName)
                    }
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_OFF || 
                action == Intent.ACTION_SCREEN_ON || 
                action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "Screen event detected ($action). Showing lock overlay.")
                mainHandler.post {
                    if (!overlay.isShowing()) {
                        if (overlay.show()) {
                            overlayVisible.set(true)
                        }
                    }
                }
            }
        }
    }

    // Temporary grants are shared across the service and accessibility monitor
    // so a locally unlocked app remains allowed until the grant expires.

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = AppLockPreferences(this)
        apkSyncManager = ApkSyncManager(this, scope, http)

        overlay = LockOverlayHelper(
            context = this,
            getLocalPassword = { prefs.unlockPattern },
            onLocalUnlockSuccess = {
                // Local unlock success — dismiss overlay and temp-grant the current app
                lastForeground?.let { pkg ->
                    GrantManager.grant(pkg, 60_000L)
                }
                mainHandler.post {
                    overlayVisible.set(false)
                    overlay.dismiss()
                }
            },
            onUnlockRequested = { sendUnlockRequest() }
        )
        usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AppLockerDeviceAdminReceiver::class.java)
        statusBarBlocker = StatusBarBlocker(this)
        createNotificationChannel()
        
        // Register broadcast receiver for home screen requests
        try {
            val filter = IntentFilter().apply {
                addAction(BROADCAST_SEND_UNLOCK_REQUEST)
                addAction(BROADCAST_SEND_BLOCK_REQUEST)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockRequestReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(unlockRequestReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register request receiver", e)
        }

        try {
            val pkgFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            registerReceiver(packageChangeReceiver, pkgFilter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register package change receiver", e)
        }
        
        try {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, screenFilter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }
        
        Log.d(TAG, "Service created")
        startUdpListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            devicePolicyManager.setStatusBarDisabled(adminComponent, false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-enable status bar via DPM", e)
                    }
                } else {
                    statusBarBlocker.disable()
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_BLOCK_SETTINGS -> {
                mainHandler.post {
                    ejectToHome()
                }
            }
        }

        startForegroundNotification()
        setupKioskMode()
        applyDeviceOwnerRestrictions()

        // Enable status bar blocker: DPM (official, doesn't intercept touch) if Owner, otherwise fallback to overlay
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    devicePolicyManager.setStatusBarDisabled(adminComponent, true)
                    Log.d(TAG, "Status bar disabled via DevicePolicyManager")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable status bar via DPM", e)
                mainHandler.post { statusBarBlocker.enable() }
            }
        } else {
            mainHandler.post { statusBarBlocker.enable() }
        }

        scope.launch { monitorLoop() }
        scope.launch {
            delay(1000)
            syncConfigHttp()
            while (isActive) {
                delay(30 * 60 * 1000L)
                syncConfigHttp()
                if (!wsConnected.get()) {
                    connectWebSocket()
                }
            }
        }
        connectWebSocket()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUdpListener()
        apkSyncManager.cleanup()

        try {
            unregisterReceiver(unlockRequestReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister unlock request receiver", e)
        }

        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister package change receiver", e)
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        serviceJob.cancel()
        webSocket?.close(1000, "Service stopped")
        mainHandler.post {
            overlay.dismiss()
            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        devicePolicyManager.setStatusBarDisabled(adminComponent, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-enable status bar via DPM", e)
                }
            } else {
                statusBarBlocker.disable()
            }
        }
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Kiosk / Lock Task setup ───────────────────────────────────────────────

    private fun setupKioskMode() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Log.d(TAG, "Not device owner — kiosk lock task not available, using overlay mode")
            return
        }

        try {
            // 1. Set which packages can enter lock task mode
            val lockTaskPackages = mutableSetOf(packageName).apply {
                addAll(AppLockPreferences.ALWAYS_ALLOWED)
                addAll(prefs.getAllowedApps())
                addAll(getInstalledSettingsPackages())
            }
            devicePolicyManager.setLockTaskPackages(adminComponent, lockTaskPackages.toTypedArray())
            Log.d(TAG, "Lock task packages set: ${lockTaskPackages.size}")

            // 2. Configure lock task features (what's visible in kiosk mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.setLockTaskFeatures(
                    adminComponent,
                    // Allow HOME button (goes to our KioskHomeActivity)
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    // Allow notifications (but status bar blocked by our overlay)
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                    // NOT including: LOCK_TASK_FEATURE_SYSTEM_INFO (hides clock/battery)
                    // NOT including: LOCK_TASK_FEATURE_KEYGUARD
                    // NOT including: LOCK_TASK_FEATURE_OVERVIEW (blocks recent apps)
                    // NOT including: LOCK_TASK_FEATURE_GLOBAL_ACTIONS (blocks power menu)
                )
                Log.d(TAG, "Lock task features configured")
            }

            // 3. Set our KioskHomeActivity as the preferred home activity
            val kioskComponent = ComponentName(packageName, KioskHomeActivity::class.java.name)
            val filter = android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            devicePolicyManager.addPersistentPreferredActivity(adminComponent, filter, kioskComponent)
            Log.d(TAG, "KioskHomeActivity set as preferred home")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up kiosk mode", e)
        }
    }

    private fun getInstalledSettingsPackages(): Set<String> {
        return AppLockPreferences.SETTINGS_PACKAGES.filter { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }.toSet()
    }

    private fun applyDeviceOwnerRestrictions() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) return
        try {
            if (prefs.blockDateTime) {
                devicePolicyManager.addUserRestriction(
                    adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME
                )
            } else {
                devicePolicyManager.clearUserRestriction(
                    adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME
                )
            }
            if (prefs.blockGps) {
                devicePolicyManager.addUserRestriction(
                    adminComponent, UserManager.DISALLOW_CONFIG_LOCATION
                )
                devicePolicyManager.addUserRestriction(
                    adminComponent, UserManager.DISALLOW_SHARE_LOCATION
                )
            } else {
                devicePolicyManager.clearUserRestriction(
                    adminComponent, UserManager.DISALLOW_CONFIG_LOCATION
                )
                devicePolicyManager.clearUserRestriction(
                    adminComponent, UserManager.DISALLOW_SHARE_LOCATION
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        devicePolicyManager.setLocationEnabled(adminComponent, true)
                        Log.d(TAG, "Device location forced ON via DevicePolicyManager")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to force location ON", e)
                }
            }
            Log.d(TAG, "Device owner restrictions applied (gps=${prefs.blockGps}, dt=${prefs.blockDateTime})")
            
            // Auto grant permissions in background to prevent blocking main thread (ANR)
            scope.launch(Dispatchers.IO) {
                for (pkg in AppLockPreferences.ALWAYS_ALLOWED) {
                    grantPermissionsToPackage(pkg)
                }
                for (pkg in AppLockPreferences.ALWAYS_VISIBLE) {
                    grantPermissionsToPackage(pkg)
                }
                for (pkg in prefs.getAllowedApps()) {
                    grantPermissionsToPackage(pkg)
                }
                grantSelfRuntimePermissions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply device owner restrictions", e)
        }
    }

    private fun grantPermissionsToPackage(targetPkg: String) {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) return
        try {
            val pm = packageManager
            val info = pm.getPackageInfo(targetPkg, android.content.pm.PackageManager.GET_PERMISSIONS)
            val requestedPermissions = info.requestedPermissions ?: return
            
            for (perm in requestedPermissions) {
                try {
                    devicePolicyManager.setPermissionGrantState(
                        adminComponent,
                        targetPkg,
                        perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    Log.d(TAG, "Programmatically granted permission $perm to $targetPkg")
                } catch (e: Exception) {
                    // Ignore non-runtime permissions
                }
            }
        } catch (e: Exception) {
            // Package might not be installed yet
        }
    }

    private fun grantSelfRuntimePermissions() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) return
        val permissions = arrayOf(
            "android.permission.CAMERA",
            "android.permission.POST_NOTIFICATIONS"
        )
        for (perm in permissions) {
            try {
                devicePolicyManager.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.d(TAG, "Auto-granted runtime permission $perm to self")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to grant runtime permission $perm to self: ${e.message}")
            }
        }
    }

    // ── Monitoring loop ───────────────────────────────────────────────────────

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return km.isKeyguardLocked || !pm.isInteractive
    }

    private suspend fun monitorLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                if (isDeviceLocked()) {
                    delay(1000)
                    continue
                }
                val current = getForegroundPackage()
                if (current != null && current != packageName) {
                    evaluatePackage(current)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Monitor error", e)
            }
            delay(200)
        }
    }

    private fun ejectToHome() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Log.d(TAG, "Ejected to home from AppLockService because package is not allowed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to eject to home", e)
        }
    }

    private fun evaluatePackage(pkg: String) {
        if (isDeviceLocked()) return
        val now = System.currentTimeMillis()

        if (lastForeground != null && lastForeground != pkg) {
            GrantManager.cleanup()
        }
        lastForeground = pkg

        if (com.example.applocker.service.SettingsMonitorService.isKioskPaused()) {
            return
        }

        val allowed = prefs.isPackageAllowed(pkg) || 
                      GrantManager.isGranted(pkg) || 
                      pkg == packageName || 
                      pkg == "com.android.systemui"

        mainHandler.post {
            if (!allowed) {
                ejectToHome()
            }
        }
    }

    private fun getForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        val start = end - 5_000L
        val events = usageStats.queryEvents(start, end)
        var pkg: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            @Suppress("DEPRECATION")
            val isFg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            else
                ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isFg) pkg = ev.packageName
        }
        return pkg
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        if (wsConnected.get()) return
        val serverUrl = prefs.serverUrl ?: return
        val wsUrl = serverUrl.replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws"

        val req = Request.Builder().url(wsUrl).build()
        Log.d(TAG, "Connecting to WebSocket at $wsUrl")

        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected.set(true)
                Log.d(TAG, "WebSocket connected")
                webSocket.send(JSONObject().apply {
                    put("type", "register")
                    put("deviceId", prefs.deviceId)
                    prefs.deviceKey?.takeIf { it.isNotBlank() }?.let { put("deviceKey", it) }
                    put("installedApps", getInstalledAppsJson())
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS message: $text")
                handleServerMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected.set(false)
                this@AppLockService.webSocket = null
                Log.d(TAG, "WS closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsConnected.set(false)
                this@AppLockService.webSocket = null
                Log.e(TAG, "WS failure", t)
                scheduleReconnect()
            }
        })
    }

    private fun handleServerMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("action", msg.optString("type"))) {

                "registered", "config_update" -> {
                    prefs.applyServerConfig(msg)
                    if (msg.has("deviceKey")) {
                        val receivedKey = msg.getString("deviceKey")
                        if (receivedKey.isNotBlank()) prefs.deviceKey = receivedKey
                    }
                    val set = prefs.getAllowedApps()
                    Log.d(TAG, "Whitelist updated: ${set.size} apps")
                    if (msg.has("apks")) {
                        apkSyncManager.syncApks(msg.getJSONArray("apks"))
                    }

                    if (msg.optBoolean("blocked", false)) {
                        mainHandler.post {
                            if (!overlayVisible.get()) {
                                if (overlay.show()) {
                                    overlayVisible.set(true)
                                }
                            }
                        }
                    } else {
                        mainHandler.post {
                            if (overlayVisible.get()) {
                                overlayVisible.set(false)
                                overlay.dismiss()
                            }
                        }
                    }

                    if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                        val lockPkgs = mutableSetOf(packageName).apply {
                            addAll(AppLockPreferences.ALWAYS_ALLOWED)
                            addAll(set)
                            addAll(getInstalledSettingsPackages())
                        }
                        try {
                            devicePolicyManager.setLockTaskPackages(
                                adminComponent, lockPkgs.toTypedArray()
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update lock task packages", e)
                        }
                    }
                    applyDeviceOwnerRestrictions()
                    sendBroadcast(Intent(BROADCAST_CONFIG_UPDATED))
                }

                "unlock_granted" -> {
                    Log.d(TAG, "Remote unlock GRANTED")
                    lastForeground?.let { pkg ->
                        GrantManager.grant(pkg, 60_000L)
                    }
                    mainHandler.post {
                        overlay.unlockState.value = LockOverlayHelper.UnlockState.Granted
                    }
                    mainHandler.postDelayed({
                        overlayVisible.set(false)
                        overlay.dismiss()
                    }, 1200)
                }

                "unlock_denied" -> {
                    Log.d(TAG, "Remote unlock DENIED")
                    mainHandler.post {
                        overlay.unlockState.value = LockOverlayHelper.UnlockState.Denied
                    }
                }

                "block_granted" -> {
                    Log.d(TAG, "Remote block GRANTED")
                    mainHandler.post {
                        if (!overlayVisible.get()) {
                            if (overlay.show()) {
                                overlayVisible.set(true)
                            }
                        }
                    }
                }

                "unblock" -> {
                    Log.d(TAG, "Remote unblock received")
                    mainHandler.post {
                        overlayVisible.set(false)
                        overlay.dismiss()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message", e)
        }
    }

    private fun sendUnlockRequest() {
        if (!wsConnected.get()) {
            Log.w(TAG, "WebSocket not connected; attempting reconnect")
            connectWebSocket()
            return
        }

        webSocket?.send(JSONObject().apply {
            put("type", "unlock_request")
            put("deviceId", prefs.deviceId)
            prefs.deviceKey?.takeIf { it.isNotBlank() }?.let { put("deviceKey", it) }
        }.toString()) ?: Log.w(TAG, "WebSocket not connected")
    }

    private fun sendBlockRequest() {
        if (!wsConnected.get()) {
            Log.w(TAG, "WebSocket not connected; attempting reconnect")
            connectWebSocket()
            return
        }

        webSocket?.send(JSONObject().apply {
            put("type", "block_request")
            put("deviceId", prefs.deviceId)
            prefs.deviceKey?.takeIf { it.isNotBlank() }?.let { put("deviceKey", it) }
        }.toString()) ?: Log.w(TAG, "WebSocket not connected")
    }

    private fun getInstalledAppsJson(): org.json.JSONArray {
        val pm = packageManager
        val packages = pm.getInstalledPackages(0)
        val arr = org.json.JSONArray()
        for (pkg in packages) {
            try {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                val isLaunchable = launchIntent != null
                
                if (!isSystem || isLaunchable || AppLockPreferences.ALWAYS_ALLOWED.contains(pkg.packageName) || AppLockPreferences.ALWAYS_BLOCKED.contains(pkg.packageName)) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    arr.put(JSONObject().apply {
                        put("packageName", pkg.packageName)
                        put("label", label)
                        put("isSystem", isSystem)
                    })
                }
            } catch (_: Exception) {}
        }
        return arr
    }

    private fun sendInstalledAppsUpdate() {
        if (!wsConnected.get() || webSocket == null) return
        scope.launch {
            try {
                webSocket?.send(JSONObject().apply {
                    put("type", "installed_apps_update")
                    put("deviceId", prefs.deviceId)
                    prefs.deviceKey?.takeIf { it.isNotBlank() }?.let { put("deviceKey", it) }
                    put("installedApps", getInstalledAppsJson())
                }.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending installed apps update", e)
            }
        }
    }

    private fun startUdpListener() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(50001).apply {
                    reuseAddress = true
                }
                udpSocket = socket
                val buffer = ByteArray(1024)
                Log.d(TAG, "UDP listener started on port 50001")
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    Log.d(TAG, "Received UDP broadcast packet: $message")
                    
                    syncConfigHttp()
                    mainHandler.post {
                        if (!wsConnected.get()) {
                            connectWebSocket()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in UDP listener", e)
            }
        }
    }

    private fun stopUdpListener() {
        try {
            udpSocket?.close()
            udpSocket = null
        } catch (_: Exception) {}
    }

    private fun syncConfigHttp() {
        val serverUrl = prefs.serverUrl ?: return
        val deviceId = prefs.deviceId
        val deviceKey = prefs.deviceKey ?: ""
        
        val url = try {
            serverUrl.trim().trimEnd('/') + 
                    "/api/provision?deviceId=${java.net.URLEncoder.encode(deviceId, "UTF-8")}" +
                    if (deviceKey.isNotBlank()) "&deviceKey=${java.net.URLEncoder.encode(deviceKey, "UTF-8")}" else ""
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding URL", e)
            return
        }
                
        val request = Request.Builder().url(url).build()
        
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "HTTP sync failed", e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string() ?: ""
                        try {
                            val json = JSONObject(body)
                            prefs.applyServerConfig(json)
                            Log.d(TAG, "HTTP sync successful, applied config")
                            
                            if (json.has("apks")) {
                                apkSyncManager.syncApks(json.getJSONArray("apks"))
                            }
                            
                            if (json.optBoolean("blocked", false)) {
                                mainHandler.post {
                                    if (!overlayVisible.get()) {
                                        if (overlay.show()) {
                                            overlayVisible.set(true)
                                        }
                                    }
                                }
                            } else {
                                mainHandler.post {
                                    if (overlayVisible.get()) {
                                        overlayVisible.set(false)
                                        overlay.dismiss()
                                    }
                                }
                            }
                            
                            val set = prefs.getAllowedApps()
                            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                                val lockPkgs = mutableSetOf(packageName).apply {
                                    addAll(AppLockPreferences.ALWAYS_ALLOWED)
                                    addAll(set)
                                    addAll(getInstalledSettingsPackages())
                                }
                                try {
                                    devicePolicyManager.setLockTaskPackages(
                                        adminComponent, lockPkgs.toTypedArray()
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to update lock task packages", e)
                                }
                            }
                            applyDeviceOwnerRestrictions()
                            sendBroadcast(Intent(BROADCAST_CONFIG_UPDATED))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error applying sync config", e)
                        }
                    } else {
                        Log.e(TAG, "HTTP sync returned error code: ${it.code}")
                    }
                }
            }
        })
    }

    private fun scheduleReconnect() {
        wsConnected.set(false)
        webSocket = null
        scope.launch {
            delay(5_000)
            if (isActive && !wsConnected.get()) connectWebSocket()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        val model = Build.MODEL.replace(" ", "_")
        val id = androidId?.takeIf { it.isNotBlank() } ?: Build.FINGERPRINT.take(16)
        return "${model}_$id"
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, KioskHomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FLShield activo")
            .setContentText("Dispositivo protegido")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "FLShield Service",
                NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                description = "Servicio de protección en segundo plano"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}
