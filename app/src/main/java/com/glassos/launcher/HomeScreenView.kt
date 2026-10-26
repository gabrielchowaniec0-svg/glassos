package com.glassos.launcher

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class ContextMenuTarget(val app: AppEntry, val anchor: Rect)

// =============================================================================================
// Root
// =============================================================================================

@Composable
fun GlassOSRoot(
    system: SystemStateHolder,
    hingeSensorManager: HingeSensorManager,
    settings: LauncherSettings,
    media: MediaStateHolder,
    weather: WeatherHolder,
    homePressEvents: SharedFlow<Unit>,
    onPickWallpaper: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panel = remember { PanelController(scope) }
    val edge = remember { EdgePanelController(scope) }
    val exclusions = remember { GestureExclusions() }
    val settingsProgress = remember { Animatable(0f) }
    var settingsOpen by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<ContextMenuTarget?>(null) }
    var focusSearch by remember { mutableStateOf(false) }

    LaunchedEffect(settingsOpen) {
        settingsProgress.animateTo(
            targetValue = if (settingsOpen) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 300f)
        )
    }

    // Z Fold unfold transition: content scales 0.94 -> 1.0 while a 50 -> 0 blur dissolves.
    val unfoldTarget by hingeSensorManager.unfoldProgress.collectAsState()
    val unfold by animateFloatAsState(
        targetValue = unfoldTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "unfoldProgress"
    )

    val wallpaperPhoto by produceState<ImageBitmap?>(null, settings.wallpaperVersion) {
        value = loadWallpaperPhoto(context)
    }

    val apps by produceState(emptyList<AppEntry>(), system.appsVersion, settings.iconStyle) {
        val style = settings.iconStyle
        value = withContext(Dispatchers.Default) { AppRepository.loadApps(context, style) }
    }

    // Keep the home layout in sync with installed apps (first-run defaults, new installs, removals).
    LaunchedEffect(apps) {
        if (apps.isEmpty()) return@LaunchedEffect
        syncHomeLayout(apps, settings)
    }

    LaunchedEffect(Unit) {
        while (true) {
            weather.refresh()
            delay(if (weather.info == null) 60_000L else 15 * 60_000L)
        }
    }

    val configuration = LocalConfiguration.current
    val isLarge = configuration.screenWidthDp >= HingeSensorManager.LARGE_SCREEN_MIN_WIDTH_DP
    val grid = if (isLarge) settings.mainGrid else settings.coverGrid
    val withWidgets = settings.showWidgets
    val homeEntries = remember(apps, settings.homeApps) {
        val byKey = apps.associateBy { it.key }
        settings.homeApps.mapNotNull { byKey[it] }
    }
    val edgeEntries = remember(apps, settings.edgeApps) {
        val byKey = apps.associateBy { it.key }
        settings.edgeApps.mapNotNull { byKey[it] }
    }
    val pages = remember(homeEntries, grid, withWidgets) { paginate(homeEntries, grid, withWidgets) }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    val cellWidth = ((configuration.screenWidthDp - 24) / grid.columns).dp
    val iconSize = min(cellWidth * 0.68f, 62.dp)
    val appearance = IconAppearance(settings.iconStyle, Color(settings.tintColor), settings.showLabels)

    // A home screen never closes on Back. Declared first so the handler below takes priority.
    BackHandler(enabled = true) {}

    BackHandler(enabled = menuTarget != null || settingsOpen || edge.isOpen || panel.isActive) {
        when {
            menuTarget != null -> menuTarget = null
            settingsOpen -> settingsOpen = false
            edge.isOpen -> edge.close()
            else -> panel.close()
        }
    }

    LaunchedEffect(homePressEvents) {
        homePressEvents.collect {
            menuTarget = null
            settingsOpen = false
            edge.close()
            panel.close()
            if (pagerState.pageCount > 0) pagerState.animateScrollToPage(0)
        }
    }

    val glassStyle = remember(settings.frost, settings.refraction) {
        GlassStyle(frost = settings.frost, refraction = settings.refraction)
    }
    val motion: () -> Float = {
        panel.progress + edge.progress + settingsProgress.value + pagerState.currentPageOffsetFraction
    }
    val wallpaperBackdrop = rememberBackdrop()
    val homeBackdrop = rememberBackdrop()

    val launchApp: (AppEntry) -> Unit = { app -> system.launch(app.intent) }
    val openSettings: () -> Unit = {
        panel.close()
        edge.close()
        settingsOpen = true
    }

    CompositionLocalProvider(
        LocalGlassStyle provides glassStyle,
        LocalGlassMotion provides motion,
        LocalGestureExclusions provides exclusions
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = lerp(0.94f, 1f, unfold)
                        scaleX = s
                        scaleY = s
                    }
                    .layerBlur { lerp(50f, 0f, unfold) }
                    .onGloballyPositioned { exclusions.root = it }
                    .shellGestures(
                        panel = panel,
                        exclusions = exclusions,
                        enabled = { menuTarget == null && !settingsOpen && !edge.isOpen },
                        splitFraction = { settings.splitFraction }
                    )
            ) {
                // Everything in this box is recorded into [homeBackdrop] so overlay glass can refract it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .backdropSource(homeBackdrop)
                ) {
                    GlassWallpaper(
                        preset = settings.wallpaper,
                        photo = wallpaperPhoto,
                        modifier = Modifier
                            .fillMaxSize()
                            .backdropSource(wallpaperBackdrop)
                    )
                    CompositionLocalProvider(LocalBackdrop provides wallpaperBackdrop) {
                        HomeContent(
                            pages = pages,
                            pagerState = pagerState,
                            grid = grid,
                            withWidgets = withWidgets,
                            apps = apps,
                            appearance = appearance,
                            iconSize = iconSize,
                            settings = settings,
                            system = system,
                            media = media,
                            weather = weather,
                            statusAlpha = {
                                if (panel.type == PanelType.Quick) 1f - panel.progress else 1f
                            },
                            onAppClick = launchApp,
                            onAppLongPress = { app, rect -> menuTarget = ContextMenuTarget(app, rect) },
                            onEmptyLongPress = openSettings,
                            onSearch = {
                                focusSearch = true
                                panel.open(PanelType.Drawer)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // No blur anywhere: overlays fade the icons instead. The control
                                    // center keeps them partly visible so its glass refracts them.
                                    val overlay = when (panel.type) {
                                        PanelType.Drawer, PanelType.Notifications -> panel.progress
                                        PanelType.Quick -> panel.progress * 0.45f
                                        PanelType.None -> 0f
                                    }
                                    val hide = max(overlay, settingsProgress.value)
                                    alpha = 1f - hide
                                    val s = 1f - 0.07f * hide
                                    scaleX = s
                                    scaleY = s
                                }
                        )
                    }
                }

                // Overlays refract the live home screen (icons, widgets and wallpaper).
                CompositionLocalProvider(LocalBackdrop provides homeBackdrop) {
                    if (panel.type == PanelType.Drawer) {
                        AppDrawer(
                            panel = panel,
                            apps = apps,
                            appearance = appearance,
                            columns = grid.columns,
                            iconSize = iconSize,
                            system = system,
                            focusSearch = focusSearch,
                            onFocusConsumed = { focusSearch = false },
                            onAppClick = { app ->
                                launchApp(app)
                                panel.closeImmediately()
                            },
                            onAppLongPress = { app, rect -> menuTarget = ContextMenuTarget(app, rect) }
                        )
                    }

                    PanelOverlay(
                        controller = panel,
                        system = system,
                        media = media,
                        settings = settings,
                        isLarge = isLarge,
                        onOpenLauncherSettings = openSettings
                    )

                    if (settings.edgePanelEnabled && !settingsOpen && !panel.isActive) {
                        EdgePanel(
                            controller = edge,
                            apps = edgeEntries,
                            appearance = appearance,
                            iconSize = iconSize,
                            onLaunch = launchApp,
                            onLongPress = { app, rect -> menuTarget = ContextMenuTarget(app, rect) }
                        )
                    }

                    if (settingsOpen || settingsProgress.value > 0.001f) {
                        GlassSettingsScreen(
                            progress = { settingsProgress.value },
                            settings = settings,
                            system = system,
                            onPickWallpaper = onPickWallpaper,
                            onClose = { settingsOpen = false }
                        )
                    }

                    val target = menuTarget
                    if (target != null) {
                        AppContextMenu(
                            target = target,
                            settings = settings,
                            system = system,
                            appearance = appearance,
                            iconSize = iconSize,
                            onDismiss = { menuTarget = null }
                        )
                    }
                }
            }
        }
    }
}

