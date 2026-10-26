package com.glassos.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * GlassOS Settings: an iOS Settings style page of grouped liquid glass cards. Opens from the
 * control center "+" button or a long press on an empty part of the home screen.
 */
@Composable
fun GlassSettingsScreen(
    progress: () -> Float,
    settings: LauncherSettings,
    system: SystemStateHolder,
    onPickWallpaper: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress()
                alpha = p
                translationY = (1f - p) * 90.dp.toPx()
            }
            .background(Color.Black.copy(alpha = 0.18f)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 52.dp, bottom = 12.dp, start = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassLabel("Settings", size = 34, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GlassSurface(
                    modifier = Modifier
                        .height(40.dp)
                        .glassClickable(onClick = onClose),
                    cornerRadius = GlassCapsule,
                    contentAlignment = Alignment.Center
                ) {
                    GlassLabel(
                        "Done",
                        size = 15,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Appearance")
                SettingsCard {
                    SettingLabel("App icons", "Clear glass uses refracting liquid glass tiles")
                    GlassSegmented(
                        options = IconStyle.entries.map { it.label },
                        selectedIndex = settings.iconStyle.ordinal,
                        onSelect = { settings.updateIconStyle(IconStyle.entries[it]) }
                    )
                    if (settings.iconStyle == IconStyle.Tinted) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LauncherSettings.TINT_SWATCHES.forEach { argb ->
                                val selected = settings.tintColor == argb
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .glassClickable(pressedScale = 1.15f) { settings.updateTintColor(argb) }
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .then(
                                            if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier
                                        )
                                )
                            }
                        }
                    }
                    Divider()
                    SettingLabel("Wallpaper", null)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WallpaperPreset.entries.filter { it != WallpaperPreset.Photo }.forEach { preset ->
                            val selected = settings.wallpaper == preset
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 46.dp, height = 70.dp)
                                        .glassClickable(pressedScale = 1.08f) { settings.updateWallpaper(preset) }
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .background(Brush.verticalGradient(preset.previewColors()))
                                        .then(
                                            if (selected) {
                                                Modifier.border(
                                                    2.5.dp,
                                                    Color.White,
                                                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                                VerticalSpace(4.dp)
                                GlassLabel(preset.label, size = 11, alpha = 0.85f)
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassSurface(
                                modifier = Modifier
                                    .size(width = 46.dp, height = 70.dp)
                                    .glassClickable(pressedScale = 1.08f, onClick = onPickWallpaper),
                                cornerRadius = 12.dp,
                                tint = if (settings.wallpaper == WallpaperPreset.Photo) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Image, contentDescription = "Choose photo", tint = Color.White)
                            }
                            VerticalSpace(4.dp)
                            GlassLabel("Photo", size = 11, alpha = 0.85f)
                        }
                    }
                    Divider()
                    SliderSetting(
                        title = "Glass frost",
                        valueText = if (settings.frost < 0.02f) "Clear" else "${(settings.frost * 100).roundToInt()}%",
                        value = settings.frost,
                        range = 0f..1f,
                        onChange = { settings.updateFrost(it) }
                    )
                    SliderSetting(
                        title = "Light refraction",
                        valueText = "${(settings.refraction * 100).roundToInt()}%",
                        value = settings.refraction,
                        range = 0.3f..1.6f,
                        onChange = { settings.updateRefraction(it) }
                    )
                    SliderSetting(
                        title = "Dim behind panels",
                        valueText = "${(settings.panelDim * 100).roundToInt()}%",
                        value = settings.panelDim,
                        range = 0f..0.6f,
                        onChange = { settings.updatePanelDim(it) }
                    )
                }

                SectionTitle("Home screen")
                SettingsCard {
                    SettingLabel("Cover screen grid", null)
                    GlassSegmented(
                        options = GridSize.coverOptions.map { it.label },
                        selectedIndex = GridSize.coverOptions.indexOf(settings.coverGrid).coerceAtLeast(0),
                        onSelect = { settings.updateCoverGrid(GridSize.coverOptions[it]) }
                    )
                    SettingLabel("Main screen grid", "Used when the Fold is open")
                    GlassSegmented(
                        options = GridSize.mainOptions.map { it.label },
                        selectedIndex = GridSize.mainOptions.indexOf(settings.mainGrid).coerceAtLeast(0),
                        onSelect = { settings.updateMainGrid(GridSize.mainOptions[it]) }
                    )
                    Divider()
                    SwitchRow("Widgets on first page", settings.showWidgets) { settings.updateShowWidgets(it) }
                    SwitchRow("App labels", settings.showLabels) { settings.updateShowLabels(it) }
                    SwitchRow("Search button", settings.showSearchPill) { settings.updateShowSearchPill(it) }
                    SwitchRow("Add new apps to Home", settings.addNewApps) { settings.updateAddNewApps(it) }
                    Divider()
                    ActionRow("Reset Home screen layout", null, destructive = true) { settings.resetHomeLayout() }
                }

                SectionTitle("Panels")
                SettingsCard {
                    SettingLabel("Swipe-down split", "Left side opens notifications, right side opens controls")
                    val splits = listOf(0.5f, 0.6f, 0.7f)
                    GlassSegmented(
                        options = listOf("50 / 50", "60 / 40", "70 / 30"),
                        selectedIndex = splits.indexOfFirst { kotlin.math.abs(it - settings.splitFraction) < 0.01f }.coerceAtLeast(0),
                        onSelect = { settings.updateSplitFraction(splits[it]) }
                    )
                    Divider()
                    SwitchRow("Edge panel", settings.edgePanelEnabled) { settings.updateEdgePanelEnabled(it) }
                }

                SectionTitle("Permissions")
                SettingsCard {
                    ActionRow("Default Home app", status(system.isDefaultLauncher)) { system.openHomeAppSettings() }
                    ActionRow("Notification access", status(system.notificationAccessGranted)) {
                        system.openNotificationAccessSettings()
                    }
                    ActionRow("Modify system settings", status(system.writeSettingsGranted)) {
                        system.openWriteSettingsPermission()
                    }
                    ActionRow("Do Not Disturb access", status(system.dndAccessGranted)) {
                        system.openDndAccessSettings()
                    }
                    ActionRow("Location and Bluetooth", null) { system.openAppPermissions() }
                }

                SectionTitle("About")
                SettingsCard {
                    SettingLabel("GlassOS Launcher 2.0", "Liquid glass for Galaxy Z Fold6 and S25 Ultra")
                }
            }
        }
    }
}

private fun status(granted: Boolean): String = if (granted) "On" else "Off"

@Composable
private fun SectionTitle(text: String) {
    GlassLabel(
        text.uppercase(),
        size = 12,
        weight = FontWeight.SemiBold,
        alpha = 0.7f,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 26.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun SettingLabel(title: String, subtitle: String?) {
    Column {
        GlassLabel(title, size = 16, weight = FontWeight.SemiBold)
        if (subtitle != null) {
            GlassLabel(subtitle, size = 12, alpha = 0.65f, maxLines = 2)
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.14f))
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassLabel(title, size = 16, modifier = Modifier.weight(1f))
        GlassSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, value: String?, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .glassClickable(pressedScale = 1.02f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = glassText(TextStyle(fontSize = 16.sp)),
            color = if (destructive) IosRed else Color.White,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            GlassLabel(value, size = 15, alpha = 0.6f)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassLabel(title, size = 16, modifier = Modifier.weight(1f))
            GlassLabel(valueText, size = 14, alpha = 0.65f)
        }
        GlassHorizontalSlider(value = value, onValueChange = onChange, valueRange = range)
    }
}
