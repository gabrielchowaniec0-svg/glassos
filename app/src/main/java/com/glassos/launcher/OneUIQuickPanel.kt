package com.glassos.launcher

import android.app.PendingIntent
import android.media.AudioManager
import android.text.format.DateUtils
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Panel state machine
// ---------------------------------------------------------------------------------------------

enum class PanelType {
    None,

    /** Opened by a swipe down on the left 60% of the screen. */
    Notifications,

    /** Opened by a swipe down on the right 40% of the screen. */
    Quick
}

/** Fraction of the screen height the panel occupies when fully open. */
fun PanelType.heightFraction(): Float = when (this) {
    PanelType.None -> 0f
    PanelType.Notifications -> 0.66f
    PanelType.Quick -> 0.84f
}

/** Space reserved above the panel for the custom status bar. */
val PanelTopMargin: Dp = 54.dp

/**
 * Owns the open/close progress shared by the pull-down gesture, the scrim, the drag handle and the
 * back button. [progress] is 0f (hidden) .. 1f (fully revealed).
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
        type = target
        progress = 0f
    }

    fun dragBy(deltaFraction: Float) {
        progress = (progress + deltaFraction).coerceIn(0f, 1f)
    }

    /** [velocityFractionPerSecond] is positive when the finger moves down (opening). */
    fun endDrag(velocityFractionPerSecond: Float) {
        val open = when {
            velocityFractionPerSecond > 1.5f -> true
            velocityFractionPerSecond < -1.5f -> false
            else -> progress > 0.5f
        }
        settle(open)
    }

    fun open(target: PanelType) {
        animationJob?.cancel()
        if (type == PanelType.None) progress = 0f
        type = target
        settle(true)
    }

    fun close() {
        if (type == PanelType.None) return
        settle(false)
    }

    private fun settle(open: Boolean) {
        animationJob?.cancel()
        animationJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
            if (!open) type = PanelType.None
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Overlay container
// ---------------------------------------------------------------------------------------------

@Composable
fun PanelOverlay(
    controller: PanelController,
    systemState: SystemStateHolder,
    modifier: Modifier = Modifier
) {
    if (controller.type == PanelType.None && controller.progress <= 0f) return

    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val rootHeightPx = constraints.maxHeight.toFloat()
        val panelType = controller.type
        val panelHeightPx = (rootHeightPx * panelType.heightFraction()).coerceAtLeast(1f)
        val panelHeight = with(density) { panelHeightPx.toDp() }
        val topMarginPx = with(density) { PanelTopMargin.toPx() }
        val latestPanelHeightPx by rememberUpdatedState(panelHeightPx)

        // Scrim: dims the blurred home screen; tapping it closes the panel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = controller.progress }
                .background(Color.Black.copy(alpha = 0.40f))
                .pointerInput(Unit) {
                    detectTapGestures { controller.close() }
                }
        )

        GlassSurface(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .offset {
                    val hiddenShift = (1f - controller.progress) * (latestPanelHeightPx + topMarginPx)
                    IntOffset(0, (topMarginPx - hiddenShift).roundToInt())
                }
                .fillMaxWidth()
                .height(panelHeight),
            cornerRadius = 38.dp,
            tint = Color(0xFF0A0F2A).copy(alpha = 0.38f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (panelType) {
                        PanelType.Quick -> QuickSettingsContent(systemState)
                        PanelType.Notifications -> NotificationTrayContent(
                            systemState = systemState,
                            onDismissPanel = { controller.close() }
                        )
                        PanelType.None -> Unit
                    }
                }
                PanelHandle(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                controller.dragBy(delta / latestPanelHeightPx)
                            },
                            onDragStopped = { velocity ->
                                controller.endDrag(velocity / latestPanelHeightPx)
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun PanelHandle(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.65f))
        )
    }
}

// ---------------------------------------------------------------------------------------------
// One UI 9.0 style Quick Settings (single page, no tabs)
// ---------------------------------------------------------------------------------------------

private val ActiveAccent = Color(0xFF4DA3FF)