/** First-run One UI defaults, auto-adding new installs and pruning uninstalled apps. */
private fun syncHomeLayout(apps: List<AppEntry>, settings: LauncherSettings) {
    val keys = apps.map { it.key }.toSet()
    val byPackage = apps.groupBy { it.packageName }
    if (!settings.homeInitialized) {
        val dockPackages = AppRepository.dockSlots.map { it.packageName }.toSet()
        val defaults = AppRepository.defaultHomePackages.mapNotNull { byPackage[it]?.firstOrNull()?.key }
        val rest = apps
            .filter { it.packageName !in dockPackages && it.key !in defaults }
            .map { it.key }
        settings.replaceHomeApps(defaults + rest)
        if (settings.edgeApps.isEmpty()) settings.replaceEdgeApps(defaults.take(6))
        settings.replaceKnownApps(keys)
        return
    }
    var home = settings.homeApps
    val newKeys = keys - settings.knownApps
    if (newKeys.isNotEmpty() && settings.addNewApps) {
        home = home + apps.filter { it.key in newKeys }.map { it.key }
    }
    val pruned = home.filter { it in keys }
    if (pruned != settings.homeApps) settings.replaceHomeApps(pruned)
    val prunedEdge = settings.edgeApps.filter { it in keys }
    if (prunedEdge != settings.edgeApps) settings.replaceEdgeApps(prunedEdge)
    if (keys != settings.knownApps) settings.replaceKnownApps(keys)
}

