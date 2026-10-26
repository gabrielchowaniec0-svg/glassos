package com.glassos.launcher

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private const val GRID_COLUMNS = 4
private const val GRID_ROWS = 5
private const val APPS_PER_PAGE = GRID_COLUMNS * GRID_ROWS

/** Left 60% of the screen opens notifications, the right 40% opens quick settings. */
private const val NOTIFICATION_ZONE_FRACTION = 0.6f

// ---------------------------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------------------------

@Composable
fun GlassOSRoot(
    systemState: SystemStateHolder,
    hingeSensorManager: HingeSensorManager,
    homePressEvents: SharedFlow<Unit>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panelController = remember { PanelController(scope) }

    // Z Fold unfold transition: content scales 0.94 -> 1.0 while a 50 -> 0 blur dissolves.
    val unfoldTarget by hingeSensorManager.unfoldProgress.collectAsState()
    val unfold by animateFloatAsState(
        targetValue = unfoldTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "unfoldProgress"
    )

    val apps by produceState(emptyList<AppEntry>(), systemState.appsVersion) {
        value = withContext(Dispatchers.Default) { AppRepository.loadApps(context) }
    }
    val dock by produceState(emptyList<DockEntry>(), systemState.appsVersion) {
        value = withContext(Dispatchers.Default) { AppRepository.loadDock(context) }
    }

    val pageCount = max(1, (apps.size + APPS_PER_PAGE - 1) / APPS_PER_PAGE)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    BackHandler(enabled = panelController.isActive) {
        panelController.close()
    }

    LaunchedEffect(homePressEvents) {
        homePressEvents.collect {
            panelController.close()
            pagerState.animateScrollToPage(0)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val rootSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val environment = remember(rootSize) { GlassEnvironment(rootSize) }

        CompositionLocalProvider(LocalGlassEnvironment provides environment) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = lerp(0.94f, 1f, unfold)
                        scaleX = scale
                        scaleY = scale
                    }
                    .layerBlur { lerp(50f, 0f, unfold) }
                    .panelPullGesture(panelController)
            ) {
                GlassWallpaper(modifier = Modifier.fillMaxSize())

                HomeContent(
                    apps = apps,
                    dock = dock,
                    pagerState = pagerState,
                    systemState = systemState,
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBlur { panelController.progress * 28f }
                )

                PanelOverlay(
                    controller = panelController,
                    systemState = systemState
                )

                CustomStatusBar(
                    battery = systemState.battery,
                    wifi = systemState.wifi,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Split pull-down gesture
// ---------------------------------------------------------------------------------------------

/**
 * Watches the whole screen in the Initial pass without consuming anything until a vertical drag is
 * recognised, so horizontal paging and taps keep working.
 *  - closed + downward drag, started in the left 60%  -> notification tray
 *  - closed + downward drag, started in the right 40% -> quick settings
 *  - open   + upward drag started on the scrim / handle area -> close
 */
private fun Modifier.panelPullGesture(controller: PanelController): Modifier =
    pointerInput(controller) {
        val velocityTracker = VelocityTracker()
        awaitEachGesture {
            velocityTracker.resetTracking()
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val screenWidth = size.width.toFloat()
            val screenHeight = size.height.toFloat()
            val touchSlop = viewConfiguration.touchSlop
            val wasOpen = controller.isActive
            val startedInNotificationZone = down.position.x < screenWidth * NOTIFICATION_ZONE_FRACTION
            val topMarginPx = PanelTopMargin.toPx()
            val closeZonePx = 150.dp.toPx()
            val openPanelBottomPx = if (wasOpen) {
                topMarginPx + controller.type.heightFraction() * screenHeight * controller.progress
            } else {
                0f
            }

            var totalDx = 0f
            var totalDy = 0f
            var decided = false
            var claimed = false
            var activeHeightPx = 1f

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocityTracker.addPointerInputChange(change)
                if (change.changedToUpIgnoreConsumed()) break

                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y

                if (!decided) {
                    if (hypot(totalDx, totalDy) < touchSlop) continue
                    decided = true
                    val verticalDominant = abs(totalDy) > abs(totalDx) * 1.2f
                    val opening = !wasOpen && totalDy > 0f
                    val closing = wasOpen && totalDy < 0f && down.position.y > openPanelBottomPx - closeZonePx

                    if (verticalDominant && opening) {
                        val target = if (startedInNotificationZone) PanelType.Notifications else PanelType.Quick
                        controller.beginDrag(target)
                        activeHeightPx = (target.heightFraction() * screenHeight).coerceAtLeast(1f)
                        controller.dragBy(totalDy / activeHeightPx)
                        claimed = true
                        change.consume()
                    } else if (verticalDominant && closing) {
                        activeHeightPx = (controller.type.heightFraction() * screenHeight).coerceAtLeast(1f)
                        controller.dragBy(totalDy / activeHeightPx)
                        claimed = true
                        change.consume()
                    } else {
                        // Not ours: horizontal paging, taps, scrolling and sliders keep the gesture.
                        break
                    }
                } else if (claimed) {
                    controller.dragBy(delta.y / activeHeightPx)
                    change.consume()
                }
            }

            if (claimed) {
                val velocityY = velocityTracker.calculateVelocity().y
                controller.endDrag(velocityY / activeHeightPx)
            }
        }
    }

// ---------------------------------------------------------------------------------------------
// Home content
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeContent(
    apps: List<AppEntry>,
    dock: List<DockEntry>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    systemState: SystemStateHolder,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 58.dp)
    ) {
        ClockWidget(
            onClick = { systemState.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            val pageApps = apps.drop(page * APPS_PER_PAGE).take(APPS_PER_PAGE)
            AppPage(
                items = pageApps,
                onLaunch = { systemState.launch(it.intent) },
                onInfo = {
                    systemState.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", it.packageName, null)
                        )
                    )
                }
            )
        }

        PageDots(
            count = pagerState.pageCount,
            current = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        DockBar(
            dock = dock,
            onLaunch = { systemState.launch(it.intent) },
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun rememberNow(): State<Date> =
    produceState(initialValue = Date()) {
        while (true) {
            value = Date()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
        }
    }

@Composable
private fun ClockWidget(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val use24Hour = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val now by rememberNow()
    val timeText = remember(now, use24Hour) {
        SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
    }
    val dateText = remember(now) {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
    }

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(124.dp)
            .squishyDeformation()
            .clickable(onClick = onClick),
        cornerRadius = 34.dp,
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = Modifier.padding(horizontal = 26.dp)) {
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 52.sp,
                fontWeight = FontWeight.Light
            )
            Text(
                text = dateText,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AppPage(
    items: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp)
    ) {
        repeat(GRID_ROWS) { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                repeat(GRID_COLUMNS) { column ->
                    val item = items.getOrNull(row * GRID_COLUMNS + column)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item != null) {
                            AppIcon(
                                app = item,
                                onClick = { onLaunch(item) },
                                onLongClick = { onInfo(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIcon(
    app: AppEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "iconPress"
    )

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(60.dp)
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.9f)
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) {
        Spacer(modifier = modifier.height(7.dp))
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (index == current) 0.95f else 0.35f))
            )
        }
    }
}

@Composable
private fun DockBar(
    dock: List<DockEntry>,
    onLaunch: (DockEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(94.dp),
        cornerRadius = 36.dp,
        tint = Color.White.copy(alpha = 0.04f),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            dock.forEach { entry ->
                DockIcon(entry = entry, onClick = { onLaunch(entry) })
            }
        }
    }
}

@Composable
private fun DockIcon(entry: DockEntry, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .squishyDeformation()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val icon = entry.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = entry.label,
                modifier = Modifier.size(60.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.14f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = entry.fallbackIcon,
                    contentDescription = entry.label,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}
