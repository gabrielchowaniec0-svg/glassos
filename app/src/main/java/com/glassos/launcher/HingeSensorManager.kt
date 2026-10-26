package com.glassos.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the Galaxy Z Fold 6 unfold transition.
 *
 * With a hinge sensor ([Sensor.TYPE_HINGE_ANGLE], sensor type 36) the raw hinge angle is mapped onto
 * [unfoldProgress]: 0.0 at 60 degrees (partially open) up to 1.0 at 180 degrees (flat), smoothed with a
 * smoothstep curve. Without the sensor (Galaxy S25 Ultra, emulators, or a failed registration) the
 * listener is unregistered and the transition falls back to window bounds changes reported by
 * [WindowMetricsCalculator].
 *
 * The transition only applies while the window is "large" (inner display, >= 600dp wide), so the
 * narrow cover display never gets scaled or blurred while the hinge is closed.
 */
class HingeSensorManager(private val activity: ComponentActivity) : SensorEventListener {

    companion object {
        /** Sensor.TYPE_HINGE_ANGLE == 36. */
        const val HINGE_SENSOR_TYPE: Int = Sensor.TYPE_HINGE_ANGLE
        const val PARTIAL_OPEN_DEGREES: Float = 60f
        const val FLAT_DEGREES: Float = 180f
        const val LARGE_SCREEN_MIN_WIDTH_DP: Float = 600f
        private const val FALLBACK_START_PROGRESS: Float = 0.35f
        private const val FALLBACK_DURATION_MS: Long = 520L
    }

    private val sensorManager: SensorManager =
        activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hingeSensor: Sensor? = sensorManager.getDefaultSensor(HINGE_SENSOR_TYPE)

    /** True when this device exposes a hinge angle sensor. */
    val hasHingeSensor: Boolean get() = hingeSensor != null

    private var sensorActive = false
    private var fallbackAnimator: ValueAnimator? = null

    private val hingeAngleFlow = MutableStateFlow(FLAT_DEGREES)
    val hingeAngle: StateFlow<Float> = hingeAngleFlow.asStateFlow()

    private val windowBoundsFlow = MutableStateFlow(currentBounds())
    val windowBounds: StateFlow<Rect> = windowBoundsFlow.asStateFlow()

    private val unfoldProgressFlow = MutableStateFlow(1f)

    /** 0f = partially open (60 degrees), 1f = fully flat (180 degrees) or not applicable. */
    val unfoldProgress: StateFlow<Float> = unfoldProgressFlow.asStateFlow()

    private var lastWasLarge: Boolean = isLarge(windowBoundsFlow.value)

    fun start() {
        val sensor = hingeSensor
        sensorActive = if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            false
        }
        if (!sensorActive) {
            // No hinge sensor (S25 Ultra) or registration refused: make sure nothing stays registered
            // and rely on window bounds changes instead.
            sensorManager.unregisterListener(this)
        }
        onWindowBoundsChanged(animate = false)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sensorActive = false
        fallbackAnimator?.cancel()
        fallbackAnimator = null
    }

    /** Call from Activity.onConfigurationChanged (the activity is never recreated on fold/unfold). */
    fun onWindowBoundsChanged(animate: Boolean = true) {
        val bounds = currentBounds()
        windowBoundsFlow.value = bounds
        val large = isLarge(bounds)

        if (sensorActive) {
            fallbackAnimator?.cancel()
            recomputeFromSensor()
        } else if (animate && large && !lastWasLarge) {
            animateFallbackUnfold()
        } else {
            fallbackAnimator?.cancel()
            unfoldProgressFlow.value = 1f
        }
        lastWasLarge = large
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != HINGE_SENSOR_TYPE || event.values.isEmpty()) return
        hingeAngleFlow.value = event.values[0]
        recomputeFromSensor()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun recomputeFromSensor() {
        if (!isLarge(windowBoundsFlow.value)) {
            unfoldProgressFlow.value = 1f
            return
        }
        val linear = ((hingeAngleFlow.value - PARTIAL_OPEN_DEGREES) /
            (FLAT_DEGREES - PARTIAL_OPEN_DEGREES)).coerceIn(0f, 1f)
        unfoldProgressFlow.value = linear * linear * (3f - 2f * linear)
    }

    private fun animateFallbackUnfold() {
        fallbackAnimator?.cancel()
        fallbackAnimator = ValueAnimator.ofFloat(FALLBACK_START_PROGRESS, 1f).apply {
            duration = FALLBACK_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { unfoldProgressFlow.value = it.animatedValue as Float }
            start()
        }
    }

    private fun currentBounds(): Rect =
        WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds

    private fun isLarge(bounds: Rect): Boolean {
        val density = activity.resources.displayMetrics.density
        return bounds.width() / density >= LARGE_SCREEN_MIN_WIDTH_DP
    }
}
