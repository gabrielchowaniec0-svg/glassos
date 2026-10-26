package com.glassos.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// =============================================================================================
// Media (Now Playing)
// =============================================================================================

data class MediaInfo(
    val title: String,
    val artist: String,
    val artwork: ImageBitmap?,
    val isPlaying: Boolean,
    val packageName: String
)

/**
 * Tracks the active media session (Spotify, YouTube Music, Samsung Music, ...). Uses the
 * notification listener component as the access token, so it only works once Notification access
 * has been granted to GlassOS.
 */
class MediaStateHolder(context: Context) {

    private val appContext = context.applicationContext
    private val sessionManager: MediaSessionManager? =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, GlassNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var info by mutableStateOf<MediaInfo?>(null)
        private set

    private var controller: MediaController? = null
    private var started = false
    private var lastArtworkKey: String? = null
    private var lastArtwork: ImageBitmap? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        choose(controllers ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refresh()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refresh()
        }

        override fun onSessionDestroyed() {
            detachController()
            reload()
        }
    }

    fun start() {
        if (started) return
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            reload()
        } catch (e: SecurityException) {
            // Notification access not granted yet.
            info = null
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            // Ignore.
        }
        detachController()
    }

    private fun reload() {
        val controllers = try {
            sessionManager?.getActiveSessions(listenerComponent) ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        choose(controllers)
    }

    private fun choose(controllers: List<MediaController>) {
        val playing = controllers.firstOrNull { isPlaying(it.playbackState) }
        val chosen = playing ?: controllers.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            detachController()
            controller = chosen
            chosen?.registerCallback(controllerCallback, handler)
        }
        refresh()
    }

    private fun detachController() {
        try {
            controller?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {
            // Ignore.
        }
        controller = null
    }

    private fun isPlaying(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    private fun refresh() {
        val c = controller
        val metadata = c?.metadata
        if (c == null || metadata == null) {
            info = null
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        if (title.isBlank() && artist.isBlank()) {
            info = null
            return
        }
        val artworkKey = "${c.packageName}|$title|$artist"
        val artwork = if (artworkKey == lastArtworkKey) {
            lastArtwork
        } else {
            val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val scaled = bitmap?.let { scaleArtwork(it) }
            lastArtworkKey = artworkKey
            lastArtwork = scaled
            scaled
        }
        info = MediaInfo(
            title = title,
            artist = artist,
            artwork = artwork,
            isPlaying = isPlaying(c.playbackState),
            packageName = c.packageName
        )
    }

    private fun scaleArtwork(bitmap: Bitmap): ImageBitmap? = try {
        val maxEdge = 320
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val scale = maxEdge.toFloat() / maxOf(source.width, source.height)
        val result = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        result.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    fun playPause() {
        val c = controller ?: return
        if (isPlaying(c.playbackState)) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    /** Opens the player app (its session activity when it exposes one). */
    fun openPlayer(system: SystemStateHolder) {
        val c = controller
        if (c == null) {
            system.launchPackage("com.sec.android.app.music", null)
            return
        }
        val activity = c.sessionActivity
        if (activity != null) {
            try {
                activity.send()
                return
            } catch (e: Exception) {
                // Fall through to launching the package.
            }
        }
        system.launchPackage(c.packageName, null)
    }
}

// =============================================================================================
// Weather (Open-Meteo, no API key)
// =============================================================================================

data class WeatherInfo(
    val city: String?,
    val temperature: Int,
    val high: Int,
    val low: Int,
    val code: Int,
    val isDay: Boolean
)

class WeatherHolder(context: Context) {

    private val appContext = context.applicationContext

    var info by mutableStateOf<WeatherInfo?>(null)
        private set

    /** Shown when [info] is null. */
    var message by mutableStateOf("Updating…")
        private set

    private var lastFetchMs = 0L

    /** Fetches fresh weather if the last result is older than [maxAgeMs]. */
    suspend fun refresh(maxAgeMs: Long = 30 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        if (info != null && now - lastFetchMs < maxAgeMs) return

        if (!hasLocationPermission()) {
            message = "Allow location for weather"
            return
        }
        val location = findLocation()
        if (location == null) {
            if (info == null) message = "Location unavailable"
            return
        }
        val fetched = fetch(location)
        if (fetched != null) {
            info = fetched
            lastFetchMs = now
        } else if (info == null) {
            message = "Weather unavailable"
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun findLocation(): Location? {
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return null
        val providers = try {
            lm.getProviders(true)
        } catch (e: SecurityException) {
            return null
        }
        val recent = providers.mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 3 * 60 * 60 * 1000L) return recent

        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return recent
        }
        val fresh = withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(appContext)) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (e: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (e: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return fresh ?: recent
    }

    private suspend fun fetch(location: Location): WeatherInfo? = withContext(Dispatchers.IO) {
        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1%s",
            location.latitude,
            location.longitude,
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        )
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            val body = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            WeatherInfo(
                city = cityName(location),
                temperature = current.getDouble("temperature_2m").roundToInt(),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1
            )
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun cityName(location: Location): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            Geocoder(appContext, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }
    } catch (e: Exception) {
        null
    }
}

/** WMO weather code -> label. */
fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly Clear"
    2 -> "Partly Cloudy"
    3 -> "Cloudy"
    45, 48 -> "Foggy"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    95, 96, 99 -> "Thunderstorms"
    else -> "Weather"
}

/** WMO weather code -> icon. */
fun weatherIcon(code: Int, isDay: Boolean): ImageVector = when (code) {
    0, 1 -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    2 -> Icons.Rounded.WbCloudy
    3, 45, 48 -> Icons.Rounded.Cloud
    51, 53, 55, 56, 57 -> Icons.Rounded.Grain
    61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Rounded.Umbrella
    71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.FlashOn
    else -> Icons.Rounded.Cloud
}
