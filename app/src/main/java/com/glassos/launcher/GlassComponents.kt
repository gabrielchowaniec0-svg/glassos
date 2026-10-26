package com.glassos.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

// iOS system colours used for active states.
val IosBlue = Color(0xFF0A84FF)
val IosGreen = Color(0xFF30D158)
val IosOrange = Color(0xFFFF9F0A)
val IosIndigo = Color(0xFF5E5CE6)
val IosRed = Color(0xFFFF453A)
val IosYellow = Color(0xFFFFD60A)

// =============================================================================================
// App icons
// =============================================================================================

/** Rendering options for app icons, derived from settings. */
data class IconAppearance(
    val style: IconStyle,
    val tint: Color,
    val showLabels: Boolean
)

/**
 * One app icon in any of the three styles:
 *  - Default: the app's real artwork clipped to an iOS rounded square.
 *  - Clear: white glyph floating on a refracting liquid glass tile.
 *  - Tinted: accent-coloured glyph on a slightly darker glass tile.
 */
@Composable
fun AppIconImage(
    app: AppEntry,
    appearance: IconAppearance,
    iconSize: Dp,
    modifier: Modifier = Modifier
) {
    val corner = iconSize * 0.235f
    if (!app.isGlyph || appearance.style == IconStyle.Default) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = modifier
                .size(iconSize)
                .clip(RoundedCornerShape(corner))
        )
    } else {
        GlassSurface(
            modifier = modifier.size(iconSize),
            cornerRadius = corner,
            tint = if (appearance.style == IconStyle.Tinted) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                colorFilter = if (appearance.style == IconStyle.Tinted) {
                    ColorFilter.tint(appearance.tint, BlendMode.Modulate)
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.94f)
            )
        }
    }
}

/** Icon with an optional label underneath, as used on the home screen, drawer and dock. */
@Composable
fun AppIconWithLabel(
    app: AppEntry,
    appearance: IconAppearance,
    iconSize: Dp,
    showLabel: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.glassClickable(pressedScale = 0.9f, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppIconImage(app = app, appearance = appearance, iconSize = iconSize)
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = app.label,
                style = glassText(TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(iconSize + 16.dp)
            )
        }
    }
}

/** Vector fallback (used when a Samsung core app is missing from the device). */
@Composable
fun FallbackAppIcon(
    icon: ImageVector,
    label: String,
    iconSize: Dp,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier.size(iconSize),
        cornerRadius = iconSize * 0.235f,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(iconSize * 0.5f)
        )
    }
}

// =============================================================================================
// Buttons
// =============================================================================================

/** Round liquid glass button with an icon (control center, lock-screen style shortcuts). */
@Composable
fun GlassRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    active: Boolean = false,
    activeColor: Color = Color.White,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fill by animateColorAsState(
        targetValue = if (active) activeColor.copy(alpha = if (activeColor == Color.White) 0.92f else 0.85f) else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "roundFill"
    )
    val iconColor = if (active && activeColor == Color.White) Color(0xFF1C1C1E) else Color.White
    GlassSurface(
        modifier = modifier
            .size(size)
            .glassClickable(onLongClick = onLongClick, onClick = onClick),
        cornerRadius = GlassCapsule,
        tint = fill,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(size * 0.42f)
        )
    }
}

// =============================================================================================
// iOS-style switch
// =============================================================================================

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val trackWidth = 52.dp
    val trackHeight = 32.dp
    val thumbSize = 26.dp
    val position by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "switchPos"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) IosGreen else Color.White.copy(alpha = 0.10f),
        label = "switchTrack"
    )
    // The thumb stretches like a droplet while it travels (liquid feel).
    val travel = 1f - abs(position - 0.5f) * 2f
    val thumbWidth = thumbSize + 8.dp * travel.coerceIn(0f, 1f)
    val density = LocalDensity.current

    GlassSurface(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .pointerInput(checked) {
                detectTapGestures {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(!checked)
                }
            },
        cornerRadius = GlassCapsule,
        tint = trackColor,
        contentAlignment = Alignment.CenterStart
    ) {
        val maxOffsetPx = with(density) { (trackWidth - thumbWidth - 6.dp).toPx() }
        Box(
            modifier = Modifier
                .offset { IntOffset((with(density) { 3.dp.toPx() } + maxOffsetPx * position).roundToInt(), 0) }
                .size(width = thumbWidth, height = thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

// =============================================================================================
// Segmented control with a sliding liquid thumb
// =============================================================================================

@Composable
fun GlassSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "segment"
    )
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        cornerRadius = GlassCapsule
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            val count = options.size.coerceAtLeast(1)
            val segmentWidth = maxWidth / count
            val stretch = 1f + (1f - abs((animatedIndex % 1f) - 0.5f) * 2f).coerceIn(0f, 1f) * 0.18f
            Box(
                modifier = Modifier
                    .offset(x = segmentWidth * animatedIndex)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = if (animatedIndex % 1f == 0f) 1f else stretch }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.26f))
            )
            Row(modifier = Modifier.fillMaxSize()) {
                options.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(index, selectedIndex) {
                                detectTapGestures {
                                    if (index != selectedIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelect(index)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = glassText(
                                TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Medium
                                )
                            ),
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Sliders
// =============================================================================================

/** Horizontal glass slider (settings). */
@Composable
fun GlassHorizontalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val latest by rememberUpdatedState(onValueChange)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    latest(valueRange.start + (offset.x / size.width).coerceIn(0f, 1f) * span)
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latest(valueRange.start + (change.position.x / size.width).coerceIn(0f, 1f) * span)
                }
            },
        cornerRadius = GlassCapsule,
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceAtLeast(0.08f))
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
        )
    }
}

/**
 * Tall control-center slider: fills from the bottom like iOS, stretches slightly while dragged and
 * springs back on release.
 */
@Composable
fun VerticalGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: ImageVector,
    iconTint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val latest by rememberUpdatedState(onValueChange)
    var dragging by remember { mutableStateOf(false) }
    val stretch by animateFloatAsState(
        targetValue = if (dragging) 1.045f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sliderStretch"
    )
    val fill = value.coerceIn(0f, 1f)

    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                scaleY = stretch
                scaleX = 1f / stretch
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    latest(1f - (offset.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                var start = 0f
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        start = fill
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, amount ->
                        change.consume()
                        start = (start - amount.y / size.height).coerceIn(0f, 1f)
                        latest(start)
                    }
                )
            },
        cornerRadius = GlassCapsule,
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fill)
                .background(Color.White.copy(alpha = 0.92f))
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (fill > 0.18f) iconTint else Color.White,
            modifier = Modifier
                .padding(bottom = 18.dp)
                .size(30.dp)
        )
    }
}

// =============================================================================================
// Small helpers
// =============================================================================================

@Composable
fun GlassLabel(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 13,
    weight: FontWeight = FontWeight.Medium,
    alpha: Float = 1f,
    maxLines: Int = 1
) {
    Text(
        text = text,
        style = glassText(TextStyle(fontSize = size.sp, fontWeight = weight)),
        color = Color.White.copy(alpha = alpha),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun VerticalSpace(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun HorizontalSpace(width: Dp) {
    Spacer(Modifier.width(width))
}

/** Row with even spacing used for grids of equal modules. */
@Composable
fun EvenRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
