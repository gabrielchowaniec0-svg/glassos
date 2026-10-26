package com.glassos.launcher

import android.app.PendingIntent
import android.media.AudioManager
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// =============================================================================================
// Panel state machine
// =============================================================================================

enum class PanelType {
    None,

    /** Swipe down on the left part of the screen: notification center (lock screen style). */
    Notifications,

    /** Swipe down on the right part of the screen: control center. */
    Quick,

    /** Swipe up on the home screen: One UI apps screen. */
    Drawer
}

/**
 * Open/close progress shared by gestures, buttons and back. [progress] is 0f (hidden) .. 1f (open).
 * All motion is spring-driven and interruptible: grabbing a moving panel continues from where it is.
 */
@Stable
class PanelController(private val scope: CoroutineScope) {

    var type by mutableStateOf(PanelType.None)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    private var animationJob: Job? = null

    val isActive: Boolean get() = type != PanelType.None

    fun beginDrag(target: PanelType) {
        animationJob?.cancel()
        if (type != target) progress = 0f
        type = target
    }

    /** Grab an already open (or animating) panel. */
    fun grab() {
        animationJob?.cancel()
    }

    fun dragBy(deltaFraction: Float) {
        progress = (progress + deltaFraction).coerceIn(0f, 1f)
    }

    /** [velocityFraction] is in "panels per second", positive towards open. */
    fun endDrag(velocityFraction: Float) {
        val open = when {
            velocityFraction > 1.1f -> true
            velocityFraction < -1.1f -> false
            else -> progress > 0.5f
        }
        settle(open)
    }

    fun open(target: PanelType) {
        animationJob?.cancel()
        if (type != target) progress = 0f
        type = target
        settle(true)
    }

    fun close() {
        if (type == PanelType.None) return
        settle(false)
    }

    fun closeImmediately() {
        animationJob?.cancel()
        progress = 0f
        type = PanelType.None
    }

    private fun settle(open: Boolean) {
        animationJob?.cancel()
        animationJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 320f)
            ) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
            if (!open) type = PanelType.None
        }
    }
}

// =============================================================================================
// Gesture exclusions (sliders and scrollable lists keep their own vertical drags)
// =============================================================================================

class GestureExclusions {

    private class Entry(var coordinates: LayoutCoordinates?, var blocks: (Float) -> Boolean)

    /** Coordinates of the node that runs the shell gestures. */
    var root: LayoutCoordinates? = null

    private val entries = HashMap<Any, Entry>()

    fun register(key: Any, coordinates: LayoutCoordinates, blocks: (Float) -> Boolean) {
        val entry = entries[key]
        if (entry == null) {
            entries[key] = Entry(coordinates, blocks)
        } else {
            entry.coordinates = coordinates
            entry.blocks = blocks
        }
    }

    fun unregister(key: Any) {
        entries.remove(key)
    }

    /** True when a registered element under [position] wants a vertical drag of direction [dy]. */
    fun blocks(position: Offset, dy: Float): Boolean {
        val rootCoordinates = root ?: return false
        if (!rootCoordinates.isAttached) return false
        return entries.values.any { entry ->
            val coordinates = entry.coordinates
            coordinates != null && coordinates.isAttached &&
                rootCoordinates.localBoundingBoxOf(coordinates).contains(position) &&
                entry.blocks(dy)
        }
    }
}

val LocalGestureExclusions = staticCompositionLocalOf { GestureExclusions() }

/** Marks this element as owning vertical drags for which [blocks] returns true. */
fun Modifier.gestureExclusion(blocks: (dy: Float) -> Boolean = { true }): Modifier = composed {
    val registry = LocalGestureExclusions.current
    val key = remember { Any() }
    val latest by rememberUpdatedState(blocks)
    DisposableEffect(registry, key) {
        onDispose { registry.unregister(key) }
    }
    onGloballyPositioned { coordinates ->
        registry.register(key, coordinates) { dy -> latest(dy) }
    }
}

/**
 * Staggered entrance for panel modules: each module fades, scales up from 86% and drops in slightly
 * after the previous one, all driven by the same spring progress (so swiping is fully interactive).
 */
fun Modifier.panelEntrance(progress: () -> Float, index: Int, fromTop: Boolean = true): Modifier =
    graphicsLayer {
        val delay = (index * 0.045f).coerceAtMost(0.5f)
        val p = ((progress() - delay) / (1f - delay)).coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p)
        alpha = eased
        val s = 0.86f + 0.14f * eased
        scaleX = s
        scaleY = s
        translationY = (if (fromTop) -1f else 1f) * (1f - eased) * 56.dp.toPx()
        transformOrigin = TransformOrigin(0.5f, if (fromTop) 0f else 1f)
    }