/** Splits the home apps into pages; the first page loses two rows when widgets are shown. */
private fun paginate(apps: List<AppEntry>, grid: GridSize, withWidgets: Boolean): List<List<AppEntry>> {
    val firstCapacity = grid.columns * (grid.rows - if (withWidgets) 2 else 0)
    val capacity = grid.columns * grid.rows
    val pages = ArrayList<List<AppEntry>>()
    pages.add(apps.take(firstCapacity))
    var index = firstCapacity
    while (index < apps.size) {
        pages.add(apps.subList(index, minOf(apps.size, index + capacity)))
        index += capacity
    }
    return pages
}

// =============================================================================================
// Shell gestures
// =============================================================================================

/**
 * One gesture detector for the whole shell, watching the Initial pass so it can claim vertical drags
 * before children see them (horizontal paging, taps and excluded sliders/lists are left alone):
 *  - home, swipe down left of the split -> notification center
 *  - home, swipe down right of the split -> control center
 *  - home, swipe up -> apps screen
 *  - open panel, swipe up -> close (the panel follows the finger)
 *  - apps screen at the top, swipe down -> close
 */
private fun Modifier.shellGestures(
    panel: PanelController,
    exclusions: GestureExclusions,
    enabled: () -> Boolean,
    splitFraction: () -> Float
): Modifier = pointerInput(panel, exclusions) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        tracker.resetTracking()
        tracker.addPosition(down.uptimeMillis, down.position)

        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val slop = viewConfiguration.touchSlop
        val startType = panel.type

        var totalDx = 0f
        var totalDy = 0f
        var decided = false
        var claimed = false
        var distance = 1f
        var direction = 1f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(change)
            // The release event can carry movement too (input is often batched), so count it.
            val delta = change.positionChange()
            if (change.changedToUpIgnoreConsumed()) {
                if (claimed) panel.dragBy(direction * delta.y / distance)
                break
            }

            if (!decided) {
                totalDx += delta.x
                totalDy += delta.y
                if (hypot(totalDx, totalDy) < slop) continue
                decided = true
                val vertical = abs(totalDy) > abs(totalDx) * 1.2f
                if (!vertical || exclusions.blocks(down.position, totalDy)) break

                when (startType) {
                    PanelType.None -> {
                        if (totalDy > 0f) {
                            val target = if (down.position.x < width * splitFraction()) {
                                PanelType.Notifications
                            } else {
                                PanelType.Quick
                            }
                            panel.beginDrag(target)
                            distance = height * 0.55f
                            direction = 1f
                        } else {
                            panel.beginDrag(PanelType.Drawer)
                            distance = height * 0.6f
                            direction = -1f
                        }
                    }
                    PanelType.Notifications, PanelType.Quick -> {
                        if (totalDy > 0f) break
                        panel.grab()
                        distance = height * 0.55f
                        direction = 1f
                    }
                    PanelType.Drawer -> {
                        if (totalDy < 0f) break
                        panel.grab()
                        distance = height * 0.6f
                        direction = -1f
                    }
                }
                claimed = true
                panel.dragBy(direction * totalDy / distance)
                change.consume()
            } else if (claimed) {
                panel.dragBy(direction * delta.y / distance)
                change.consume()
            }
        }

        if (claimed) {
            val velocityY = tracker.calculateVelocity().y
            panel.endDrag(direction * velocityY / distance, opening = startType == PanelType.None)
        }
    }
}

