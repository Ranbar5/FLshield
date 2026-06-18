package com.example.applocker

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.hardware.camera2.CameraManager
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.HorizontalDivider
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import com.example.applocker.service.SettingsMonitorService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.applocker.data.AppLockPreferences
import com.example.applocker.data.KioskAppResolver
import com.example.applocker.service.AppLockService
import com.example.applocker.theme.AppLockerTheme
import java.text.SimpleDateFormat
import java.util.*

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

class KioskHomeActivity : ComponentActivity() {

    private lateinit var prefs: AppLockPreferences
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var appsState = mutableStateOf<List<AppItem>>(emptyList())
    private var deviceNameState = mutableStateOf("")
    private var isDefaultHomeState = mutableStateOf(false)
    private var fontSizeState = mutableStateOf(16)
    private var homeSettingsPrompted = false

    // Flashlight tracking
    private var isFlashlightOn = mutableStateOf(false)
    private var cameraManager: CameraManager? = null
    private var torchCallback: CameraManager.TorchCallback? = null

    // Receive config updates from AppLockService
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appsState.value = loadAllowedApps()
            deviceNameState.value = prefs.deviceName
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppLockPreferences(this)
        deviceNameState.value = prefs.deviceName
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, com.example.applocker.AppLockerDeviceAdminReceiver::class.java)
        appsState.value = loadAllowedApps()

        // Start the lock service if not running
        startService(Intent(this, AppLockService::class.java).apply {
            action = AppLockService.ACTION_START
        })

        registerReceiver(configReceiver, IntentFilter("com.example.applocker.CONFIG_UPDATED"),
            RECEIVER_NOT_EXPORTED)

        isDefaultHomeState.value = isCurrentDefaultHome()
        fontSizeState.value = prefs.fontSizeSp
        verifyHomeLauncher()
        setupFlashlight()

        setContent {
            AppLockerTheme {
                KioskScreen()
            }
        }
    }

    private fun setupFlashlight() {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchCallback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    super.onTorchModeChanged(cameraId, enabled)
                    val firstId = cameraManager?.cameraIdList?.firstOrNull()
                    if (cameraId == firstId) {
                        isFlashlightOn.value = enabled
                    }
                }
            }
            cameraManager?.registerTorchCallback(torchCallback!!, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e("KioskHome", "Error registering torch callback", e)
        }
    }

    private fun toggleFlashlight() {
        try {
            val manager = cameraManager ?: return
            val id = manager.cameraIdList.firstOrNull() ?: return
            val target = !isFlashlightOn.value
            manager.setTorchMode(id, target)
            isFlashlightOn.value = target
        } catch (e: Exception) {
            Log.e("KioskHome", "Failed to toggle flashlight", e)
            Toast.makeText(this, "No se pudo cambiar el estado de la linterna", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMobileDataSettings() {
        try {
            SettingsMonitorService.allowSettingsNavigation(30_000L)
            val intent = Intent(Settings.ACTION_DATA_USAGE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                SettingsMonitorService.allowSettingsNavigation(30_000L)
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (ex: Exception) {
                Log.e("KioskHome", "Unable to open mobile data settings", ex)
                Toast.makeText(this, "No se pudo abrir Datos Móviles", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appsState.value = loadAllowedApps()
        deviceNameState.value = prefs.deviceName
        isDefaultHomeState.value = isCurrentDefaultHome()
        fontSizeState.value = prefs.fontSizeSp
        if (!com.example.applocker.service.SettingsMonitorService.isKioskPaused()) {
            startLockTaskIfPermitted()
        }
        com.example.applocker.service.SettingsMonitorService.isAuroraActive = false
    }

    private fun startLockTaskIfPermitted() {
        try {
            if (devicePolicyManager.isDeviceOwnerApp(packageName) &&
                devicePolicyManager.isLockTaskPermitted(packageName)) {
                startLockTask()
            }
        } catch (e: Exception) {
            Log.e("KioskHome", "Unable to enter lock task mode", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(configReceiver) } catch (_: Exception) {}
        try {
            torchCallback?.let {
                cameraManager?.unregisterTorchCallback(it)
            }
        } catch (_: Exception) {}
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Block back button on home screen
    }

    @Composable
    private fun KioskScreen() {
        val apps by appsState
        val deviceName by deviceNameState
        val isDefaultHome by isDefaultHomeState
        val fontSizeSp by fontSizeState
        var currentTime by remember { mutableStateOf(getCurrentTime()) }
        var currentDate by remember { mutableStateOf(getCurrentDate()) }
        var showPinDialog by remember { mutableStateOf(false) }
        var pinInput by remember { mutableStateOf("") }
        var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
        var showSettingsMenu by remember { mutableStateOf(false) }
        var kioskActive by remember { mutableStateOf(isLockTaskModeActive()) }

        var showClearDataDialog by remember { mutableStateOf(false) }
        var clearDataPackageName by remember { mutableStateOf("") }
        var clearDataAppLabel by remember { mutableStateOf("") }
        var clearDataPasswordInput by remember { mutableStateOf("") }

        var batteryLevel by remember { mutableStateOf(100) }
        var isCharging by remember { mutableStateOf(false) }
        val context = LocalContext.current
        
        DisposableEffect(context) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    intent?.let {
                        val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                        if (level != -1 && scale != -1) {
                            batteryLevel = (level.toFloat() / scale.toFloat() * 100).toInt()
                        }
                        val status = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                        isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            onDispose {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
        }

        // Periodically refresh kiosk mode status and clock
        LaunchedEffect(Unit) {
            while (true) {
                kioskActive = isLockTaskModeActive()
                kotlinx.coroutines.delay(30_000)
                currentTime = getCurrentTime()
                currentDate = getCurrentDate()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090E1A))
                .systemBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Clock & Buttons header ───────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    // Clock
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (deviceName.isNotBlank()) {
                                Text(
                                    text = deviceName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6366F1),
                                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
                                )
                            }
                            Text(
                                text = currentTime,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Light,
                                color = Color.White,
                                letterSpacing = (-2).sp
                            )
                        }
                        Text(
                            text = currentDate,
                            fontSize = 14.sp,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (isCharging) "⚡" else "🔋",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = "$batteryLevel%" + (if (isCharging) " (Cargando)" else ""),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (batteryLevel > 20) Color(0xFF94A3B8) else Color(0xFFEF4444)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Kiosk status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111827), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (kioskActive) "Modo kiosk activo" else "Modo kiosk inactivo",
                            color = if (kioskActive) Color(0xFF34D399) else Color(0xFFFBBF24),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ── App grid ─────────────────────────────────────────────────
                if (!isDefaultHome) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .background(Color(0xFF1F2937), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                "FLShield no es el launcher predeterminado",
                                color = Color(0xFFEAB308),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Para mantener el kiosk activo debes seleccionar FLShield como launcher predeterminado.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { openHomeSettings() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Seleccionar launcher predeterminado", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }

                if (apps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text("📭", fontSize = 52.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Sin aplicaciones permitidas",
                                color = Color(0xFF64748B),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "El administrador debe agregar apps en el Centro de Control",
                                color = Color(0xFF475569),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            AppIconItem(
                                app = app,
                                fontSizeSp = fontSizeSp,
                                onClick = { launchApp(app.packageName) },
                                onLongClick = {
                                    clearDataPackageName = app.packageName
                                    clearDataAppLabel = app.label
                                    clearDataPasswordInput = ""
                                    showClearDataDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // PIN Dialog for Settings
            PinDialogComposable(
                showDialog = showPinDialog,
                pinInput = pinInput,
                onPinChange = { pinInput = it },
                onDismiss = {
                    showPinDialog = false
                    pendingAction = null
                    pinInput = ""
                },
                onAccept = {
                    if (pinInput == prefs.localPassword) {
                        showPinDialog = false
                        val action = pendingAction
                        pendingAction = null
                        pinInput = ""
                        when (action) {
                            PendingAction.SETTINGS -> startActivity(Intent(this@KioskHomeActivity, SettingsActivity::class.java))
                            else -> {}
                        }
                    } else {
                        Toast.makeText(this@KioskHomeActivity, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                        showPinDialog = false
                        pendingAction = null
                        pinInput = ""
                    }
                }
            )

            // Clear Data Dialog
            if (showClearDataDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = true) { 
                            showClearDataDialog = false 
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1F2937), RoundedCornerShape(16.dp))
                            .padding(24.dp)
                            .clickable(enabled = false) { }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "¿Borrar datos de $clearDataAppLabel?",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Esta acción restablecerá el almacenamiento de la aplicación y borrará la caché. Se requiere la contraseña de administración de datos.",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = clearDataPasswordInput,
                                onValueChange = { clearDataPasswordInput = it },
                                label = { Text("Contraseña de Borrado", color = Color(0xFF64748B)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF111827),
                                    unfocusedContainerColor = Color(0xFF111827),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { showClearDataDialog = false },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Cancelar", color = Color.White)
                                }
                                Button(
                                    onClick = {
                                        if (clearDataPasswordInput == prefs.clearDataPassword) {
                                            showClearDataDialog = false
                                            clearApplicationData(clearDataPackageName, clearDataAppLabel)
                                        } else {
                                            Toast.makeText(this@KioskHomeActivity, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Borrar Datos", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Floating settings gear button in top-right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1F2937).copy(alpha = 0.6f))
                    .clickable { showSettingsMenu = true },
                contentAlignment = Alignment.Center
            ) {
                Text("⚙️", fontSize = 22.sp, color = Color.White)
            }

            // Settings Menu Modal Overlay
            if (showSettingsMenu) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(enabled = true) { showSettingsMenu = false },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .fillMaxWidth(0.85f)
                            .fillMaxHeight(0.85f)
                            .background(Color(0xFF111827), RoundedCornerShape(20.dp))
                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(20.dp))
                            .padding(24.dp)
                            .clickable(enabled = false) { }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = "Ajustes Rápidos",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // Wi-Fi Option
                            SettingsMenuRow(
                                icon = "📶",
                                title = "Wi-Fi",
                                description = "Configurar red inalámbrica",
                                onClick = {
                                    showSettingsMenu = false
                                    openWifiSettings()
                                }
                            )

                            HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(vertical = 8.dp))

                            // Bluetooth Option
                            SettingsMenuRow(
                                icon = "🔵",
                                title = "Bluetooth",
                                description = "Vincular y configurar dispositivos",
                                onClick = {
                                    showSettingsMenu = false
                                    openBluetoothSettings()
                                }
                            )

                            HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(vertical = 8.dp))

                            // Mobile Data Option
                            SettingsMenuRow(
                                icon = "📡",
                                title = "Datos Móviles",
                                description = "Configurar red de datos",
                                onClick = {
                                    showSettingsMenu = false
                                    openMobileDataSettings()
                                }
                            )

                            HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(vertical = 8.dp))

                            // Flashlight Option
                            SettingsMenuFlashlightRow(
                                isOn = isFlashlightOn.value,
                                onToggle = { toggleFlashlight() }
                            )

                            HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(vertical = 8.dp))

                            // Font Size Option
                            FontSizeSliderRow(
                                currentFontSize = fontSizeSp,
                                onFontSizeChange = { newSize ->
                                    prefs.fontSizeSp = newSize
                                    fontSizeState.value = newSize
                                }
                            )

                            HorizontalDivider(color = Color(0xFF1F2937), modifier = Modifier.padding(vertical = 8.dp))

                            // FLShield Settings Option
                            SettingsMenuRow(
                                icon = "⚙️",
                                title = "Configuración FLShield",
                                description = "Ajustes de la aplicación (PIN)",
                                onClick = {
                                    showSettingsMenu = false
                                    pendingAction = PendingAction.SETTINGS
                                    showPinDialog = true
                                }
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = { showSettingsMenu = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cerrar", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PinDialogComposable(
        showDialog: Boolean,
        pinInput: String,
        onPinChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onAccept: () -> Unit
    ) {
        if (showDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = true) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1F2937), RoundedCornerShape(16.dp))
                        .padding(24.dp)
                        .clickable(enabled = false) { }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Ingresa contraseña",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { onPinChange(it) },
                            label = { Text("PIN", color = Color(0xFF64748B)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF111827),
                                unfocusedContainerColor = Color(0xFF111827),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onDismiss() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Cancelar", color = Color.White)
                            }
                            Button(
                                onClick = { onAccept() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Aceptar", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsMenuRow(
        icon: String,
        title: String,
        description: String,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick() }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF1F2937), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Text("➔", color = Color(0xFF4B5563), fontSize = 16.sp)
        }
    }

    @Composable
    private fun SettingsMenuFlashlightRow(
        isOn: Boolean,
        onToggle: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggle() }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF1F2937), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isOn) "🔦" else "🔌", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Linterna", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (isOn) "Encendida" else "Apagada", color = if (isOn) Color(0xFF10B981) else Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = isOn,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF6366F1),
                    uncheckedThumbColor = Color(0xFF94A3B8),
                    uncheckedTrackColor = Color(0xFF1F2937)
                )
            )
        }
    }

    @Composable
    private fun FontSizeSliderRow(
        currentFontSize: Int,
        onFontSizeChange: (Int) -> Unit
    ) {
        var fontSize by remember(currentFontSize) { mutableStateOf(currentFontSize.toFloat()) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1F2937), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔤", fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tamaño de letra", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Ajustar tamaño del texto", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Text(
                    "${fontSize.toInt()} sp",
                    color = Color(0xFF6366F1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.Slider(
                value = fontSize,
                onValueChange = {
                    fontSize = it
                    onFontSizeChange(it.toInt())
                },
                valueRange = 10f..30f,
                steps = 19,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color(0xFF6366F1),
                    activeTrackColor = Color(0xFF6366F1),
                    inactiveTrackColor = Color(0xFF1F2937)
                )
            )
        }
    }

    private enum class PendingAction {
        SETTINGS
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AppIconItem(app: AppItem, fontSizeSp: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF111827))
            ) {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = app.label,
                color = Color(0xFFCBD5E1),
                fontSize = fontSizeSp.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                lineHeight = (fontSizeSp + 3).sp,
                modifier = Modifier.widthIn(max = 72.dp)
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadAllowedApps(): List<AppItem> {
        val pm = packageManager
        val packages = KioskAppResolver.packagesForKiosk(this, prefs)

        return packages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                val icon = pm.getApplicationIcon(pkg).toBitmapImage()
                AppItem(pkg, label, icon)
            } catch (_: PackageManager.NameNotFoundException) { null }
        }.sortedBy { it.label }
    }

    private fun launchApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun verifyHomeLauncher() {
        if (!isCurrentDefaultHome() && !homeSettingsPrompted) {
            Toast.makeText(this, "Selecciona FLShield como launcher predeterminado para mantener el kiosk activo", Toast.LENGTH_LONG).show()
            openHomeSettings()
        }
    }

    private fun isLockTaskModeActive(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            when (activityManager.lockTaskModeState) {
                ActivityManager.LOCK_TASK_MODE_LOCKED,
                ActivityManager.LOCK_TASK_MODE_PINNED -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sendRemoteBlockRequest() {
        try {
            val intent = Intent("com.example.applocker.SEND_BLOCK_REQUEST")
            sendBroadcast(intent)
            Toast.makeText(this, "Solicitud de bloqueo remoto enviada", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("KioskHome", "Error sending block request", e)
            Toast.makeText(this, "Error al enviar solicitud", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHomeSettings() {
        try {
            SettingsMonitorService.allowSettingsNavigation(30_000L)
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            homeSettingsPrompted = true
        } catch (e: Exception) {
            Log.e("KioskHome", "Unable to open home settings", e)
            Toast.makeText(this, "No se pudo abrir la configuración de launcher", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWifiSettings() {
        try {
            SettingsMonitorService.allowSettingsNavigation(30_000L)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("KioskHome", "Unable to open Wi-Fi settings", e)
            Toast.makeText(this, "No se pudo abrir Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBluetoothSettings() {
        try {
            SettingsMonitorService.allowSettingsNavigation(30_000L)
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("KioskHome", "Unable to open Bluetooth settings", e)
            Toast.makeText(this, "No se pudo abrir Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private fun exitKioskMode() {
        try {
            com.example.applocker.service.SettingsMonitorService.pauseKiosk(this, 3 * 60 * 1000L)
            stopLockTask()
            Toast.makeText(this, "Kiosko pausado por 3 minutos. Se reactivará automáticamente.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("KioskHome", "Failed to pause kiosk mode", e)
            Toast.makeText(this, "No se pudo pausar el modo kiosk", Toast.LENGTH_LONG).show()
        }
    }

    private fun isCurrentDefaultHome(): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun Drawable.toBitmapImage(): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
        val w = intrinsicWidth.coerceAtLeast(1)
        val h = intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp.asImageBitmap()
    }

    private fun getCurrentTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun getCurrentDate(): String =
        SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es")).format(Date())

    private fun sendRemoteUnlockRequest() {
        try {
            val intent = Intent("com.example.applocker.SEND_UNLOCK_REQUEST")
            sendBroadcast(intent)
            Toast.makeText(this, "Solicitud enviada", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("KioskHome", "Error sending unlock request", e)
            Toast.makeText(this, "Error al enviar solicitud", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearApplicationData(packageName: String, appLabel: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (devicePolicyManager.isDeviceOwnerApp(this.packageName)) {
                    val executor = mainExecutor
                    devicePolicyManager.clearApplicationUserData(
                        adminComponent,
                        packageName,
                        executor,
                        DevicePolicyManager.OnClearApplicationUserDataListener { _, succeeded ->
                            runOnUiThread {
                                if (succeeded) {
                                    Toast.makeText(
                                        this@KioskHomeActivity,
                                        "Datos de $appLabel borrados correctamente",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        this@KioskHomeActivity,
                                        "No se pudieron borrar los datos de $appLabel",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )
                } else {
                    Toast.makeText(
                        this,
                        "FLShield no es propietario del dispositivo (Device Owner)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Versión de Android no compatible (requiere Android 9+)",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("KioskHome", "Error clearing application user data", e)
            Toast.makeText(this, "Error al intentar borrar datos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
