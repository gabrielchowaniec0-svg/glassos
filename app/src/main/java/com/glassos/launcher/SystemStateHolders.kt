package com.glassos.launcher

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Immutable UI state models
// ---------------------------------------------------------------------------------------------

data class BatteryState(
    val level: Int = 100,
    val isCharging: Boolean = false
)

data class WifiState(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val ssid: String? = null,
    /** 0..4, as reported by [WifiManager.calculateSignalLevel]. */
    val signalLevel: Int = 0
)

data class BluetoothState(
    val enabled: Boolean = false,
    val connectedName: String? = null
)

// ---------------------------------------------------------------------------------------------
// SystemStateHolder - one place that owns every live system signal the launcher renders
// ---------------------------------------------------------------------------------------------

/**
 * Observes battery, connectivity, audio, display and quick-toggle state and exposes it as Compose
 * snapshot state. Call [start] from onResume and [stop] from onPause.
 *
 * Android does not allow third-party apps to flip several radios directly (airplane mode, mobile
 * data, hotspot, NFC, system dark mode). For those toggles the launcher opens the matching system
 * settings page/panel so the tile still does something useful on a stock Samsung device.
 */
class SystemStateHolder(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val cameraManager: CameraManager? = appContext.getSystemService(CameraManager::class.java)
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private val nfcAdapter: NfcAdapter? = try {
        NfcAdapter.getDefaultAdapter(appContext)
    } catch (e: Exception) {
        null
    }

    // ---- Observable state -------------------------------------------------------------------

    var battery by mutableStateOf(BatteryState())
        private set
    var wifi by mutableStateOf(WifiState())
        private set
    var bluetooth by mutableStateOf(BluetoothState())
        private set
    var flashlightOn by mutableStateOf(false)
        private set
    var ringerMode by mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL)
        private set
    var airplaneMode by mutableStateOf(false)
        private set
    var autoRotate by mutableStateOf(true)
        private set
    var mobileDataEnabled by mutableStateOf(false)
        private set
    var darkMode by mutableStateOf(false)
        private set
    var nfcEnabled by mutableStateOf(false)
        private set
    var brightness by mutableFloatStateOf(0.5f)
        private set
    var volume by mutableFloatStateOf(0.5f)
        private set
    var appsVersion by mutableIntStateOf(0)
        private set
    var notificationAccessGranted by mutableStateOf(false)
        private set

    /** Applies a window-level brightness override (or -1f to clear it). Set by MainActivity. */
    var windowBrightnessApplier: ((Float) -> Unit)? = null

    // ---- Internals ---------------------------------------------------------------------------

    private var started = false
    private var torchCameraId: String? = null
    private var windowBrightnessOverride = false
    private val connectedBluetooth = LinkedHashMap<String, String>()

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                WifiManager.WIFI_STATE_CHANGED_ACTION -> refreshWifiEnabled()
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    refreshBluetooth(requery = true)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> handleBluetoothAcl(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleBluetoothAcl(intent, connected = false)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> refreshRinger()
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> refreshAirplane()
                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> refreshNfc()
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_CHANGED -> appsVersion += 1
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
    ) {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val info = networkCapabilities.transportInfo as? WifiInfo
            val rawSsid = info?.ssid?.removeSurrounding("\"")
            val ssid = rawSsid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
            val level = if (info != null && wifiManager != null) {
                wifiManager.calculateSignalLevel(info.rssi).coerceIn(0, 4)
            } else {
                0
            }
            wifi = wifi.copy(
                enabled = true,
                connected = true,
                ssid = ssid,
                signalLevel = level
            )
        }

        override fun onLost(network: Network) {
            wifi = wifi.copy(connected = false, ssid = null, signalLevel = 0)
            refreshWifiEnabled()
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) flashlightOn = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) flashlightOn = false
        }
    }

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshSettingsDerived()
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            this@SystemStateHolder.onConfigurationChanged(newConfig)
        }

        override fun onLowMemory() {
            // Nothing cached that is worth dropping.
        }
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(appContext, systemReceiver, packageFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager?.registerNetworkCallback(request, wifiCallback, mainHandler)
        } catch (e: Exception) {
            // Missing permission or unsupported device: Wi-Fi tile degrades to enabled/disabled only.
        }

        try {
            torchCameraId = findTorchCameraId()
            if (torchCameraId != null) {
                cameraManager?.registerTorchCallback(ContextCompat.getMainExecutor(appContext), torchCallback)
            }
        } catch (e: Exception) {
            torchCameraId = null
        }

        val contentResolver = appContext.contentResolver
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
        appContext.registerComponentCallbacks(configCallbacks)

        refreshAll()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            appContext.unregisterReceiver(systemReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        try {
            connectivityManager?.unregisterNetworkCallback(wifiCallback)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        try {
            if (torchCameraId != null) cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {
            // Ignore.
        }
        appContext.contentResolver.unregisterContentObserver(settingsObserver)
        try {
            appContext.unregisterComponentCallbacks(configCallbacks)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        darkMode = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun refreshAll() {
        updateBattery(appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        refreshWifiEnabled()
        refreshBluetooth(requery = true)
        refreshRinger()
        refreshAirplane()
        refreshNfc()
        refreshSettingsDerived()
        onConfigurationChanged(appContext.resources.configuration)
        refreshNotificationAccess()
    }

    fun refreshNotificationAccess() {
        notificationAccessGranted = NotificationManagerCompat
            .getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
    }

    // ---- Refresh helpers ---------------------------------------------------------------------

    private fun updateBattery(intent: Intent?) {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val rawScale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val level = when {
            capacity in 0..100 -> capacity
            rawLevel >= 0 && rawScale > 0 -> (rawLevel * 100) / rawScale
            else -> battery.level
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        battery = BatteryState(level = level.coerceIn(0, 100), isCharging = charging)
    }

    private fun refreshWifiEnabled() {
        val enabled = wifiManager?.isWifiEnabled == true
        wifi = if (enabled) {
            wifi.copy(enabled = true)
        } else {
            WifiState(enabled = false)
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshBluetooth(requery: Boolean) {
        val adapter = bluetoothManager?.adapter
        val enabled = adapter?.isEnabled == true
        if (!enabled) {
            connectedBluetooth.clear()
        } else if (requery && adapter != null && hasBluetoothPermission()) {
            queryBluetoothProfile(adapter, BluetoothProfile.A2DP)
            queryBluetoothProfile(adapter, BluetoothProfile.HEADSET)
        }
        bluetooth = BluetoothState(
            enabled = enabled,
            connectedName = if (enabled) connectedBluetooth.values.firstOrNull() else null
        )
    }

    private fun queryBluetoothProfile(adapter: BluetoothAdapter, profile: Int) {
        try {
            adapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices.forEach { device ->
                            connectedBluetooth[device.address] = safeDeviceName(device)
                        }
                    } catch (e: SecurityException) {
                        // Permission revoked mid-flight.
                    } finally {
                        adapter.closeProfileProxy(profileId, proxy)
                    }
                    refreshBluetooth(requery = false)
                }

                override fun onServiceDisconnected(profileId: Int) = Unit
            }, profile)
        } catch (e: SecurityException) {
            // Ignore: label simply stays generic.
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        try {
            device.name ?: "Bluetooth device"
        } catch (e: SecurityException) {
            "Bluetooth device"
        }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun handleBluetoothAcl(intent: Intent, connected: Boolean) {
        val device = intent.bluetoothDeviceExtra() ?: return
        if (connected) {
            connectedBluetooth[device.address] = safeDeviceName(device)
        } else {
            connectedBluetooth.remove(device.address)
        }
        refreshBluetooth(requery = false)
    }

    private fun refreshRinger() {
        ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    }

    private fun refreshAirplane() {
        airplaneMode = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    private fun refreshNfc() {
        nfcEnabled = try {
            nfcAdapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshSettingsDerived() {
        val resolver = appContext.contentResolver
        autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        mobileDataEnabled = Settings.Global.getInt(resolver, "mobile_data", 0) == 1
        refreshAirplane()
        if (!windowBrightnessOverride) {
            val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            brightness = (raw / 255f).coerceIn(0f, 1f)
        }
        val am = audioManager
        if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }
    }

    private fun findTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return try {
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: CameraAccessException) {
            null
        }
    }

    // ---- Actions invoked by the Quick Settings panel -----------------------------------------

    fun toggleFlashlight() {
        val id = torchCameraId ?: return
        try {
            cameraManager?.setTorchMode(id, !flashlightOn)
        } catch (e: CameraAccessException) {
            // Camera is in use by another app.
        } catch (e: IllegalArgumentException) {
            // Torch unavailable.
        }
    }

    fun toggleRinger() {
        val target = if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            AudioManager.RINGER_MODE_VIBRATE
        } else {
            AudioManager.RINGER_MODE_NORMAL
        }
        try {
            audioManager?.ringerMode = target
            refreshRinger()
        } catch (e: SecurityException) {
            startSafely(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    fun toggleAutoRotate() {
        if (Settings.System.canWrite(appContext)) {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (autoRotate) 0 else 1
            )
            refreshSettingsDerived()
        } else {
            startSafely(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))
            )
        }
    }

    fun setBrightness(fraction: Float) {
        val value = fraction.coerceIn(0.02f, 1f)
        brightness = value
        if (Settings.System.canWrite(appContext)) {
            windowBrightnessOverride = false
            windowBrightnessApplier?.invoke(-1f)
            val resolver = appContext.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (value * 255f).roundToInt())
        } else {
            // Without WRITE_SETTINGS the best we can do is dim/brighten the launcher window itself.
            windowBrightnessOverride = true
            windowBrightnessApplier?.invoke(value)
        }
    }

    fun setVolume(fraction: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val level = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        } catch (e: SecurityException) {
            // Do-not-disturb policy blocked the change.
        }
        volume = level.toFloat() / max
    }

    fun openWifiPanel() = startSafely(Intent(Settings.Panel.ACTION_WIFI))

    fun openBluetoothSettings() = startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openInternetPanel() = startSafely(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))

    fun openAirplaneSettings() = startSafely(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))

    fun openNfcSettings() = startSafely(Intent(Settings.ACTION_NFC_SETTINGS))

    fun openDisplaySettings() = startSafely(Intent(Settings.ACTION_DISPLAY_SETTINGS))

    fun openMediaOutput() = startSafely(Intent(Settings.Panel.ACTION_VOLUME))

    fun openSettings() = startSafely(Intent(Settings.ACTION_SETTINGS))

    fun openNotificationAccessSettings() =
        startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openHotspotSettings() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        if (!startSafely(tether)) {
            startSafely(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    fun openDeviceControls() {
        val smartThings = appContext.packageManager.getLaunchIntentForPackage("com.samsung.android.oneconnect")
        if (smartThings == null || !startSafely(smartThings)) {
            startSafely(Intent(Settings.ACTION_CAST_SETTINGS))
        }
    }

    fun launch(intent: Intent) {
        startSafely(intent)
    }

    private fun startSafely(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------------------------

data class GlassNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val icon: ImageBitmap?,
    val contentIntent: PendingIntent?,
    val clearable: Boolean
)

/** Process-wide bridge between the NotificationListenerService and the Compose tray. */
object NotificationRepository {
    private val itemsFlow = MutableStateFlow<List<GlassNotification>>(emptyList())
    val items: StateFlow<List<GlassNotification>> = itemsFlow.asStateFlow()

    private var service: GlassNotificationListener? = null

    internal fun attach(listener: GlassNotificationListener) {
        service = listener
    }

    internal fun detach(listener: GlassNotificationListener) {
        if (service === listener) service = null
        itemsFlow.value = emptyList()
    }

    internal fun replaceAll(list: List<GlassNotification>) {
        itemsFlow.value = list.sortedByDescending { it.postTime }
    }

    internal fun upsert(item: GlassNotification) {
        val updated = itemsFlow.value.filterNot { it.key == item.key } + item
        itemsFlow.value = updated.sortedByDescending { it.postTime }
    }

    internal fun remove(key: String) {
        itemsFlow.value = itemsFlow.value.filterNot { it.key == key }
    }

    fun dismiss(key: String) {
        service?.cancelNotification(key)
        remove(key)
    }

    fun dismissAll() {
        service?.cancelAllNotifications()
        itemsFlow.value = itemsFlow.value.filterNot { it.clearable }
    }
}

/**
 * Receives the device's active notifications so the glass tray can render them. The user has to
 * grant "Notification access" to the launcher once (the tray offers a shortcut to that screen).
 */
class GlassNotificationListener : NotificationListenerService() {

    private val iconCache = HashMap<String, ImageBitmap?>()

    override fun onListenerConnected() {
        NotificationRepository.attach(this)
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        }
        NotificationRepository.replaceAll(active?.mapNotNull { convert(it) } ?: emptyList())
    }

    override fun onListenerDisconnected() {
        NotificationRepository.detach(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val item = convert(sbn)
        if (item != null) {
            NotificationRepository.upsert(item)
        } else {
            NotificationRepository.remove(sbn.key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationRepository.remove(sbn.key)
    }

    private fun convert(sbn: StatusBarNotification): GlassNotification? {
        if (sbn.packageName == packageName) return null
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        val icon = iconCache.getOrPut(sbn.packageName) {
            try {
                packageManager.getApplicationIcon(sbn.packageName).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }

        return GlassNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            icon = icon,
            contentIntent = notification.contentIntent,
            clearable = sbn.isClearable
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Installed apps + Samsung core app binding
// ---------------------------------------------------------------------------------------------

data class AppEntry(
    val label: String,
    val packageName: String,
    val intent: Intent,
    val icon: ImageBitmap
)

data class DockEntry(
    val label: String,
    val intent: Intent,
    val icon: ImageBitmap?,
    val fallbackIcon: ImageVector
)

object AppRepository {

    private const val ICON_SIZE_PX = 144

    private data class DockSpec(
        val label: String,
        val samsungPackage: String,
        val fallbackIcon: ImageVector,
        val fallbackIntent: () -> Intent
    )

    private val dockSpecs: List<DockSpec> = listOf(
        DockSpec(
            label = "Phone",
            samsungPackage = "com.samsung.android.dialer",
            fallbackIcon = Icons.Rounded.Phone,
            fallbackIntent = { Intent(Intent.ACTION_DIAL) }
        ),
        DockSpec(
            label = "Messages",
            samsungPackage = "com.samsung.android.messaging",
            fallbackIcon = Icons.Rounded.ChatBubble,
            fallbackIntent = {
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            }
        ),
        DockSpec(
            label = "Camera",
            samsungPackage = "com.sec.android.app.camera",
            fallbackIcon = Icons.Rounded.CameraAlt,
            fallbackIntent = { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }
        ),
        DockSpec(
            label = "Gallery",
            samsungPackage = "com.sec.android.gallery3d",
            fallbackIcon = Icons.Rounded.PhotoLibrary,
            fallbackIntent = {
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            }
        )
    )

    /** Queries every launchable activity (ACTION_MAIN + CATEGORY_LAUNCHER) except the launcher itself. */
    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(query, 0)
        }

        return resolved
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .map { info ->
                val activity = info.activityInfo
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    packageName = activity.packageName,
                    intent = launch,
                    icon = info.loadIcon(pm).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX).asImageBitmap()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Binds Phone / Messages / Camera / Gallery to the Samsung packages explicitly and falls back to
     * generic system intents when a package is not installed.
     */
    fun loadDock(context: Context): List<DockEntry> {
        val pm = context.packageManager
        return dockSpecs.map { spec ->
            val samsungIntent = pm.getLaunchIntentForPackage(spec.samsungPackage)
            val icon = if (samsungIntent != null) {
                try {
                    pm.getApplicationIcon(spec.samsungPackage)
                        .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                        .asImageBitmap()
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            } else {
                null
            }
            val intent = (samsungIntent ?: spec.fallbackIntent())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            DockEntry(
                label = spec.label,
                intent = intent,
                icon = icon,
                fallbackIcon = spec.fallbackIcon
            )
        }
    }
}
