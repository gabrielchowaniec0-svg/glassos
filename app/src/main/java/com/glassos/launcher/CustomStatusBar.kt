package com.glassos.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ChargingGreen = Color(0xFF34C759)
private val LowRed = Color(0xFFFF3B30)

/** Current time, updated on every minute boundary. */
@Composable
fun rememberMinuteClock(): State<Date> =
    produceState(initialValue = Date()) {
        while (true) {
            value = Date()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
        }
    }

@Composable
fun rememberUse24Hour(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}

fun formatTime(date: Date, use24Hour: Boolean): String =
    SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm", Locale.getDefault()).format(date)

/**
 * iOS-style status bar that replaces the hidden native one: time on the left, signal, Wi-Fi and
 * battery on the right. The middle stays empty so the camera punch-hole is never covered.
 */
@Composable
fun CustomStatusBar(
    battery: BatteryState,
    wifi: WifiState,
    cellular: CellularState,
    modifier: Modifier = Modifier
) {
    val use24Hour = rememberUse24Hour()
    val now by rememberMinuteClock()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTime(now, use24Hour),
            style = glassText(TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)),
            color = Color.White
        )
        StatusIcons(battery = battery, wifi = wifi, cellular = cellular, showCarrier = false)
    }
}

/** Signal bars, Wi-Fi and battery, as a compact row. */
@Composable
fun StatusIcons(
    battery: BatteryState,
    wifi: WifiState,
    cellular: CellularState,
    showCarrier: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showCarrier && cellular.carrier != null) {
            Text(
                text = cellular.carrier,
                style = glassText(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)),
                color = Color.White
            )
            Spacer(Modifier.width(6.dp))
        }
        if (cellular.hasTelephony) {
            CellularBars(level = cellular.level, color = Color.White)
            Spacer(Modifier.width(6.dp))
        }
        WifiSignalIcon(
            level = wifi.signalLevel,
            enabled = wifi.enabled,
            connected = wifi.connected,
            color = Color.White
        )
        Spacer(Modifier.width(7.dp))
        BatteryPill(level = battery.level, charging = battery.isCharging)
    }
}

/** Four rising bars; lit bars follow the live signal level (0..4). */
@Composable
fun CellularBars(level: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 12.dp)) {
        val barWidth = size.width / 5.2f
        val gap = (size.width - barWidth * 4f) / 3f
        for (i in 0 until 4) {
            val barHeight = size.height * (0.40f + 0.20f * i)
            drawRoundRect(
                color = if (i < level) color else color.copy(alpha = 0.30f),
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.35f)
            )
        }
    }
}

/** Three-arc Wi-Fi glyph; lit arcs follow the live signal level (0..4). */
@Composable
fun WifiSignalIcon(
    level: Int,
    enabled: Boolean,
    connected: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 14.dp)) {
        val strokeWidth = 2.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height - strokeWidth * 0.7f
        val litArcs = when {
            !enabled || !connected -> 0
            level <= 1 -> 1
            level == 2 -> 2
            else -> 3
        }
        val dim = color.copy(alpha = 0.30f)
        val radii = floatArrayOf(size.height * 0.34f, size.height * 0.62f, size.height * 0.90f)

        radii.forEachIndexed { index, radius ->
            drawArc(
                color = if (index < litArcs) color else dim,
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        drawCircle(
            color = if (enabled) color else dim,
            radius = strokeWidth * 0.8f,
            center = Offset(centerX, centerY)
        )
        if (!enabled) {
            drawLine(
                color = color,
                start = Offset(1.5.dp.toPx(), size.height - 1.dp.toPx()),
                end = Offset(size.width - 1.5.dp.toPx(), 1.dp.toPx()),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

/** iOS-style battery: percentage, outlined body filled to the level, terminal nub and charging bolt. */
@Composable
fun BatteryPill(
    level: Int,
    charging: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = when {
        charging -> ChargingGreen
        level <= 15 -> LowRed
        else -> Color.White
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$level%",
            style = glassText(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)),
            color = Color.White
        )
        Spacer(Modifier.width(4.dp))
        Canvas(modifier = Modifier.size(width = 26.dp, height = 13.dp)) {
            val nubWidth = 2.5.dp.toPx()
            val bodyWidth = size.width - nubWidth
            val bodyHeight = size.height
            val stroke = 1.dp.toPx()
            val inset = 2.dp.toPx()

            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(bodyWidth - stroke, bodyHeight - stroke),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = stroke)
            )
            val fillWidth = ((bodyWidth - inset * 2f) * (level.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(inset, inset),
                    size = Size(fillWidth, bodyHeight - inset * 2f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Fill
                )
            }
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(bodyWidth + 0.5.dp.toPx(), bodyHeight * 0.30f),
                size = Size(nubWidth - 0.5.dp.toPx(), bodyHeight * 0.40f),
                cornerRadius = CornerRadius(1.dp.toPx()),
                style = Fill
            )
            if (charging) {
                val bolt = Path().apply {
                    moveTo(bodyWidth * 0.56f, bodyHeight * 0.12f)
                    lineTo(bodyWidth * 0.30f, bodyHeight * 0.56f)
                    lineTo(bodyWidth * 0.48f, bodyHeight * 0.56f)
                    lineTo(bodyWidth * 0.42f, bodyHeight * 0.90f)
                    lineTo(bodyWidth * 0.70f, bodyHeight * 0.44f)
                    lineTo(bodyWidth * 0.52f, bodyHeight * 0.44f)
                    close()
                }
                drawPath(path = bolt, color = Color.Black.copy(alpha = 0.78f), style = Fill)
            }
        }
    }
}
