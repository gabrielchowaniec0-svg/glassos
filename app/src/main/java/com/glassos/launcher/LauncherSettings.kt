package com.glassos.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How app icons are drawn on the home screen, dock, drawer and edge panel. */
enum class IconStyle(val label: String) {
    Default("Default"),

    /** iOS 26 "Clear": white glyph on a refracting liquid glass tile. */
    Clear("Clear glass"),

    /** Glyph tinted with the accent colour on a liquid glass tile. */
    Tinted("Tinted")
}

/** Built-in wallpapers. [Photo] uses the image the user picked from the gallery. */
enum class WallpaperPreset(val label: String) {
    Aurora("Aurora"),
    Ocean("Ocean"),
    Sunset("Sunset"),
    Graphite("Graphite"),
    Photo("Photo")
}

/** A home screen grid. One UI keeps separate grids for the Fold cover and main screens. */
enum class GridSize(val columns: Int, val rows: Int) {
    G4x5(4, 5),
    G4x6(4, 6),
    G5x6(5, 6),
    G6x5(6, 5),
    G6x6(6, 6),
    G7x6(7, 6);

    val label: String get() = "${columns}x$rows"

    companion object {
        val coverOptions = listOf(G4x5, G4x6, G5x6)
        val mainOptions = listOf(G6x5, G6x6, G7x6)
    }
}

/**
 * Persistent launcher preferences exposed as Compose snapshot state. Every setter writes through to
 * SharedPreferences immediately, so changes survive restarts and apply live across the UI.
 */
class LauncherSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("glassos_settings", Context.MODE_PRIVATE)

    // ---- Appearance ---------------------------------------------------------------------------

    var iconStyle by mutableStateOf(readEnum(KEY_ICON_STYLE, IconStyle.Default))
        private set

    var tintColor by mutableIntStateOf(prefs.getInt(KEY_TINT_COLOR, TINT_SWATCHES[0]))
        private set

    var wallpaper by mutableStateOf(readEnum(KEY_WALLPAPER, WallpaperPreset.Aurora))
        private set

    /** Bumped every time a new photo wallpaper is saved so the bitmap reloads. */
    var wallpaperVersion by mutableIntStateOf(prefs.getInt(KEY_WALLPAPER_VERSION, 0))
        private set

    /** 0 = perfectly clear glass, 1 = heavily frosted. */
    var frost by mutableFloatStateOf(prefs.getFloat(KEY_FROST, 0f))
        private set

    /** Multiplier for edge refraction and chromatic dispersion (0.3 .. 1.6). */
    var refraction by mutableFloatStateOf(prefs.getFloat(KEY_REFRACTION, 1f))
        private set

    /** How much the home screen darkens behind open panels (no blur is ever applied). */
    var panelDim by mutableFloatStateOf(prefs.getFloat(KEY_PANEL_DIM, 0.22f))
        private set

    // ---- Home screen --------------------------------------------------------------------------

    var coverGrid by mutableStateOf(readEnum(KEY_COVER_GRID, GridSize.G4x6))
        private set

    var mainGrid by mutableStateOf(readEnum(KEY_MAIN_GRID, GridSize.G6x5))
        private set

    var showWidgets by mutableStateOf(prefs.getBoolean(KEY_SHOW_WIDGETS, true))
        private set

    var showLabels by mutableStateOf(prefs.getBoolean(KEY_SHOW_LABELS, true))
        private set

    var showSearchPill by mutableStateOf(prefs.getBoolean(KEY_SHOW_SEARCH, true))
        private set

    var addNewApps by mutableStateOf(prefs.getBoolean(KEY_ADD_NEW_APPS, true))
        private set

    // ---- Panels -------------------------------------------------------------------------------

    /** Fraction of the screen width (from the left) that opens notifications instead of controls. */
    var splitFraction by mutableFloatStateOf(prefs.getFloat(KEY_SPLIT, 0.6f))
        private set

    var edgePanelEnabled by mutableStateOf(prefs.getBoolean(KEY_EDGE_PANEL, true))
        private set

    // ---- Layout data --------------------------------------------------------------------------

    /** Ordered component keys ("package/activity") pinned to the home screen. */
    var homeApps by mutableStateOf(readList(KEY_HOME_APPS))
        private set

    /** Ordered component keys shown in the edge panel. */
    var edgeApps by mutableStateOf(readList(KEY_EDGE_APPS))
        private set

    /** Every component key the launcher has already seen, used to detect newly installed apps. */
    var knownApps by mutableStateOf(readList(KEY_KNOWN_APPS).toSet())
        private set

    val homeInitialized: Boolean get() = prefs.getBoolean(KEY_HOME_INITIALIZED, false)

    // ---- Setters ------------------------------------------------------------------------------

    fun updateIconStyle(value: IconStyle) {
        iconStyle = value
        prefs.edit().putString(KEY_ICON_STYLE, value.name).apply()
    }

    fun updateTintColor(value: Int) {
        tintColor = value
        prefs.edit().putInt(KEY_TINT_COLOR, value).apply()
    }

    fun updateWallpaper(value: WallpaperPreset) {
        wallpaper = value
        prefs.edit().putString(KEY_WALLPAPER, value.name).apply()
    }

    fun onPhotoWallpaperSaved() {
        wallpaperVersion += 1
        wallpaper = WallpaperPreset.Photo
        prefs.edit()
            .putInt(KEY_WALLPAPER_VERSION, wallpaperVersion)
            .putString(KEY_WALLPAPER, WallpaperPreset.Photo.name)
            .apply()
    }

    fun updateFrost(value: Float) {
        frost = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_FROST, frost).apply()
    }

    fun updateRefraction(value: Float) {
        refraction = value.coerceIn(0.3f, 1.6f)
        prefs.edit().putFloat(KEY_REFRACTION, refraction).apply()
    }

    fun updatePanelDim(value: Float) {
        panelDim = value.coerceIn(0f, 0.6f)
        prefs.edit().putFloat(KEY_PANEL_DIM, panelDim).apply()
    }

    fun updateCoverGrid(value: GridSize) {
        coverGrid = value
        prefs.edit().putString(KEY_COVER_GRID, value.name).apply()
    }

    fun updateMainGrid(value: GridSize) {
        mainGrid = value
        prefs.edit().putString(KEY_MAIN_GRID, value.name).apply()
    }

    fun updateShowWidgets(value: Boolean) {
        showWidgets = value
        prefs.edit().putBoolean(KEY_SHOW_WIDGETS, value).apply()
    }

    fun updateShowLabels(value: Boolean) {
        showLabels = value
        prefs.edit().putBoolean(KEY_SHOW_LABELS, value).apply()
    }

    fun updateShowSearchPill(value: Boolean) {
        showSearchPill = value
        prefs.edit().putBoolean(KEY_SHOW_SEARCH, value).apply()
    }

    fun updateAddNewApps(value: Boolean) {
        addNewApps = value
        prefs.edit().putBoolean(KEY_ADD_NEW_APPS, value).apply()
    }

    fun updateSplitFraction(value: Float) {
        splitFraction = value.coerceIn(0.3f, 0.8f)
        prefs.edit().putFloat(KEY_SPLIT, splitFraction).apply()
    }

    fun updateEdgePanelEnabled(value: Boolean) {
        edgePanelEnabled = value
        prefs.edit().putBoolean(KEY_EDGE_PANEL, value).apply()
    }

    fun replaceHomeApps(keys: List<String>) {
        homeApps = keys.distinct()
        prefs.edit()
            .putString(KEY_HOME_APPS, homeApps.joinToString(SEPARATOR))
            .putBoolean(KEY_HOME_INITIALIZED, true)
            .apply()
    }

    /** Clears the home layout; it is rebuilt from the One UI defaults on the next app load. */
    fun resetHomeLayout() {
        homeApps = emptyList()
        prefs.edit()
            .remove(KEY_HOME_APPS)
            .putBoolean(KEY_HOME_INITIALIZED, false)
            .apply()
    }

    fun addHomeApp(key: String) {
        if (key !in homeApps) replaceHomeApps(homeApps + key)
    }

    fun removeHomeApp(key: String) {
        replaceHomeApps(homeApps - key)
    }

    fun replaceEdgeApps(keys: List<String>) {
        edgeApps = keys.distinct().take(MAX_EDGE_APPS)
        prefs.edit().putString(KEY_EDGE_APPS, edgeApps.joinToString(SEPARATOR)).apply()
    }

    fun toggleEdgeApp(key: String) {
        replaceEdgeApps(if (key in edgeApps) edgeApps - key else edgeApps + key)
    }

    fun replaceKnownApps(keys: Set<String>) {
        knownApps = keys
        prefs.edit().putString(KEY_KNOWN_APPS, keys.joinToString(SEPARATOR)).apply()
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val stored = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    private fun readList(key: String): List<String> =
        prefs.getString(key, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    companion object {
        const val MAX_EDGE_APPS = 10
        private const val SEPARATOR = "\n"

        /** Accent swatches offered for the tinted icon style (ARGB). */
        val TINT_SWATCHES: List<Int> = listOf(
            0xFF64B5FF.toInt(),
            0xFFFF9F43.toInt(),
            0xFF5EE6A8.toInt(),
            0xFFFF6FAE.toInt(),
            0xFFC59BFF.toInt()
        )

        private const val KEY_ICON_STYLE = "icon_style"
        private const val KEY_TINT_COLOR = "tint_color"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_WALLPAPER_VERSION = "wallpaper_version"
        private const val KEY_FROST = "frost"
        private const val KEY_REFRACTION = "refraction"
        private const val KEY_PANEL_DIM = "panel_dim"
        private const val KEY_COVER_GRID = "cover_grid"
        private const val KEY_MAIN_GRID = "main_grid"
        private const val KEY_SHOW_WIDGETS = "show_widgets"
        private const val KEY_SHOW_LABELS = "show_labels"
        private const val KEY_SHOW_SEARCH = "show_search"
        private const val KEY_ADD_NEW_APPS = "add_new_apps"
        private const val KEY_SPLIT = "split_fraction"
        private const val KEY_EDGE_PANEL = "edge_panel"
        private const val KEY_HOME_APPS = "home_apps"
        private const val KEY_EDGE_APPS = "edge_apps"
        private const val KEY_KNOWN_APPS = "known_apps"
        private const val KEY_HOME_INITIALIZED = "home_initialized"
    }
}
