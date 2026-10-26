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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ChargingGreen = Color(0xFF34C759)
private val LowRed = Color(0xFFFF3B30)

/**
 * Replacement for the hidden native status bar: a live clock pill on the left and a connectivity /
 * battery pill on the right. The centre stays empty so the S25 Ultra / Fold punch-hole is never covered.
 */
@Composable
fun CustomStatusBar(
    battery: BatteryState,
    wifi: WifiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val use24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val timeText by produceState(initialValue = formatClock(use24Hour), use24Hour) {
        while (true) {
            value = formatClock(use24Hour)
            val untilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(untilNextMinute + 50L)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassSurface(
            modifier = Modifier.height(34.dp),
            cornerRadius = 17.dp,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 15.dp)
            )
        }

        GlassSurface(
            modifier = Modifier.height(34.dp),
            cornerRadius = 17.dp,
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WifiSignalIcon(
                    level = wifi.signalLevel,
                    enabled = wifi.enabled,
                    connected = wifi.connected,
                    color = Color.White
                )
                Spacer(Modifier.width(10.dp))
                BatteryPill(level = battery.level, charging = battery.isCharging)
            }
        }
    }
}

private fun formatClock(use24Hour: Boolean): String {
    val pattern = if (use24Hour) "HH:mm" else "h:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

/** Three-arc Wi-Fi glyph drawn with Canvas; lit arcs follow the live signal level (0..4). */
@Composable
fun WifiSignalIcon(
    level: Int,
    enabled: Boolean,
    connected: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 14.dp)) {
        val strokeWidth = 1.9.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height - strokeWidth * 0.7f
        val litArcs = when {
            !enabled || !connected -> 0
            level <= 1 -> 1
            level == 2 -> 2
            else -> 3
        }
        val dim = color.copy(alpha = 0.28f)
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
            radius = strokeWidth * 0.75f,
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

/** Custom-drawn battery: percentage text plus an outlined body filled to the BatteryManager level. */
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
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(5.dp))
        Canvas(modifier = Modifier.size(width = 26.dp, height = 13.dp)) {
            val nubWidth = 2.5.dp.toPx()
            val bodyWidth = size.width - nubWidth
            val bodyHeight = size.height
            val stroke = 1.dp.toPx()
            val inset = 2.dp.toPx()
            val bodyRadius = CornerRadius(4.dp.toPx())

            // Outline
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(bodyWidth - stroke, bodyHeight - stroke),
                cornerRadius = bodyRadius,
                style = Stroke(width = stroke)
            )
            // Level fill
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
            // Terminal nub
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(bodyWidth + 0.5.dp.toPx(), bodyHeight * 0.30f),
                size = Size(nubWidth - 0.5.dp.toPx(), bodyHeight * 0.40f),
                cornerRadius = CornerRadius(1.dp.toPx()),
                style = Fill
            )
            // Charging bolt
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
