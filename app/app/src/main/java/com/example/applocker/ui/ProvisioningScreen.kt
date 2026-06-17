package com.example.applocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.applocker.data.AppLockPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Composable
fun ProvisioningScreen(
    onProvisioned: (serverUrl: String, config: JSONObject, deviceKey: String?) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val prefs = AppLockPreferences(LocalContext.current)
    var urlInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<ProvStatus>(ProvStatus.Idle) }

    fun doProvision() {
        val rawUrl = urlInput.trim().trimEnd('/')
        if (rawUrl.isEmpty()) { status = ProvStatus.Error("Ingresa la URL del servidor."); return }
        if (!rawUrl.startsWith("http")) { status = ProvStatus.Error("La URL debe comenzar con http://"); return }
        status = ProvStatus.Loading
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val deviceId = prefs.deviceId
                val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
                val deviceKeyParam = prefs.deviceKey?.takeIf { it.isNotBlank() }
                    ?.let { "&deviceKey=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
                val conn = URL("$rawUrl/api/provision?deviceId=$encodedDeviceId$deviceKeyParam")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 7_000
                conn.readTimeout = 7_000
                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (code == 200) {
                    val json = JSONObject(body)
                    withContext(Dispatchers.Main) {
                        status = ProvStatus.Idle
                        onProvisioned(rawUrl, json, json.optString("deviceKey", null))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        status = ProvStatus.Error("El servidor respondió con código $code")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = ProvStatus.Error("No se pudo conectar: ${e.message}")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(36.dp)
                .widthIn(max = 420.dp)
        ) {
            // Logo
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF1A2235))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🛡️", fontSize = 40.sp)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                "FLShield",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = (-0.5).sp
            )
            Text(
                "Configuración inicial",
                fontSize = 14.sp,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Explanation card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF111827))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text("¿Cómo funciona?", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(10.dp))
                listOf(
                    "📡" to "Ingresa la URL del Centro de Control",
                    "🔗" to "El dispositivo se conecta y recibe su configuración",
                    "🔒" to "Las apps permitidas son controladas remotamente",
                    "🔑" to "El desbloqueo solo lo puede autorizar el administrador"
                ).forEach { (icon, text) ->
                    Row(
                        modifier = Modifier.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(icon, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                        Text(text, fontSize = 13.sp, color = Color(0xFF94A3B8), lineHeight = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // URL Input
            Text(
                "URL del Centro de Control",
                fontSize = 12.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("http://192.168.1.10:3000", color = Color(0xFF334155)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { doProvision() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF6366F1),
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Status
            if (status is ProvStatus.Error) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    (status as ProvStatus.Error).msg,
                    fontSize = 12.sp,
                    color = Color(0xFFEF4444),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { doProvision() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = status != ProvStatus.Loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (status == ProvStatus.Loading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Conectar y Aprovisionar", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

// Simple sealed class for provisioning status
sealed class ProvStatus {
    object Idle : ProvStatus()
    object Loading : ProvStatus()
    data class Error(val msg: String) : ProvStatus()
}