// =============================================================================================
// Overlay
// =============================================================================================

@Composable
fun PanelOverlay(
    controller: PanelController,
    system: SystemStateHolder,
    media: MediaStateHolder,
    settings: LauncherSettings,
    isLarge: Boolean,
    onOpenLauncherSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val type = controller.type
    if (type != PanelType.Notifications && type != PanelType.Quick) return
    val progress: () -> Float = { controller.progress }

    Box(modifier = modifier.fillMaxSize()) {
        // Dim only - the home screen underneath stays sharp and is refracted by the glass modules.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = controller.progress }
                .background(Color.Black.copy(alpha = settings.panelDim))
                .pointerInput(Unit) { detectTapGestures { controller.close() } }
        )

        if (isLarge) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .padding(top = 14.dp)
            ) {
                NotificationCenter(
                    system = system,
                    media = media,
                    progress = progress,
                    onClose = { controller.close() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Spacer(Modifier.width(32.dp))
                ControlCenter(
                    system = system,
                    media = media,
                    progress = progress,
                    onOpenLauncherSettings = onOpenLauncherSettings,
                    modifier = Modifier
                        .width(420.dp)
                        .fillMaxHeight()
                )
            }
        } else if (type == PanelType.Quick) {
            ControlCenter(
                system = system,
                media = media,
                progress = progress,
                onOpenLauncherSettings = onOpenLauncherSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
            )
        } else {
            NotificationCenter(
                system = system,
                media = media,
                progress = progress,
                onClose = { controller.close() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            )
        }
    }
}

// =============================================================================================
// Control center (iOS layout, One UI functions)
// =============================================================================================