// =============================================================================================
// Home content
// =============================================================================================

@Composable
private fun HomeContent(
    pages: List<List<AppEntry>>,
    pagerState: PagerState,
    grid: GridSize,
    withWidgets: Boolean,
    apps: List<AppEntry>,
    appearance: IconAppearance,
    iconSize: Dp,
    settings: LauncherSettings,
    system: SystemStateHolder,
    media: MediaStateHolder,
    weather: WeatherHolder,
    statusAlpha: () -> Float,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry, Rect) -> Unit,
    onEmptyLongPress: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        CustomStatusBar(
            battery = system.battery,
            wifi = system.wifi,
            cellular = system.cellular,
            modifier = Modifier
                .padding(top = 12.dp)
                .graphicsLayer { alpha = statusAlpha() }
        )
        Spacer(Modifier.height(14.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            beyondViewportPageCount = 1
        ) { page ->
            HomePage(
                items = pages.getOrElse(page) { emptyList() },
                grid = grid,
                withWidgets = withWidgets && page == 0,
                appearance = appearance,
                iconSize = iconSize,
                system = system,
                media = media,
                weather = weather,
                onAppClick = onAppClick,
                onAppLongPress = onAppLongPress,
                onEmptyLongPress = onEmptyLongPress
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (settings.showSearchPill) {
                SearchPill(onClick = onSearch)
            } else {
                PageDots(count = pagerState.pageCount, current = pagerState.currentPage)
            }
        }

        DockBar(
            apps = apps,
            appearance = appearance,
            iconSize = iconSize,
            system = system,
            onLongPress = onAppLongPress,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun HomePage(
    items: List<AppEntry>,
    grid: GridSize,
    withWidgets: Boolean,
    appearance: IconAppearance,
    iconSize: Dp,
    system: SystemStateHolder,
    media: MediaStateHolder,
    weather: WeatherHolder,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry, Rect) -> Unit,
    onEmptyLongPress: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        val cellWidth = maxWidth / grid.columns
        val cellHeight = maxHeight / grid.rows
        val widgetRows = if (withWidgets) 2 else 0
        val density = LocalDensity.current
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items, grid, withWidgets) {
                    // Long press on an empty cell (not on an icon or widget) opens GlassOS settings.
                    detectTapGestures(onLongPress = { offset ->
                        val row = (offset.y / cellHeightPx).toInt()
                        val column = (offset.x / cellWidthPx).toInt()
                        if (row >= widgetRows) {
                            val index = (row - widgetRows) * grid.columns + column
                            if (index !in items.indices) onEmptyLongPress()
                        }
                    })
                }
        ) {
            if (withWidgets) {
                WidgetRow(
                    height = cellHeight * 2,
                    system = system,
                    media = media,
                    weather = weather
                )
            }
            for (row in 0 until grid.rows - widgetRows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeight)
                ) {
                    for (column in 0 until grid.columns) {
                        val app = items.getOrNull(row * grid.columns + column)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (app != null) {
                                HomeAppCell(
                                    app = app,
                                    appearance = appearance,
                                    iconSize = iconSize,
                                    onClick = { onAppClick(app) },
                                    onLongPress = { rect -> onAppLongPress(app, rect) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}

/** An icon that reports its on-screen bounds when long-pressed (for the context menu). */
@Composable
private fun HomeAppCell(
    app: AppEntry,
    appearance: IconAppearance,
    iconSize: Dp,
    onClick: () -> Unit,
    onLongPress: (Rect) -> Unit
) {
    val exclusions = LocalGestureExclusions.current
    val holder = remember { CoordinatesHolder() }
    AppIconWithLabel(
        app = app,
        appearance = appearance,
        iconSize = iconSize,
        showLabel = appearance.showLabels,
        onClick = onClick,
        onLongClick = {
            val root = exclusions.root
            val coordinates = holder.coordinates
            val rect = if (root != null && coordinates != null && root.isAttached && coordinates.isAttached) {
                root.localBoundingBoxOf(coordinates)
            } else {
                Rect.Zero
            }
            onLongPress(rect)
        },
        modifier = Modifier.onGloballyPositioned { holder.coordinates = it }
    )
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .height(34.dp)
            .glassClickable(onClick = onClick),
        cornerRadius = GlassCapsule,
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            GlassLabel("Search", size = 14, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
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
    apps: List<AppEntry>,
    appearance: IconAppearance,
    iconSize: Dp,
    system: SystemStateHolder,
    onLongPress: (AppEntry, Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(iconSize + 28.dp),
        cornerRadius = (iconSize + 28.dp) * 0.42f,
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppRepository.dockSlots.forEach { slot ->
                val app = apps.firstOrNull { it.packageName == slot.packageName }
                if (app != null) {
                    HomeAppCell(
                        app = app,
                        appearance = appearance.copy(showLabels = false),
                        iconSize = iconSize,
                        onClick = { system.launch(app.intent) },
                        onLongPress = { rect -> onLongPress(app, rect) }
                    )
                } else {
                    FallbackAppIcon(
                        icon = slot.fallbackIcon,
                        label = slot.label,
                        iconSize = iconSize,
                        modifier = Modifier.glassClickable(pressedScale = 0.9f) {
                            system.launch(Intent(slot.fallbackIntent))
                        }
                    )
                }
            }
        }
    }
}

// =============================================================================================
// Widgets
// =============================================================================================

@Composable
private fun WidgetRow(
    height: Dp,
    system: SystemStateHolder,
    media: MediaStateHolder,
    weather: WeatherHolder
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        val labelSpace = 20.dp
        val gap = 16.dp
        val side = min(height - labelSpace - 10.dp, (maxWidth - gap - 8.dp) / 2)
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            WidgetFrame(label = "Weather") {
                WeatherWidget(weather = weather, system = system, size = side)
            }
            WidgetFrame(label = "Music") {
                MusicWidget(media = media, system = system, size = side)
            }
        }
    }
}

@Composable
private fun WidgetFrame(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(5.dp))
        GlassLabel(label, size = 12, weight = FontWeight.Medium)
    }
}

@Composable
private fun WeatherWidget(weather: WeatherHolder, system: SystemStateHolder, size: Dp) {
    val info = weather.info
    GlassSurface(
        modifier = Modifier
            .size(size)
            .glassClickable(pressedScale = 1.04f) {
                if (!system.launchPackage("com.sec.android.daemonapp", null)) system.webSearch("weather")
            },
        cornerRadius = size * 0.22f
    ) {
        Column(modifier = Modifier.padding(size * 0.09f)) {
            if (info == null) {
                GlassLabel("Weather", size = 15, weight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                GlassLabel(weather.message, size = 13, alpha = 0.8f, maxLines = 3)
            } else {
                GlassLabel(info.city ?: "My Location", size = 15, weight = FontWeight.SemiBold)
                Text(
                    text = "${info.temperature}°",
                    style = glassText(TextStyle(fontSize = (size.value * 0.27f).sp, fontWeight = FontWeight.Light)),
                    color = Color.White,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = weatherIcon(info.code, info.isDay),
                    contentDescription = null,
                    tint = if (info.code <= 1 && info.isDay) IosYellow else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                GlassLabel(weatherLabel(info.code), size = 13, weight = FontWeight.SemiBold)
                GlassLabel("H:${info.high}° L:${info.low}°", size = 13, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun MusicWidget(media: MediaStateHolder, system: SystemStateHolder, size: Dp) {
    val info = media.info
    GlassSurface(
        modifier = Modifier
            .size(size)
            .glassClickable(pressedScale = 1.04f) { media.openPlayer(system) },
        cornerRadius = size * 0.22f
    ) {
        Column(modifier = Modifier.padding(size * 0.09f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                val artSize = size * 0.4f
                val art = info?.artwork
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = "Artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(artSize * 0.16f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(artSize * 0.16f))
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(artSize * 0.5f)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            GlassLabel(info?.title ?: "Not Playing", size = 14, weight = FontWeight.SemiBold)
            GlassLabel(info?.artist ?: "Tap to open music", size = 12, alpha = 0.7f)
            Spacer(Modifier.height(size * 0.04f))
            GlassSurface(
                modifier = Modifier
                    .height(size * 0.2f)
                    .glassClickable {
                        if (info != null) media.playPause() else media.openPlayer(system)
                    },
                cornerRadius = GlassCapsule,
                tint = Color.White.copy(alpha = 0.12f),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (info?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    GlassLabel(if (info?.isPlaying == true) "Pause" else "Play", size = 14, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// =============================================================================================
// Apps screen (One UI app drawer with Finder-style search)
// =============================================================================================

@Composable
private fun AppDrawer(
    panel: PanelController,
    apps: List<AppEntry>,
    appearance: IconAppearance,
    columns: Int,
    iconSize: Dp,
    system: SystemStateHolder,
    focusSearch: Boolean,
    onFocusConsumed: () -> Unit,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry, Rect) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
    }

    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            delay(180)
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Field not attached yet.
            }
            onFocusConsumed()
        }
    }
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = panel.progress
                alpha = p
                translationY = (1f - p) * size.height * 0.16f
            }
            .pointerInput(Unit) { detectTapGestures { keyboard?.hide() } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 50.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                cornerRadius = GlassCapsule,
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            GlassLabel("Search apps and the web", size = 16, alpha = 0.6f)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                val first = filtered.firstOrNull()
                                if (first != null) onAppClick(first) else if (query.isNotBlank()) system.webSearch(query.trim())
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(22.dp)
                                .glassClickable { query = "" }
                        )
                    }
                }
            }

            if (query.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .glassClickable(pressedScale = 1.02f) { system.webSearch(query.trim()) },
                    cornerRadius = 18.dp,
                    contentAlignment = Alignment.CenterStart
                ) {
                    GlassLabel(
                        "Search the web for “${query.trim()}”",
                        size = 14,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .gestureExclusion { dy -> dy > 0f && gridState.canScrollBackward },
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(items = filtered, key = { it.key }) { app ->
                    Box(contentAlignment = Alignment.Center) {
                        HomeAppCell(
                            app = app,
                            appearance = appearance.copy(showLabels = true),
                            iconSize = iconSize,
                            onClick = { onAppClick(app) },
                            onLongPress = { rect -> onAppLongPress(app, rect) }
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Edge panel (One UI)
// =============================================================================================

@Stable
class EdgePanelController(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    val isOpen: Boolean get() = progress > 0.001f

    fun dragBy(delta: Float) {
        job?.cancel()
        progress = (progress + delta).coerceIn(0f, 1f)
    }

    fun settle() = animateTo(progress > 0.4f)

    fun open() = animateTo(true)

    fun close() = animateTo(false)

    private fun animateTo(open: Boolean) {
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
            ) { value, _ -> progress = value }
        }
    }
}

@Composable
private fun EdgePanel(
    controller: EdgePanelController,
    apps: List<AppEntry>,
    appearance: IconAppearance,
    iconSize: Dp,
    onLaunch: (AppEntry) -> Unit,
    onLongPress: (AppEntry, Rect) -> Unit
) {
    val density = LocalDensity.current
    val panelWidth = iconSize + 34.dp
    val panelWidthPx = with(density) { panelWidth.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (controller.isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = controller.progress }
                    .background(Color.Black.copy(alpha = 0.18f))
                    .pointerInput(Unit) { detectTapGestures { controller.close() } }
            )
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .width(panelWidth)
                    .heightIn(max = 600.dp)
                    .graphicsLayer {
                        translationX = (1f - controller.progress) * (panelWidthPx + 24.dp.toPx())
                    }
                    .pointerInput(panelWidthPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = { controller.settle() },
                            onDragCancel = { controller.settle() }
                        ) { change, amount ->
                            change.consume()
                            controller.dragBy(-amount / panelWidthPx)
                        }
                    },
                cornerRadius = 32.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 18.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (apps.isEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Apps,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                        GlassLabel(
                            "Long-press an app to add it",
                            size = 11,
                            alpha = 0.8f,
                            maxLines = 4,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    apps.forEach { app ->
                        HomeAppCell(
                            app = app,
                            appearance = appearance.copy(showLabels = false),
                            iconSize = iconSize,
                            onClick = {
                                onLaunch(app)
                                controller.close()
                            },
                            onLongPress = { rect -> onLongPress(app, rect) }
                        )
                    }
                }
            }
        }

        // Handle on the right edge: tap or drag inwards.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = (-90).dp)
                .size(width = 24.dp, height = 120.dp)
                .graphicsLayer { alpha = 1f - controller.progress }
                .pointerInput(panelWidthPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = { controller.settle() },
                        onDragCancel = { controller.settle() }
                    ) { change, amount ->
                        change.consume()
                        controller.dragBy(-amount / panelWidthPx)
                    }
                }
                .pointerInput(Unit) { detectTapGestures { controller.open() } },
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(width = 5.dp, height = 74.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.55f))
            )
        }
    }
}

// =============================================================================================
// Long-press menu (iOS style, with One UI actions and app shortcuts)
// =============================================================================================

@Composable
private fun AppContextMenu(
    target: ContextMenuTarget,
    settings: LauncherSettings,
    system: SystemStateHolder,
    appearance: IconAppearance,
    iconSize: Dp,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val app = target.app
    val shortcuts by produceState(emptyList<AppShortcut>(), app.packageName) {
        value = withContext(Dispatchers.IO) { AppRepository.loadShortcuts(context, app.packageName) }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(target) {
        appear.snapTo(0f)
        appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f))
    }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val onHome = app.key in settings.homeApps
    val onEdge = app.key in settings.edgeApps

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        val rootWidth = constraints.maxWidth
        val rootHeight = constraints.maxHeight
        val margin = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 10.dp.roundToPx() }
        val menuWidthPx = with(density) { 260.dp.roundToPx() }
        val iconPx = with(density) { iconSize.toPx() }
        val anchor = target.anchor
        val placeBelow = anchor.bottom + gap + menuSize.height < rootHeight - margin

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(Color.Black.copy(alpha = 0.32f))
        )

        // Lifted copy of the icon above the dim, like iOS.
        if (anchor != Rect.Zero) {
            AppIconImage(
                app = app,
                appearance = appearance,
                iconSize = iconSize,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (anchor.center.x - iconPx / 2f).roundToInt(),
                            anchor.top.roundToInt()
                        )
                    }
                    .graphicsLayer {
                        val s = 1f + 0.08f * appear.value
                        scaleX = s
                        scaleY = s
                    }
            )
        }

        GlassSurface(
            modifier = Modifier
                .offset {
                    val x = (anchor.center.x - menuWidthPx / 2f).roundToInt()
                        .coerceIn(margin, (rootWidth - menuWidthPx - margin).coerceAtLeast(margin))
                    val y = if (placeBelow) {
                        (anchor.bottom + gap).roundToInt()
                    } else {
                        (anchor.top - gap - menuSize.height).roundToInt().coerceAtLeast(margin)
                    }
                    IntOffset(x, y)
                }
                .width(260.dp)
                .onSizeChanged { menuSize = it }
                .graphicsLayer {
                    alpha = appear.value
                    val s = 0.82f + 0.18f * appear.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, if (placeBelow) 0f else 1f)
                },
            cornerRadius = 24.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                shortcuts.forEach { shortcut ->
                    MenuRow(label = shortcut.label, bitmap = shortcut.icon, vector = null) {
                        AppRepository.startShortcut(context, shortcut)
                        onDismiss()
                    }
                }
                if (shortcuts.isNotEmpty()) MenuDivider()
                MenuRow(
                    label = if (onHome) "Remove from Home" else "Add to Home",
                    bitmap = null,
                    vector = if (onHome) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.AddCircleOutline
                ) {
                    if (onHome) settings.removeHomeApp(app.key) else settings.addHomeApp(app.key)
                    onDismiss()
                }
                if (settings.edgePanelEnabled) {
                    MenuRow(
                        label = if (onEdge) "Remove from Edge panel" else "Add to Edge panel",
                        bitmap = null,
                        vector = Icons.Rounded.Apps
                    ) {
                        settings.toggleEdgeApp(app.key)
                        onDismiss()
                    }
                }
                MenuRow(label = "App info", bitmap = null, vector = Icons.Rounded.Info) {
                    system.openAppInfo(app.packageName)
                    onDismiss()
                }
                MenuRow(label = "Uninstall", bitmap = null, vector = Icons.Rounded.Delete, destructive = true) {
                    system.uninstall(app.packageName)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    bitmap: ImageBitmap?,
    vector: ImageVector?,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) IosRed else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassClickable(pressedScale = 1.03f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = glassText(TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)),
            color = color,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            vector != null -> Icon(
                imageVector = vector,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(6.dp)
            .background(Color.Black.copy(alpha = 0.12f))
    )
}

/** Opens a URL in the browser (used by widgets when their app is missing). */
@Suppress("unused")
private fun browse(system: SystemStateHolder, url: String) {
    system.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
