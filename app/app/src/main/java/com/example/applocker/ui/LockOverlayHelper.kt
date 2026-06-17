package com.example.applocker.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Displays a full-screen overlay over blocked apps.
 * Supports local PIN unlock + remote unlock request.
 */
class LockOverlayHelper(
    private val context: Context,
    private val getLocalPassword: () -> String,
    private val onLocalUnlockSuccess: () -> Unit,
    private val onUnlockRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: FrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    // State driven from the service (waiting / granted / denied)
    var unlockState = mutableStateOf<UnlockState>(UnlockState.Idle)

    enum class UnlockState { Idle, Waiting, Granted, Denied }

    fun isShowing(): Boolean = false

    fun show(): Boolean {
        Log.d("LockOverlayHelper", "show() called but overlays are disabled.")
        return false
    }

    fun dismiss() {
        overlayContainer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayContainer = null
        }
        lifecycleOwner?.let {
            it.onPause(); it.onStop(); it.onDestroy()
            lifecycleOwner = null
        }
        unlockState.value = UnlockState.Idle
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
fun LockOverlayScreen(
    state: LockOverlayHelper.UnlockState,
    getLocalPassword: () -> String,
    onLocalUnlock: () -> Unit,
    onRequestUnlock: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val expectedPassword = getLocalPassword().trim()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp)
        ) {
            // Lock icon
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🔒", fontSize = 38.sp)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Acceso Restringido",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Ingresa la contraseña para desbloquear",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── Local PIN input ──────────────────────────────────────────
            OutlinedTextField(
                value = pinInput,
                onValueChange = {
                    pinInput = it
                    pinError = false
                },
                placeholder = { Text("Contraseña", color = Color(0xFF334155)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                                if (pinInput.trim() == expectedPassword) {
                            onLocalUnlock()
                        } else {
                            pinError = true
                            pinInput = ""
                        }
                    }
                ),
                singleLine = true,
                isError = pinError,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF1E293B),
                    errorBorderColor = Color(0xFFEF4444),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF6366F1),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (pinError) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Contraseña incorrecta",
                    fontSize = 12.sp,
                    color = Color(0xFFEF4444)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (pinInput.trim() == expectedPassword) {
                        onLocalUnlock()
                    } else {
                        pinError = true
                        pinInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🔓  Desbloquear", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Divider ──────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF1E293B))
                Text(
                    "  o  ",
                    fontSize = 11.sp,
                    color = Color(0xFF475569)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF1E293B))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Remote unlock section ────────────────────────────────────
            when (state) {
                LockOverlayHelper.UnlockState.Idle -> {
                    TextButton(
                        onClick = onRequestUnlock,
                    ) {
                        Text(
                            "📡  Solicitar desbloqueo remoto",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                }
                LockOverlayHelper.UnlockState.Waiting -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF6366F1),
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            "Esperando aprobación...",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                LockOverlayHelper.UnlockState.Granted -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✅ ", fontSize = 18.sp)
                        Text("Acceso concedido", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                    }
                }
                LockOverlayHelper.UnlockState.Denied -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚫 Acceso denegado",
                            color = Color(0xFFEF4444), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRequestUnlock) {
                            Text("Volver a solicitar", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Lifecycle owner wrapper for standalone Compose in WindowManager ────────────

class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    private val vms = ViewModelStore()

    fun onCreate()  { registry.currentState = Lifecycle.State.CREATED }
    fun onStart()   { registry.currentState = Lifecycle.State.STARTED }
    fun onResume()  { registry.currentState = Lifecycle.State.RESUMED }
    fun onPause()   { registry.currentState = Lifecycle.State.STARTED }
    fun onStop()    { registry.currentState = Lifecycle.State.CREATED }
    fun onDestroy() { registry.currentState = Lifecycle.State.DESTROYED; vms.clear() }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vms

    init { ssc.performRestore(null) }
}