@Composable
fun ControlCenter(
    system: SystemStateHolder,
    media: MediaStateHolder,
    progress: () -> Float,
    onOpenLauncherSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.padding(top = 10.dp)) {
        val gap = 14.dp
        val headerHeight = 96.dp
        val unitFromWidth = (min(maxWidth, 440.dp) - gap * 3) / 4
        val unitFromHeight = (maxHeight - headerHeight - gap * 5 - 24.dp) / 6
        val unit = min(unitFromWidth, unitFromHeight).coerceAtLeast(52.dp)
        val big = unit * 2 + gap

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(unit * 4 + gap * 3),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            // Top bar: edit (GlassOS settings) and system settings.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .panelEntrance(progress, 0),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassRoundButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = "GlassOS settings",
                    size = 40.dp,
                    onClick = onOpenLauncherSettings
                )
                GlassRoundButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    size = 40.dp,
                    onClick = { system.openSettings() }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .panelEntrance(progress, 0),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (system.cellular.hasTelephony) {
                        CellularBars(level = system.cellular.level, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                    }
                    WifiSignalIcon(
                        level = system.wifi.signalLevel,
                        enabled = system.wifi.enabled,
                        connected = system.wifi.connected,
                        color = Color.White
                    )
                    val carrier = system.cellular.carrier
                    if (carrier != null) {
                        Spacer(Modifier.width(8.dp))
                        GlassLabel(text = carrier, size = 13, weight = FontWeight.SemiBold)
                    }
                }
                BatteryPill(level = system.battery.level, charging = system.battery.isCharging)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                ConnectivityModule(
                    system = system,
                    size = big,
                    modifier = Modifier.panelEntrance(progress, 1)
                )
                MediaModule(
                    media = media,
                    system = system,
                    size = big,
                    modifier = Modifier.panelEntrance(progress, 2)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        GlassRoundButton(
                            icon = if (system.autoRotate) Icons.Rounded.ScreenRotation else Icons.Rounded.ScreenLockRotation,
                            contentDescription = "Rotation lock",
                            size = unit,
                            active = !system.autoRotate,
                            onClick = { system.toggleAutoRotate() },
                            modifier = Modifier.panelEntrance(progress, 3)
                        )
                        GlassRoundButton(
                            icon = if (system.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                                Icons.Rounded.NotificationsActive
                            } else {
                                Icons.Rounded.Vibration
                            },
                            contentDescription = "Sound mode",
                            size = unit,
                            active = system.ringerMode != AudioManager.RINGER_MODE_NORMAL,
                            activeColor = IosRed,
                            onLongClick = { system.openVolumePanel() },
                            onClick = { system.cycleRinger() },
                            modifier = Modifier.panelEntrance(progress, 4)
                        )
                    }
                    FocusPill(
                        on = system.dndOn,
                        width = big,
                        height = unit,
                        onClick = { system.toggleDnd() },
                        onLongClick = { system.openDndAccessSettings() },
                        modifier = Modifier.panelEntrance(progress, 5)
                    )
                }
                VerticalGlassSlider(
                    value = system.brightness,
                    onValueChange = { system.applyBrightness(it) },
                    icon = Icons.Rounded.LightMode,
                    iconTint = Color(0xFFFFB800),
                    contentDescription = "Brightness",
                    modifier = Modifier
                        .size(width = unit, height = big)
                        .panelEntrance(progress, 6)
                        .gestureExclusion()
                )
                VerticalGlassSlider(
                    value = system.volume,
                    onValueChange = { system.applyVolume(it) },
                    icon = Icons.Rounded.VolumeUp,
                    iconTint = IosBlue,
                    contentDescription = "Volume",
                    modifier = Modifier
                        .size(width = unit, height = big)
                        .panelEntrance(progress, 7)
                        .gestureExclusion()
                )
            }

            Row(
                modifier = Modifier.panelEntrance(progress, 8),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                GlassRoundButton(
                    icon = Icons.Rounded.FlashlightOn,
                    contentDescription = "Flashlight",
                    size = unit,
                    active = system.flashlightOn,
                    onClick = { system.toggleFlashlight() }
                )
                GlassRoundButton(
                    icon = Icons.Rounded.Timer,
                    contentDescription = "Timer",
                    size = unit,
                    onClick = { system.openTimer() }
                )
                GlassRoundButton(
                    icon = Icons.Rounded.Calculate,
                    contentDescription = "Calculator",
                    size = unit,
                    onClick = { system.openCalculator() }
                )
                GlassRoundButton(
                    icon = Icons.Rounded.CameraAlt,
                    contentDescription = "Camera",
                    size = unit,
                    onClick = { system.openCamera() }
                )
            }

            Row(
                modifier = Modifier.panelEntrance(progress, 9),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                GlassRoundButton(
                    icon = Icons.Rounded.DarkMode,
                    contentDescription = "Dark mode",
                    size = unit,
                    active = system.darkMode,
                    onClick = { system.openDisplaySettings() }
                )
                GlassRoundButton(
                    icon = Icons.Rounded.BatterySaver,
                    contentDescription = "Power saving",
                    size = unit,
                    active = system.powerSaveOn,
                    activeColor = IosYellow,
                    onClick = { system.openPowerSaving() }
                )
                GlassRoundButton(
                    icon = Icons.Rounded.Cast,
                    contentDescription = "Smart View",
                    size = unit,
                    onClick = { system.openCast() }
                )
                GlassRoundButton(
                    icon = Icons.Rounded.Tune,
                    contentDescription = "Modes and Routines",
                    size = unit,
                    onClick = {
                        system.launchPackage(
                            "com.samsung.android.app.routines",
                            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        )
                    }
                )
            }
        }
    }
}

