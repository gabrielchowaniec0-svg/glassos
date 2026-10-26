package com.glassos.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * GlassOS home activity. It replaces the stock launcher (CATEGORY_HOME), draws edge-to-edge, hides the
 * native status bar and renders its own liquid glass status bar, pull-down notification tray and
 * Quick Settings panel instead.
 *
 * `android:configChanges` in the manifest keeps this activity alive across fold / unfold and rotation;
 * [onConfigurationChanged] forwards the new bounds to [HingeSensorManager].
 */
class MainActivity : ComponentActivity() {

    private lateinit var systemState: SystemStateHolder
    private lateinit var hingeSensorManager: HingeSensorManager

    private val homePressFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val homePressEvents: SharedFlow<Unit> = homePressFlow.asSharedFlow()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        systemState.refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: our content owns every pixel, including under the camera cut-out.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

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
                    systemState = systemState,
                    hingeSensorManager = hingeSensorManager,
                    homePressEvents = homePressEvents
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        systemState.start()
        hingeSensorManager.start()
        hideNativeStatusBar()
    }

    override fun onPause() {
        hingeSensorManager.stop()
        systemState.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNativeStatusBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hingeSensorManager.onWindowBoundsChanged()
        systemState.onConfigurationChanged(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while we are already the foreground launcher: close panels, go to page one.
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

    private fun hideNativeStatusBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val wanted = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, // connected-device name in the Bluetooth pill
            Manifest.permission.ACCESS_FINE_LOCATION // lets Android reveal the Wi-Fi network name
        )
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
