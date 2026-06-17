package com.example.applocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.applocker.KioskHomeActivity
import com.example.applocker.data.AppLockPreferences
import com.example.applocker.service.AppLockService
import com.example.applocker.service.SettingsMonitorService
import com.example.applocker.theme.AppLockerTheme
import com.example.applocker.ui.ProvisioningScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppLockPreferences
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = AppLockPreferences(this)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AppLockerDeviceAdminReceiver::class.java)

        setContent {
            AppLockerTheme {
                MainFlow()
            }
        }
    }

    @Composable
    private fun MainFlow() {
        var isProvisioned by remember { mutableStateOf(prefs.isProvisioned) }
        var autoProvError by remember { mutableStateOf<String?>(null) }
        var isAutoProvisioning by remember { mutableStateOf(false) }

        val serverUrl = prefs.serverUrl

        if (!isProvisioned) {
            if (!serverUrl.isNullOrBlank()) {
                // Auto-provisioning flow (QR code enrollment)
                LaunchedEffect(Unit) {
                    isAutoProvisioning = true
                    autoProvError = null
                    try {
                        val deviceId = prefs.deviceId
                        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
                        val deviceKeyParam = prefs.deviceKey?.takeIf { it.isNotBlank() }
                            ?.let { "&deviceKey=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
                        
                        val rawUrl = serverUrl.trim().trimEnd('/')
                        val url = URL("$rawUrl/api/provision?deviceId=$encodedDeviceId$deviceKeyParam")
                        
                        val result = withContext(Dispatchers.IO) {
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 10_000
                            conn.readTimeout = 10_000
                            val code = conn.responseCode
                            if (code == 200) {
                                val body = conn.inputStream.bufferedReader().readText()
                                conn.disconnect()
                                JSONObject(body)
                            } else {
                                conn.disconnect()
                                null
                            }
                        }
                        
                        if (result != null) {
                            prefs.applyServerConfig(result)
                            val key = result.optString("deviceKey", null)
                            key?.takeIf { it.isNotBlank() }?.let { prefs.deviceKey = it }
                            prefs.isProvisioned = true
                            isProvisioned = true
                            startLockService()
                        } else {
                            autoProvError = "El servidor no respondió correctamente."
                        }
                    } catch (e: Exception) {
                        autoProvError = "Error de conexión: ${e.message}"
                    } finally {
                        isAutoProvisioning = false
                    }
                }

                // Auto-provisioning splash screen
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0A0F1E)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(36.dp)
                    ) {
                        Text("🛡️", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Configurando dispositivo...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            "Conectando a $serverUrl",
                            color = Color(0xFF64748B),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        if (isAutoProvisioning) {
                            CircularProgressIndicator(color = Color(0xFF6366F1))
                        } else if (autoProvError != null) {
                            Text(
                                autoProvError!!,
                                color = Color(0xFFEF4444),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { recreate() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("Reintentar", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = {
                                prefs.serverUrl = null
                                recreate()
                            }) {
                                Text("Configuración Manual", color = Color(0xFF94A3B8))
                            }
                        }
                    }
                }
            } else {
                ProvisioningScreen { sUrl, config, deviceKey ->
                    prefs.serverUrl = sUrl
                    prefs.applyServerConfig(config)
                    deviceKey?.takeIf { it.isNotBlank() }?.let { prefs.deviceKey = it }
                    prefs.isProvisioned = true
                    isProvisioned = true
                    startLockService()
                }
            }
        } else {
            LaunchedEffect(Unit) {
                startLockService()
            }
            DashboardScreen()
        }
    }

    @Composable
    private fun DashboardScreen() {
        val hasOverlayPerm = remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        val hasUsagePerm = remember { mutableStateOf(checkUsageStatsPermission()) }
        var kioskStarted by remember { mutableStateOf(false) }

        LaunchedEffect(hasOverlayPerm.value, hasUsagePerm.value, prefs.isProvisioned) {
            if (prefs.isProvisioned && hasOverlayPerm.value && hasUsagePerm.value && !kioskStarted) {
                kioskStarted = true
                startKioskHome()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0F1E))
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text("🛡️", fontSize = 44.sp)
                Text(
                    "FLShield",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    prefs.serverUrl ?: "Sin servidor",
                    fontSize = 12.sp,
                    color = Color(0xFF4ADE80),
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Permission cards
                PermCard(
                    icon = "🪟",
                    title = "Permiso de superposición",
                    granted = hasOverlayPerm.value,
                    onRequest = {
                        openSettingsScreen(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                PermCard(
                    icon = "📊",
                    title = "Acceso a estadísticas de uso",
                    granted = hasUsagePerm.value,
                    onRequest = {
                        openSettingsScreen(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                AccessibilityCard()
                Spacer(modifier = Modifier.height(12.dp))
                DeviceAdminCard()

                Spacer(modifier = Modifier.height(28.dp))

                // Start / Stop
                val allGranted = hasOverlayPerm.value && hasUsagePerm.value
                Button(
                    onClick = { startLockService() },
                    enabled = allGranted,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("▶  Activar Protección", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { stopLockService() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("⏹  Detener Servicio")
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Reset provisioning
                TextButton(onClick = {
                    prefs.isProvisioned = false
                    prefs.serverUrl = null
                    stopLockService()
                    recreate()
                }) {
                    Text("Cambiar servidor de control", color = Color(0xFF475569), fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun PermCard(icon: String, title: String, granted: Boolean, onRequest: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (granted) Color(0xFF1B3A2C) else Color(0xFF3A1B1B), RoundedCornerShape(14.dp))
                .background(if (granted) Color(0xFF0D1F18) else Color(0xFF1F0D0D), RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(icon, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (granted) "Concedido ✓" else "Requerido — toca para activar",
                        color = if (granted) Color(0xFF4ADE80) else Color(0xFFF87171),
                        fontSize = 12.sp
                    )
                }
            }
            if (!granted) {
                TextButton(onClick = onRequest) {
                    Text("Activar", color = Color(0xFF6366F1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun AccessibilityCard() {
        val isEnabled = remember { mutableStateOf(isAccessibilityEnabled()) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp,
                    if (isEnabled.value) Color(0xFF1B3A2C) else Color(0xFF3A2A1B),
                    RoundedCornerShape(14.dp))
                .background(
                    if (isEnabled.value) Color(0xFF0D1F18) else Color(0xFF1F160D),
                    RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("♿", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Servicio de Accesibilidad", color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (isEnabled.value) "Activado — Fecha/GPS protegidos"
                        else "Recomendado — protege Fecha y GPS en Ajustes",
                        color = if (isEnabled.value) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                        fontSize = 12.sp
                    )
                }
            }
            if (!isEnabled.value) {
                TextButton(onClick = {
                    openSettingsScreen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Activar", color = Color(0xFFF59E0B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    private fun startLockService() {
        startService(Intent(this, AppLockService::class.java).apply {
            action = AppLockService.ACTION_START
        })
    }

    private fun stopLockService() {
        startService(Intent(this, AppLockService::class.java).apply {
            action = AppLockService.ACTION_STOP
        })
    }

    private fun startKioskHome() {
        startActivity(Intent(this, KioskHomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun checkUsageStatsPermission(): Boolean {
        val statsManager = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = statsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY, end - 1000 * 60, end
        )
        return stats != null && stats.isNotEmpty()
    }

    @Composable
    private fun DeviceAdminCard() {
        val isActive = devicePolicyManager.isAdminActive(adminComponent)
        val isOwner = devicePolicyManager.isDeviceOwnerApp(packageName)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isActive) Color(0xFF1B3A2C) else Color(0xFF3A1B1B), RoundedCornerShape(14.dp))
                .background(if (isActive) Color(0xFF0D1F18) else Color(0xFF1F0D0D), RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("🛡️", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Administrador de dispositivo", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (isActive) "Activo — modo kiosko disponible" else "Requiere activación para kiosk mode",
                        color = if (isActive) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                        fontSize = 12.sp
                    )
                    if (!isOwner) {
                        Text("Para activar el kiosk mode completo, instala como Device Owner usando ADB o perfil de empresa.",
                            color = Color(0xFF94A3B8), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (!isActive) {
                TextButton(onClick = { requestDeviceAdmin() }) {
                    Text("Activar", color = Color(0xFF6366F1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description))
        }
        openSettingsScreen(intent)
    }

    private fun openSettingsScreen(intent: Intent) {
        SettingsMonitorService.allowSettingsNavigation()
        startActivity(intent)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${com.example.applocker.service.SettingsMonitorService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }

    override fun onResume() {
        super.onResume()
    }
}