/** iOS connectivity cluster: three big buttons and four small ones in one glass module. */
@Composable
private fun ConnectivityModule(
    system: SystemStateHolder,
    size: Dp,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier.size(size),
        cornerRadius = size * 0.24f
    ) {
        val pad = size * 0.085f
        val bigSize = (size - pad * 3) / 2
        val smallGap = bigSize * 0.12f
        val smallSize = (bigSize - smallGap) / 2
        Column(
            modifier = Modifier.padding(pad),
            verticalArrangement = Arrangement.spacedBy(pad)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(pad)) {
                ClusterCircle(
                    icon = Icons.Rounded.AirplanemodeActive,
                    description = "Airplane mode",
                    size = bigSize,
                    active = system.airplaneMode,
                    activeColor = IosOrange,
                    onClick = { system.openAirplaneSettings() }
                )
                ClusterCircle(
                    icon = Icons.Rounded.WifiTethering,
                    description = "Mobile Hotspot",
                    size = bigSize,
                    active = false,
                    activeColor = IosGreen,
                    onClick = { system.openHotspotSettings() }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(pad)) {
                ClusterCircle(
                    icon = Icons.Rounded.Wifi,
                    description = "Wi-Fi",
                    size = bigSize,
                    active = system.wifi.enabled,
                    activeColor = IosBlue,
                    onLongClick = { system.openWifiPanel() },
                    onClick = { system.openWifiPanel() }
                )
                Column(verticalArrangement = Arrangement.spacedBy(smallGap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(smallGap)) {
                        ClusterCircle(
                            icon = Icons.Rounded.SignalCellularAlt,
                            description = "Mobile data",
                            size = smallSize,
                            active = system.mobileDataEnabled,
                            activeColor = IosGreen,
                            onClick = { system.openInternetPanel() }
                        )
                        ClusterCircle(
                            icon = Icons.Rounded.Bluetooth,
                            description = "Bluetooth",
                            size = smallSize,
                            active = system.bluetooth.enabled,
                            activeColor = IosBlue,
                            onClick = { system.openBluetoothSettings() }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(smallGap)) {
                        ClusterCircle(
                            icon = Icons.Rounded.Nfc,
                            description = "NFC",
                            size = smallSize,
                            active = system.nfcEnabled,
                            activeColor = IosBlue,
                            onClick = { system.openNfcSettings() }
                        )
                        ClusterCircle(
                            icon = Icons.Rounded.LocationOn,
                            description = "Location",
                            size = smallSize,
                            active = system.locationOn,
                            activeColor = IosBlue,
                            onClick = { system.openLocationSettings() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterCircle(
    icon: ImageVector,
    description: String,
    size: Dp,
    active: Boolean,
    activeColor: Color,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val fill by animateColorAsState(
        targetValue = if (active) activeColor else Color.White.copy(alpha = 0.16f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "clusterFill"
    )
    Box(
        modifier = Modifier
            .size(size)
            .glassClickable(pressedScale = 1.1f, onLongClick = onLongClick, onClick = onClick)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(size * 0.48f)
        )
    }
}

/** Now Playing module (square). */
@Composable
private fun MediaModule(
    media: MediaStateHolder,
    system: SystemStateHolder,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val info = media.info
    GlassSurface(
        modifier = modifier
            .size(size)
            .glassClickable(pressedScale = 1.03f) { media.openPlayer(system) },
        cornerRadius = size * 0.24f
    ) {
        val pad = size * 0.085f
        Column(modifier = Modifier.padding(pad)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                val artSize = size * 0.36f
                val art = info?.artwork
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = "Artwork",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(artSize * 0.18f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(artSize * 0.18f))
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(artSize * 0.5f)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(size * 0.2f)
                        .glassClickable { system.openVolumePanel() }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneAndroid,
                        contentDescription = "Output",
                        tint = Color.White,
                        modifier = Modifier.size(size * 0.11f)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            GlassLabel(
                text = info?.title?.takeIf { it.isNotBlank() } ?: "Not Playing",
                size = 15,
                weight = FontWeight.SemiBold
            )
            GlassLabel(
                text = info?.artist ?: "",
                size = 13,
                alpha = 0.7f
            )
            Spacer(Modifier.height(size * 0.04f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaButton(Icons.Rounded.SkipPrevious, "Previous", size * 0.16f) { media.previous() }
                MediaButton(
                    if (info?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Play or pause",
                    size * 0.2f
                ) { media.playPause() }
                MediaButton(Icons.Rounded.SkipNext, "Next", size * 0.16f) { media.next() }
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = Color.White,
        modifier = Modifier
            .size(size)
            .glassClickable(pressedScale = 1.2f, onClick = onClick)
    )
}

/** Do Not Disturb pill (iOS "Focus"). */
@Composable
private fun FocusPill(
    on: Boolean,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val circleFill by animateColorAsState(
        targetValue = if (on) IosIndigo else Color.White.copy(alpha = 0.16f),
        label = "focusFill"
    )
    GlassSurface(
        modifier = modifier
            .size(width = width, height = height)
            .glassClickable(pressedScale = 1.05f, onLongClick = onLongClick, onClick = onClick),
        cornerRadius = GlassCapsule,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = height * 0.16f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(height * 0.68f)
                    .clip(CircleShape)
                    .background(circleFill),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (on) Icons.Rounded.DoNotDisturbOn else Icons.Rounded.DarkMode,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(height * 0.36f)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                GlassLabel(text = "Do Not Disturb", size = 14, weight = FontWeight.SemiBold)
                GlassLabel(text = if (on) "On" else "Off", size = 12, alpha = 0.7f)
            }
        }
    }
}

// =============================================================================================
// Notification center (lock-screen style)
// =============================================================================================

@Composable
fun NotificationCenter(
    system: SystemStateHolder,
    media: MediaStateHolder,
    progress: () -> Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notifications by NotificationRepository.items.collectAsState()
    val groups = remember(notifications) {
        notifications.groupBy { it.packageName }.values.toList()
    }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .padding(top = 44.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        LockScreenClock(modifier = Modifier
            .fillMaxWidth()
            .panelEntrance(progress, 0))

        Spacer(Modifier.height(12.dp))

        val nowPlaying = media.info
        if (nowPlaying != null) {
            NowPlayingCard(
                info = nowPlaying,
                media = media,
                system = system,
                modifier = Modifier
                    .fillMaxWidth()
                    .panelEntrance(progress, 1)
            )
            Spacer(Modifier.height(12.dp))
        }

        when {
            !system.notificationAccessGranted -> {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .panelEntrance(progress, 2)
                        .glassClickable(pressedScale = 1.03f) { system.openNotificationAccessSettings() },
                    cornerRadius = 26.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        GlassLabel("Allow notification access", size = 16, weight = FontWeight.SemiBold)
                        VerticalSpace(4.dp)
                        GlassLabel(
                            "Tap to let GlassOS show notifications and music here.",
                            size = 13,
                            alpha = 0.8f,
                            maxLines = 2
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            groups.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .panelEntrance(progress, 2),
                    contentAlignment = Alignment.Center
                ) {
                    GlassLabel("No Notifications", size = 16, weight = FontWeight.SemiBold, alpha = 0.8f)
                }
            }

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .panelEntrance(progress, 2),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassLabel("Notification Centre", size = 18, weight = FontWeight.Bold)
                    if (notifications.any { it.clearable }) {
                        GlassSurface(
                            modifier = Modifier
                                .height(32.dp)
                                .glassClickable { NotificationRepository.dismissAll() },
                            cornerRadius = GlassCapsule,
                            contentAlignment = Alignment.Center
                        ) {
                            GlassLabel(
                                "Clear",
                                size = 13,
                                weight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .gestureExclusion { dy ->
                            (dy < 0f && listState.canScrollForward) || (dy > 0f && listState.canScrollBackward)
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groups.forEachIndexed { groupIndex, group ->
                        val packageName = group.first().packageName
                        val isExpanded = packageName in expanded || group.size == 1
                        if (isExpanded) {
                            items(items = group, key = { it.key }) { notification ->
                                SwipeToDismiss(
                                    enabled = notification.clearable,
                                    onDismiss = { NotificationRepository.dismiss(notification.key) },
                                    modifier = Modifier.panelEntrance(progress, 3 + groupIndex)
                                ) {
                                    NotificationCard(
                                        notification = notification,
                                        stackedBehind = 0,
                                        extraCount = 0,
                                        onClick = {
                                            sendNotification(notification)
                                            onClose()
                                        }
                                    )
                                }
                            }
                            if (group.size > 1) {
                                item(key = "collapse_$packageName") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        GlassSurface(
                                            modifier = Modifier
                                                .height(28.dp)
                                                .glassClickable { expanded = expanded - packageName },
                                            cornerRadius = GlassCapsule,
                                            contentAlignment = Alignment.Center
                                        ) {
                                            GlassLabel(
                                                "Show less",
                                                size = 12,
                                                weight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val latest = group.first()
                            item(key = "group_$packageName") {
                                SwipeToDismiss(
                                    enabled = group.any { it.clearable },
                                    onDismiss = { group.filter { it.clearable }.forEach { NotificationRepository.dismiss(it.key) } },
                                    modifier = Modifier.panelEntrance(progress, 3 + groupIndex)
                                ) {
                                    NotificationCard(
                                        notification = latest,
                                        stackedBehind = (group.size - 1).coerceAtMost(2),
                                        extraCount = group.size - 1,
                                        onClick = { expanded = expanded + packageName }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Lock-screen style shortcuts.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 14.dp)
                .panelEntrance(progress, 6, fromTop = false),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GlassRoundButton(
                icon = Icons.Rounded.FlashlightOn,
                contentDescription = "Flashlight",
                size = 52.dp,
                active = system.flashlightOn,
                onClick = { system.toggleFlashlight() }
            )
            GlassRoundButton(
                icon = Icons.Rounded.CameraAlt,
                contentDescription = "Camera",
                size = 52.dp,
                onClick = {
                    system.openCamera()
                    onClose()
                }
            )
        }
    }
}

private fun sendNotification(notification: GlassNotification) {
    try {
        notification.contentIntent?.send()
    } catch (e: PendingIntent.CanceledException) {
        // The owning app cancelled it; nothing to launch.
    }
}

/** Big date plus liquid glass numerals, like the iOS lock screen. */
@Composable
private fun LockScreenClock(modifier: Modifier = Modifier) {
    val now by rememberMinuteClock()
    val use24Hour = rememberUse24Hour()
    val date = remember(now) { SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(now) }
    val time = formatTime(now, use24Hour)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassLabel(date, size = 20, weight = FontWeight.SemiBold, alpha = 0.9f)
        Box(contentAlignment = Alignment.Center) {
            // Glass body: translucent vertical gradient.
            Text(
                text = time,
                style = TextStyle(
                    fontSize = 96.sp,
                    fontWeight = FontWeight.SemiBold,
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.38f))
                    ),
                    shadow = GlassTextShadow
                )
            )
            // Specular edge: thin bright outline catching the light.
            Text(
                text = time,
                style = TextStyle(
                    fontSize = 96.sp,
                    fontWeight = FontWeight.SemiBold,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.7f))
                    ),
                    drawStyle = Stroke(width = 2.2f)
                )
            )
        }
    }
}

@Composable
private fun NowPlayingCard(
    info: MediaInfo,
    media: MediaStateHolder,
    system: SystemStateHolder,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier
            .height(84.dp)
            .glassClickable(pressedScale = 1.02f) { media.openPlayer(system) },
        cornerRadius = 28.dp,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val art = info.artwork
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = "Artwork",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                GlassLabel(info.title, size = 15, weight = FontWeight.SemiBold)
                GlassLabel(info.artist, size = 13, alpha = 0.7f)
            }
            MediaButton(Icons.Rounded.SkipPrevious, "Previous", 30.dp) { media.previous() }
            Spacer(Modifier.width(8.dp))
            MediaButton(
                if (info.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                "Play or pause",
                36.dp
            ) { media.playPause() }
            Spacer(Modifier.width(8.dp))
            MediaButton(Icons.Rounded.SkipNext, "Next", 30.dp) { media.next() }
        }
    }
}

/** Horizontal swipe to dismiss with spring snap-back. */
@Composable
private fun SwipeToDismiss(
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val w = size.width.coerceAtLeast(1f)
                alpha = 1f - (abs(offset.value) / w).coerceIn(0f, 1f) * 0.8f
            }
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val w = size.width.toFloat()
                        if (abs(offset.value) > w * 0.38f) {
                            scope.launch {
                                offset.animateTo(if (offset.value > 0) w * 1.2f else -w * 1.2f, tween(180))
                                latestDismiss()
                                offset.snapTo(0f)
                            }
                        } else {
                            scope.launch {
                                offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.6f)) }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + amount) }
                    }
                )
            }
    ) {
        content()
    }
}

@Composable
private fun NotificationCard(
    notification: GlassNotification,
    stackedBehind: Int,
    extraCount: Int,
    onClick: () -> Unit
) {
    val relativeTime = remember(notification.postTime) {
        DateUtils.getRelativeTimeSpanString(
            notification.postTime,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = (7 * stackedBehind).dp)
    ) {
        // iOS-style stack slivers peeking out below a collapsed group.
        for (i in stackedBehind downTo 1) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = (12 * i).dp)
                    .offset(y = (7 * i).dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.09f))
            )
        }
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .glassClickable(pressedScale = 1.02f, onClick = onClick),
            cornerRadius = 24.dp
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                val icon = notification.icon
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = notification.appName,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassLabel(
                            text = notification.title.ifBlank { notification.appName },
                            size = 15,
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        GlassLabel(text = relativeTime, size = 12, alpha = 0.65f)
                    }
                    if (notification.text.isNotBlank()) {
                        Text(
                            text = notification.text,
                            style = glassText(TextStyle(fontSize = 14.sp)),
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (extraCount > 0) {
                        GlassLabel(
                            text = "$extraCount more from ${notification.appName}",
                            size = 12,
                            alpha = 0.65f,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Provides a backdrop override for a subtree (used by overlays that should refract the home screen). */
@Composable
fun WithBackdrop(backdrop: Backdrop, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        content()
    }
}