private data class QuickTile(
    val label: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun QuickSettingsContent(state: SystemStateHolder) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top row: Wi-Fi and Bluetooth pills with live sub-labels.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val wifiSubLabel = when {
                !state.wifi.enabled -> "Off"
                state.wifi.connected -> state.wifi.ssid ?: "Connected"
                else -> "Not connected"
            }
            ConnectivityPill(
                title = "Wi-Fi",
                subtitle = wifiSubLabel,
                icon = if (state.wifi.enabled) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                active = state.wifi.enabled,
                onClick = { state.openWifiPanel() },
                modifier = Modifier.weight(1f)
            )
            val bluetoothSubLabel = when {
                !state.bluetooth.enabled -> "Off"
                state.bluetooth.connectedName != null -> state.bluetooth.connectedName.orEmpty()
                else -> "On"
            }
            ConnectivityPill(
                title = "Bluetooth",
                subtitle = bluetoothSubLabel,
                icon = if (state.bluetooth.enabled) Icons.Rounded.Bluetooth else Icons.Rounded.BluetoothDisabled,
                active = state.bluetooth.enabled,
                onClick = { state.openBluetoothSettings() },
                modifier = Modifier.weight(1f)
            )
        }

        // Middle: 4x2 grid of quick toggles.
        val tiles = listOf(
            QuickTile(
                label = "Flashlight",
                icon = if (state.flashlightOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                active = state.flashlightOn,
                onClick = { state.toggleFlashlight() }
            ),
            QuickTile(
                label = when (state.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> "Sound"
                    AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                    else -> "Mute"
                },
                icon = when (state.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> Icons.Rounded.NotificationsActive
                    AudioManager.RINGER_MODE_VIBRATE -> Icons.Rounded.Vibration
                    else -> Icons.Rounded.NotificationsOff
                },
                active = state.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                onClick = { state.toggleRinger() }
            ),
            QuickTile(
                label = "Airplane",
                icon = Icons.Rounded.AirplanemodeActive,
                active = state.airplaneMode,
                onClick = { state.openAirplaneSettings() }
            ),
            QuickTile(
                label = "Auto rotate",
                icon = Icons.Rounded.ScreenRotation,
                active = state.autoRotate,
                onClick = { state.toggleAutoRotate() }
            ),
            QuickTile(
                label = "Mobile data",
                icon = Icons.Rounded.SignalCellularAlt,
                active = state.mobileDataEnabled,
                onClick = { state.openInternetPanel() }
            ),
            QuickTile(
                label = "Hotspot",
                icon = Icons.Rounded.WifiTethering,
                active = false,
                onClick = { state.openHotspotSettings() }
            ),
            QuickTile(
                label = "Dark mode",
                icon = Icons.Rounded.DarkMode,
                active = state.darkMode,
                onClick = { state.openDisplaySettings() }
            ),
            QuickTile(
                label = "NFC",
                icon = Icons.Rounded.Nfc,
                active = state.nfcEnabled,
                onClick = { state.openNfcSettings() }
            )
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            tiles.chunked(4).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTiles.forEach { tile ->
                        QuickToggleTile(tile = tile, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Sliders: brightness and volume as large glass pills.
        GlassSlider(
            icon = Icons.Rounded.BrightnessMedium,
            label = "Brightness",
            value = state.brightness,
            onValueChange = { state.setBrightness(it) }
        )
        GlassSlider(
            icon = Icons.Rounded.GraphicEq,
            label = "Volume",
            value = state.volume,
            onValueChange = { state.setVolume(it) }
        )

        // Bottom: device control / quick tools bar.
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            cornerRadius = 38.dp,
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton(Icons.Rounded.Devices, "Devices") { state.openDeviceControls() }
                ToolButton(Icons.Rounded.MusicNote, "Media") { state.openMediaOutput() }
                ToolButton(Icons.Rounded.Settings, "Settings") { state.openSettings() }
            }
        }
    }
}

@Composable
private fun ConnectivityPill(
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier
            .height(78.dp)
            .squishyDeformation()
            .clickable(onClick = onClick),
        cornerRadius = 39.dp,
        tint = if (active) ActiveAccent.copy(alpha = 0.30f) else Color.Transparent,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (active) Color(0xFF0B1E3F) else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickToggleTile(tile: QuickTile, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GlassSurface(
            modifier = Modifier
                .size(68.dp)
                .squishyDeformation()
                .clickable(onClick = tile.onClick),
            cornerRadius = 24.dp,
            tint = if (tile.active) ActiveAccent.copy(alpha = 0.40f) else Color.Transparent,
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = tile.label,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = tile.label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GlassSlider(
    icon: ImageVector,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    latestOnValueChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latestOnValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        cornerRadius = 33.dp,
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = value.coerceIn(0.04f, 1f))
                .background(Color.White.copy(alpha = 0.40f))
        )
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .squishyDeformation()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Notification tray
// ---------------------------------------------------------------------------------------------

@Composable
private fun NotificationTrayContent(
    systemState: SystemStateHolder,
    onDismissPanel: () -> Unit
) {
    val notifications by NotificationRepository.items.collectAsState()
    val hasClearable = notifications.any { it.clearable }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Notifications",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            if (hasClearable) {
                GlassSurface(
                    modifier = Modifier
                        .height(36.dp)
                        .clickable { NotificationRepository.dismissAll() },
                    cornerRadius = 18.dp,
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear all",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            !systemState.notificationAccessGranted -> {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { systemState.openNotificationAccessSettings() },
                    cornerRadius = 26.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Allow notification access",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Tap to let GlassOS show your notifications in this tray.",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            notifications.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No notifications",
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 16.sp
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = notifications, key = { it.key }) { notification ->
                        NotificationCard(
                            notification = notification,
                            onOpen = {
                                try {
                                    notification.contentIntent?.send()
                                } catch (e: PendingIntent.CanceledException) {
                                    // The owning app cancelled it; nothing to launch.
                                }
                                onDismissPanel()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: GlassNotification, onOpen: () -> Unit) {
    val relativeTime = remember(notification.postTime) {
        DateUtils.getRelativeTimeSpanString(
            notification.postTime,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        cornerRadius = 26.dp
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
                        .size(34.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${notification.appName} • $relativeTime",
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (notification.title.isNotBlank()) {
                    Text(
                        text = notification.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (notification.text.isNotBlank()) {
                    Text(
                        text = notification.text,
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (notification.clearable) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { NotificationRepository.dismiss(notification.key) }
                        .padding(4.dp)
                )
            }
        }
    }
}
