package com.glassos.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * GlassOS home activity. It replaces the stock launcher (CATEGORY_HOME), draws edge-to-edge, hides the
 * native status bar and renders its own status bar, notification center, control center, apps screen,
 * edge panel and settings - all in the same liquid glass.
 *
 * `android:configChanges` in the manifest keeps this activity alive across fold / unfold and rotation;
 * [onConfigurationChanged] forwards the new bounds to [HingeSensorManager].
 */
class MainActivity : ComponentActivity() {

    private lateinit var systemState: SystemStateHolder
    private lateinit var hingeSensorManager: HingeSensorManager
    private lateinit var settings: LauncherSettings
    private lateinit var media: MediaStateHolder
    private lateinit var weather: WeatherHolder

    private var lastCollapseAt = 0L
    private var shadeReceiverRegistered = false

    /**
     * The system broadcasts ACTION_CLOSE_SYSTEM_DIALOGS (with a "reason" extra) when its shade or
     * other system UI is involved. While GlassOS is in front we answer by collapsing the native shade,
     * so top-edge swipes end up in GlassOS's own panels instead.
     */
    private val shadeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS && intent.getStringExtra("reason") != null) {
                collapseNativeShade()
            }
        }
    }

    private val homePressFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val homePressEvents: SharedFlow<Unit> = homePressFlow.asSharedFlow()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        systemState.refreshAll()
    }

    private val wallpaperPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                if (saveWallpaperFromUri(this@MainActivity, uri)) {
                    settings.onPhotoWallpaperSaved()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: our content owns every pixel, including under the camera cut-out.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        settings = LauncherSettings(this)
        media = MediaStateHolder(this)
        weather = WeatherHolder(this)
        systemState = SystemStateHolder(this).also { holder ->
            holder.windowBrightnessApplier = { value ->
                val params = window.attributes
                params.screenBrightness = if (value < 0f) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    value
                }
                window.attributes = params
            }
        }
        hingeSensorManager = HingeSensorManager(this)

        installImmersiveMode()
        requestRuntimePermissionsIfNeeded()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GlassOSRoot(
                    system = systemState,
                    hingeSensorManager = hingeSensorManager,
                    settings = settings,
                    media = media,
                    weather = weather,
                    homePressEvents = homePressEvents,
                    onPickWallpaper = {
                        wallpaperPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        systemState.start()
        systemState.refreshPermissions()
        hingeSensorManager.start()
        if (systemState.notificationAccessGranted) media.start()
        registerShadeReceiver()
        hideNativeStatusBar()
    }

    override fun onPause() {
        unregisterShadeReceiver()
        media.stop()
        hingeSensorManager.stop()
        systemState.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideNativeStatusBar()
        } else if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            // Losing focus while still resumed usually means the native shade started to expand
            // over the home screen (launching an app pauses us first, so it is not affected).
            collapseNativeShade()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hingeSensorManager.onWindowBoundsChanged()
        systemState.onConfigurationChanged(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while we are already the foreground launcher: close everything, go to page one.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePressFlow.tryEmit(Unit)
        }
    }

    private fun installImmersiveMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // If the system briefly reveals the native status bar (swipe from the top edge), hide it again
        // right away so it never lingers over the home screen.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
                view.post { hideNativeStatusBar() }
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        hideNativeStatusBar()
    }

    /**
     * Collapses the native status bar / notification shade via the hidden StatusBarManager API.
     * Android 12+ restricts this for apps targeting API 31+, and One UI may block it, so every failure
     * is swallowed; GlassOS's own top-edge handling and immersive mode still apply.
     */
    private fun collapseNativeShade() {
        val now = SystemClock.uptimeMillis()
        if (now - lastCollapseAt < 250L) return
        lastCollapseAt = now
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val collapse = statusBarManager.getMethod("collapsePanels")
            collapse.invoke(statusBarService)
        } catch (e: Exception) {
            // Safe fallback if blocked by system/OEM permissions.
        }
        hideNativeStatusBar()
    }

    private fun registerShadeReceiver() {
        if (shadeReceiverRegistered) return
        @Suppress("DEPRECATION")
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(shadeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(shadeReceiver, filter)
            }
            shadeReceiverRegistered = true
        } catch (e: Exception) {
            shadeReceiverRegistered = false
        }
    }

    private fun unregisterShadeReceiver() {
        if (!shadeReceiverRegistered) return
        try {
            unregisterReceiver(shadeReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        shadeReceiverRegistered = false
    }

    private fun hideNativeStatusBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val wanted = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, // connected-device name in the Bluetooth module
            Manifest.permission.ACCESS_FINE_LOCATION, // Wi-Fi network name
            Manifest.permission.ACCESS_COARSE_LOCATION // weather widget (must be requested with FINE)
        )
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
