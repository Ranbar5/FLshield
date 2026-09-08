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
    private val onLocalUnlockSuccess: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: FrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    fun isShowing(): Boolean = overlayContainer != null && overlayContainer?.isAttachedToWindow == true

    fun show(): Boolean {
        if (overlayContainer != null && overlayContainer?.isAttachedToWindow == false) {
            dismiss()
        }
        if (isShowing()) return true

        try {
            val container = FrameLayout(context)

            // Set up lifecycle owners for ComposeView inside WindowManager
            val lifecycleOwner = OverlayLifecycleOwner()
            lifecycleOwner.onCreate()
            lifecycleOwner.onStart()
            lifecycleOwner.onResume()
            this.lifecycleOwner = lifecycleOwner

            container.setViewTreeLifecycleOwner(lifecycleOwner)
            container.setViewTreeViewModelStoreOwner(lifecycleOwner)
            container.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            val composeView = ComposeView(context).apply {
                setContent {
                    LockOverlayScreen(
                        getLocalPassword = getLocalPassword,
                        onLocalUnlock = onLocalUnlockSuccess
                    )
                }
            }
            container.addView(composeView)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            windowManager.addView(container, params)
            overlayContainer = container
            return true
        } catch (e: Exception) {
            Log.e("LockOverlayHelper", "Failed to show lock overlay", e)
            dismiss()
            return false
        }
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
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
fun LockOverlayScreen(
    getLocalPassword: () -> String,
    onLocalUnlock: () -> Unit
) {
    var patternError by remember { mutableStateOf(false) }
    val expectedPassword = getLocalPassword().trim()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            // Lock icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🔒", fontSize = 32.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Acceso Restringido",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Dibuja el patrón para desbloquear",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Local Pattern input ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .background(Color(0xFF111827), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                    .padding(10.dp)
            ) {
                PatternLockView(
                    expectedPattern = expectedPassword,
                    onSuccess = {
                        patternError = false
                        onLocalUnlock()
                    },
                    onError = {
                        patternError = true
                    }
                )
            }

            if (patternError) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Patrón incorrecto",
                    fontSize = 12.sp,
                    color = Color(0xFFEF4444),
                    fontWeight = FontWeight.SemiBold
                )
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
