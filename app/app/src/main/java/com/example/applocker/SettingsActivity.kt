package com.example.applocker

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.applocker.data.AppLockPreferences
import com.example.applocker.service.AppLockService
import com.example.applocker.theme.AppLockerTheme
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle

class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: AppLockPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppLockPreferences(this)
        com.example.applocker.service.SettingsMonitorService.isAuroraActive = true

        setContent {
            AppLockerTheme {
                SettingsScreen(
                    prefs = prefs,
                    onSave = { serverUrl, fontSize ->
                        prefs.serverUrl = serverUrl
                        prefs.fontSizeSp = fontSize.toInt()
                        // Restart WebSocket connection
                        stopService(Intent(this, AppLockService::class.java))
                        Thread.sleep(500)
                        startService(Intent(this, AppLockService::class.java).apply {
                            action = AppLockService.ACTION_START
                        })
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    prefs: AppLockPreferences,
    onSave: (String, Float) -> Unit,
    onCancel: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(prefs.serverUrl ?: "") }
    var fontSize by remember { mutableStateOf(prefs.fontSizeSp.toFloat()) }
    var error by remember { mutableStateOf("") }
    var infoMessage by remember { mutableStateOf("") }
    var newLocalPassword by remember { mutableStateOf("") }
    var confirmLocalPassword by remember { mutableStateOf("") }
    var showPinPrompt by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVol = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() }
    var volumeValue by remember { 
        mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat())
    }
    
    var systemBrightness by remember {
        val initial = try {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            127
        }
        mutableStateOf(initial.toFloat() / 255f)
    }
    
    var hasWriteSettingsPermission by remember {
        mutableStateOf(android.provider.Settings.System.canWrite(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasWriteSettingsPermission = android.provider.Settings.System.canWrite(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1E)),
        contentAlignment = Alignment.Center
    ) {
        if (showPinPrompt) {
            PinPromptDialog(
                onConfirm = { pin ->
                    if (pin == prefs.localPassword) {
                        showPinPrompt = false
                    } else {
                        error = "PIN incorrecto"
                        // no-op
                    }
                },
                onCancel = onCancel,
                error = error
            )
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .padding(32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Configuración",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    "URL del servidor",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; error = ""; infoMessage = "" },
                    placeholder = { Text("http://192.168.1.10:3000", color = Color(0xFF334155)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF6366F1)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(error, fontSize = 12.sp, color = Color(0xFFEF4444))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Font size setting
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tamaño de letra",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${fontSize.toInt()} sp",
                        fontSize = 12.sp,
                        color = Color(0xFF6366F1),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 10f..30f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF6366F1),
                        activeTrackColor = Color(0xFF6366F1),
                        inactiveTrackColor = Color(0xFF1E293B)
                    )
                )

                // Volume slider
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Volumen",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${(volumeValue / maxVol * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = Color(0xFF6366F1),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = volumeValue,
                    onValueChange = {
                        volumeValue = it
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, it.toInt(), 0)
                    },
                    valueRange = 0f..maxVol,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF6366F1),
                        activeTrackColor = Color(0xFF6366F1),
                        inactiveTrackColor = Color(0xFF1E293B)
                    )
                )

                // Brightness slider
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Brillo de pantalla",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${(systemBrightness * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = Color(0xFF6366F1),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = systemBrightness,
                    onValueChange = {
                        systemBrightness = it
                        if (hasWriteSettingsPermission) {
                            try {
                                android.provider.Settings.System.putInt(
                                    context.contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                    (it * 255).toInt()
                                )
                            } catch (_: Exception) {}
                        }
                        val window = (context as? android.app.Activity)?.window
                        if (window != null) {
                            val layoutParams = window.attributes
                            layoutParams.screenBrightness = it
                            window.attributes = layoutParams
                        }
                    },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF6366F1),
                        activeTrackColor = Color(0xFF6366F1),
                        inactiveTrackColor = Color(0xFF1E293B)
                    )
                )
                if (!hasWriteSettingsPermission) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            try {
                                com.example.applocker.service.SettingsMonitorService.allowSettingsNavigation(30_000L)
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308)),
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Conceder permiso para Brillo de Pantalla 💡", fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    "PIN local del dispositivo",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = newLocalPassword,
                    onValueChange = { newLocalPassword = it; error = ""; infoMessage = "" },
                    placeholder = { Text("Nueva contraseña local", color = Color(0xFF334155)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF6366F1)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = confirmLocalPassword,
                    onValueChange = { confirmLocalPassword = it; error = ""; infoMessage = "" },
                    placeholder = { Text("Confirmar contraseña local", color = Color(0xFF334155)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF6366F1)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    if (prefs.hasDevicePasswordOverride) {
                        "Este dispositivo usa un PIN local independiente. Cambiar aquí dará acceso solo a este dispositivo."
                    } else {
                        "El dispositivo actualmente usa la contraseña maestra enviada por el servidor."
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (newLocalPassword.isBlank() || confirmLocalPassword.isBlank()) {
                                error = "Ingresa y confirma la contraseña local"
                                return@Button
                            }
                            if (newLocalPassword != confirmLocalPassword) {
                                error = "Las contraseñas no coinciden"
                                return@Button
                            }
                            prefs.localPassword = newLocalPassword.trim()
                            newLocalPassword = ""
                            confirmLocalPassword = ""
                            infoMessage = "✅ PIN local actualizado"
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Guardar PIN")
                    }

                    if (prefs.hasDevicePasswordOverride) {
                        OutlinedButton(
                            onClick = {
                                prefs.clearDevicePasswordOverride()
                                infoMessage = "✅ PIN local reiniciado a la contraseña maestra"
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Resetear a maestra")
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(error, fontSize = 12.sp, color = Color(0xFFEF4444))
                }
                if (infoMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(infoMessage, fontSize = 12.sp, color = Color(0xFF34D399))
                }

                val context = LocalContext.current
                val hasAuroraStore = remember {
                    try {
                        context.packageManager.getPackageInfo("com.aurora.store", 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (hasAuroraStore) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            com.example.applocker.service.SettingsMonitorService.allowSettingsNavigation(60_000L)
                            val intent = context.packageManager.getLaunchIntentForPackage("com.aurora.store")
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Iniciar Aurora Store 🏪", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancelar")
                    }

                    Button(
                        onClick = {
                            val trimmed = serverUrl.trim()
                            if (trimmed.isEmpty()) {
                                error = "Ingresa una URL"
                            } else if (!trimmed.startsWith("http")) {
                                error = "La URL debe comenzar con http:// o https://"
                            } else {
                                onSave(trimmed, fontSize)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Guardar")
                    }
                }
            }
        }
    }
}

@Composable
fun PinPromptDialog(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    error: String = ""
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .background(Color(0xFF111827), RoundedCornerShape(24.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔐", fontSize = 40.sp)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Acceso restringido",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Ingresa el PIN para acceder a configuración",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.widthIn(max = 300.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            placeholder = { Text("PIN", color = Color(0xFF334155)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6366F1),
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF6366F1)
            ),
            shape = RoundedCornerShape(12.dp)
        )

        if (error.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(error, fontSize = 12.sp, color = Color(0xFFEF4444))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancelar", fontSize = 13.sp)
            }

            Button(
                onClick = { onConfirm(pin) },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continuar", fontSize = 13.sp)
            }
        }
    }
}
