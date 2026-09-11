package com.flue.launcher.watchface.standard

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.net.wifi.WifiManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

internal fun watchChargingState(status: Int, plugged: Int, previous: Boolean?): Boolean? = when {
    status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        plugged != 0 -> true
    status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
        status == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
    else -> previous
}

internal fun watchMotionSensorTypes(hasGyroscope: Boolean): List<Int> = buildList {
    if (hasGyroscope) add(Sensor.TYPE_GYROSCOPE)
    add(Sensor.TYPE_ACCELEROMETER)
}

internal fun watchHeartRateValue(hasPermission: Boolean, value: Int?): Int? =
    value?.takeIf { hasPermission }

internal fun watchSensorActiveInMode(sensorType: Int, ambientMode: Boolean): Boolean =
    !ambientMode || sensorType == Sensor.TYPE_STEP_COUNTER

/**
 * 位置权限的单一口径：谁要坐标，就从这里问、从这里申请。
 *
 * ## 为什么要有这个 object
 *
 * 在这之前，全 App **唯一**的运行时位置申请点在「设置 → 表盘天气 → 使用系统定位」
 * 那一行。而 {alat} / {alon} / {aalt} 一族读的正是天气落盘的那份坐标 —— 于是一张只
 * 显示经纬度、完全不显示天气的表盘，用户没有任何理由去点天气设置，坐标就永远是空的。
 * 老师收到的「经纬度变量读不出来」就是这个：不是没实现，是没人去申请那个权限。
 *
 * 所以渲染/编辑侧需要一个能自己判断「这张表盘要坐标吗、现在有权限吗」的入口，
 * 而不是把判断散在各个 Activity 里各写一遍。
 *
 * ## 为什么两个权限都要
 *
 * `ACCESS_FINE_LOCATION` 是 Android 12+ 弹窗里「精确位置」那一档的前提，且
 * GPS / passive provider 缺了它会直接抛 SecurityException（见
 * [filterWatchWeatherLocationProvidersByPermission]）。但用户完全可以只给「大致位置」，
 * 那也够天气和表盘用 —— 所以 [isGranted] 是**任一即可**，绝不把只给了粗定位的用户
 * 当成没授权反复骚扰。
 */
object WatchLocationPermissionPolicy {

    /**
     * 一次申请里要一起问的两个权限。
     *
     * 必须**同时**提交：Android 12+ 只有在同一次请求里同时包含粗/精两个权限时，
     * 系统弹窗才会给出「精确 / 大致」的二选一；单独请求 FINE 会被静默降级成粗定位，
     * 之后再想拿精确位置就只能去系统设置里手动改。
     */
    val PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** 给传统 `onRequestPermissionsResult` 风格的调用方；Activity Result 风格用不到。 */
    const val REQUEST_CODE = 0x10C

    /** 粗或精任一被授予即算可用 —— 表盘坐标和天气都不需要米级精度。 */
    fun isGranted(context: Context): Boolean = PERMISSIONS.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 单独问精确位置：决定要不要把 GPS / passive provider 报给 LocationManager。 */
    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 这段表盘文本要不要一次授权引导：用到了位置标签、且当前没有任何位置权限。
     *
     * 传 watch.xml 全文最准（Lua 脚本与表达式里的标签也算得进去），传单个属性值也行。
     * 判定口径见 [WatchmakerTagResolver.usesLocationTags]。
     */
    fun isRequestNeededFor(context: Context, watchFaceText: String): Boolean =
        WatchmakerTagResolver.usesLocationTags(watchFaceText) && !isGranted(context)

    /**
     * 发起申请。已经授权时**什么都不做**，所以调用方可以无脑调，不必自己先判一次。
     *
     * 用 [ActivityCompat] 而不是 Activity Result contract：这样任何 Activity 都能直接
     * 调，不要求宿主是 Compose、也不要求它提前 register 一个 launcher。Compose 侧
     * 想用 contract 的话，把 [PERMISSIONS] 交给 `RequestMultiplePermissions` 即可。
     */
    fun request(activity: Activity) {
        if (isGranted(activity)) return
        runCatching { ActivityCompat.requestPermissions(activity, PERMISSIONS, REQUEST_CODE) }
    }
}

internal class AndroidWatchDataProvider(
    context: Context,
    weatherSource: WatchWeatherSource? = null,
) : WatchDataProvider, SensorEventListener {
    private val appContext = context.applicationContext
    /**
     * Null means the application-owned cache/default seam, not "weather is
     * permanently disabled". The same default is used by standard, JB and
     * editor previews, while tests or a future worker can still inject a
     * deterministic source explicitly.
     */
    private val weatherSource: WatchWeatherSource = weatherSource
        ?: WatchWeatherSources.applicationSource
        ?: WatchWeatherRepository.default(appContext)
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val hasGyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val hasRotationVectorSensor =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    private val stepPreferences = appContext.getSharedPreferences(STEP_PREFERENCES, Context.MODE_PRIVATE)
    private val stepLock = Any()
    private var ambientMode = false
    fun setAmbientMode(ambient: Boolean) {
        if (ambientMode == ambient) return
        val restart = started
        if (restart) stop()
        ambientMode = ambient
        if (!ambient) brightSinceElapsedMs = SystemClock.elapsedRealtime()
        if (restart) start()
    }
    private var started = false
    private var batteryReceiverRegistered = false
    private var connectivityReceiverRegistered = false

    @Volatile
    private var batteryPercent: Int? = null

    @Volatile
    private var charging: Boolean? = null

    @Volatile
    private var steps: Int? = null

    @Volatile
    private var heartRate: Int? = null

    @Volatile
    private var gyroscope: Triple<Float, Float, Float>? = null

    @Volatile
    private var accelerometer: Triple<Float, Float, Float>? = null
    private var previousRoll = 0f
    private var previousPitch = 0f
    private var smoothedSimulatedPitch = 0f
    private var smoothedSimulatedRoll = 0f

    @Volatile
    private var wifiSignalFraction: Float? = null

    @Volatile
    private var bluetoothEnabled: Boolean? = null

    /** 电池温度（摄氏）。与电量同一条广播里来。 */
    @Volatile
    private var batteryTemperatureCelsius: Float? = null

    /** 罗盘方位角 0–360。优先用 ROTATION_VECTOR，没有就用磁力计 + 加速度计融合。 */
    @Volatile
    private var compassHeading: Float? = null

    @Volatile
    private var magneticField: FloatArray? = null

    /**
     * 亮屏时刻（`SystemClock.elapsedRealtime()` 基准）。供 {abss} / {abssl} 用。
     *
     * 用开机以来的单调时钟而不是墙上时钟：改系统时间不该让「亮屏多久了」跳。
     */
    @Volatile
    private var brightSinceElapsedMs: Long? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> if (!ambientMode) { brightSinceElapsedMs = SystemClock.elapsedRealtime() }
                Intent.ACTION_SCREEN_OFF -> brightSinceElapsedMs = null
            }
        }
    }

    private var screenReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::updateBattery)
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.RSSI_CHANGED_ACTION -> refreshWifiSignal()
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshBluetoothState()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        refreshBatteryFallback()
        refreshWifiSignal()
        refreshBluetoothState()
        // Cache-first and nonblocking: the renderer keeps showing the last good
        // snapshot while every newly-visible provider asks the application
        // repository for a foreground refresh immediately.
        (weatherSource as? WatchWeatherRepository)?.requestRefresh()
            ?: runCatching { weatherSource.snapshot(System.currentTimeMillis()) }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { stickyIntent ->
            batteryReceiverRegistered = true
            stickyIntent?.let(::updateBattery)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                connectivityReceiver,
                IntentFilter().apply {
                    addAction(WifiManager.RSSI_CHANGED_ACTION)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            connectivityReceiverRegistered = true
        }
        // {abright} / {abss} / {abssl}：亮屏起点靠这两条广播维护。注册时如果屏幕
        // 已经亮着，就把起点当作现在 —— 少算一段总比谎报一个大数好。
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            screenReceiverRegistered = true
            if (powerManager?.isInteractive != false) {
                brightSinceElapsedMs = SystemClock.elapsedRealtime()
            }
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            restoreDailySteps()
            registerSensor(Sensor.TYPE_STEP_COUNTER)
        } else {
            steps = null
        }
        if (!ambientMode && hasHeartRatePermission(appContext)) {
            registerSensor(Sensor.TYPE_HEART_RATE)
        }
        if (ambientMode) return
        watchMotionSensorTypes(hasGyroscopeSensor).forEach(::registerSensor)
        // 罗盘：ROTATION_VECTOR 单个传感器就够且更稳；没有它才退回磁力计，
        // 与加速度计一起做姿态融合（加速度计上面已经在监听了）。
        if (hasRotationVectorSensor) {
            registerSensor(Sensor.TYPE_ROTATION_VECTOR)
        } else {
            registerSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
        if (batteryReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        if (connectivityReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(connectivityReceiver) }
            connectivityReceiverRegistered = false
        }
        if (screenReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
    }

    override fun snapshot(nowEpochMs: Long): WatchDataSnapshot {
        val weather = runCatching { weatherSource.snapshot(nowEpochMs) }
            .getOrNull()
            ?.let(::canonicalWatchWeatherSnapshot)
        val gyro = gyroscope
        val accel = accelerometer
        val currentHeartRate = watchHeartRateValue(
            hasPermission = hasHeartRatePermissionCached(),
            value = heartRate,
        )
        val slow = slowDeviceState()
        return WatchDataSnapshot(
            batteryPercent = batteryPercent,
            isCharging = charging,
            steps = steps,
            heartRateBpm = currentHeartRate,
            gyroscopeX = gyro?.first,
            gyroscopeY = gyro?.second,
            gyroscopeZ = gyro?.third,
            accelerometerX = accel?.first,
            accelerometerY = accel?.second,
            accelerometerZ = accel?.third,
            wifiSignalFraction = wifiSignalFraction,
            bluetoothEnabled = bluetoothEnabled,
            // {cnc}：渲染层不能反向依赖启动器的 NotificationListenerService，
            // 由宿主通过 WatchNotificationCounts 这个 seam 推进来。
            notificationCount = WatchNotificationCounts.current(),
            // {d24h}：DateFormat 需要 Context，而 tag 解析器是纯函数，所以在
            // provider 侧读。改制式不是实时性敏感的变更，短 TTL 缓存换掉每帧
            // 一次的 Settings 查询（见 [is24HourFormatCached]）。
            is24Hour = is24HourFormatCached(),
            weather = weather,
            // Compatibility fields retain the first slice's public snapshot
            // shape. New renderers consume [weather], whose values are
            // canonical Celsius with an explicit presentation unit.
            weatherTemperature = weatherTemperatureNumber(
                weather?.temperatureCelsius,
                weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS,
            ),
            weatherCondition = weather?.condition,
            weatherIcon = weather?.icon,
            weatherHigh = weatherTemperatureNumber(
                weather?.highCelsius,
                weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS,
            ),
            weatherLow = weatherTemperatureNumber(
                weather?.lowCelsius,
                weather?.displayUnit ?: WatchTemperatureUnit.CELSIUS,
            ),
            weatherTemperatureUnit = weather?.displayUnit?.symbol,
            weatherHumidity = weather?.humidity,
            weatherLocation = weather?.location,

            // ── N5：给原本只能渲染 `--` 的那批变量供数据 ─────────────────────
            // 全部走 [slowDeviceState]：这些量要么几乎不变（型号、屏幕形状），要么
            // 变得很慢（内存、存储、音量、亮度、定位），而 snapshot() 是**每帧**调用的。
            // 不节流的话每帧都会跑一次 StatFs、一次 getMemoryInfo、一次
            // Settings.System 的 ContentResolver 查询和两次 SharedPreferences 读 ——
            // 那是实打实的掉帧。
            batteryTemperatureCelsius = batteryTemperatureCelsius,
            // 只报真正查到的两个量；「已用」由解析器按「总量 − 可用」推，免得两处各算一遍。
            ramFreeBytes = slow.ramFreeBytes,
            ramTotalBytes = slow.ramTotalBytes,
            storageFreeBytes = slow.storageFreeBytes,
            storageTotalBytes = slow.storageTotalBytes,
            deviceManufacturer = Build.MANUFACTURER.takeIf(String::isNotBlank),
            deviceModel = Build.MODEL.takeIf(String::isNotBlank),
            deviceName = Build.DEVICE.takeIf(String::isNotBlank),
            osVersion = Build.VERSION.RELEASE.takeIf(String::isNotBlank),
            watchName = slow.watchName,
            screenRound = slow.screenRound,
            screenFlatTyre = slow.screenFlatTyre,
            volumePercent = slow.volumePercent,
            screenBrightness = slow.screenBrightness,
            lowPowerMode = slow.lowPowerMode,
            darkMode = slow.darkMode,
            lastRebootText = lastRebootText(nowEpochMs),
            brightMode = !ambientMode && powerManager?.isInteractive != false,
            // 启动器托管的表盘没有真正的息屏低位模式，所以恒为 false 而不是「不知道」。
            dimLowBitOnly = false,
            millisSinceBright = if (ambientMode) null else brightSinceElapsedMs?.let { SystemClock.elapsedRealtime() - it },
            compassHeadingDegrees = compassHeading,
            latitude = slow.latitude,
            longitude = slow.longitude,
            altitudeMeters = slow.altitudeMeters,
            stopwatchRunning = WatchStopwatch.isRunning,
            stopwatchElapsedMs = WatchStopwatch.elapsedMs(SystemClock.elapsedRealtime()),
            calendarEvents = calendarEvents(nowEpochMs),
        )
    }

    /**
     * 日程。单独一层节流，TTL 比其他慢变量长得多。
     *
     * `CalendarContract.Instances` 的查询要跨进程走 ContentProvider、还要展开重复日程，
     * 比一次 `StatFs` 贵一个量级；而「下一个会议几点」这种信息一分钟级的滞后完全无感。
     *
     * 返回 null 表示读不到（没授权）—— 与「读到了但没有日程」（空列表）不是一回事，
     * 前者渲染 `--`，后者让 {cex} 答 "false"。
     */
    private fun calendarEvents(nowEpochMs: Long): List<WatchCalendarEvent>? {
        val now = SystemClock.elapsedRealtime()
        if (calendarReadAtMs != 0L && now - calendarReadAtMs < CALENDAR_TTL_MS) {
            return calendarCache
        }
        calendarCache = readWatchCalendarEvents(appContext, nowEpochMs)
        calendarReadAtMs = now
        return calendarCache
    }

    /**
     * 变化很慢、但读起来很贵的那批设备状态。
     *
     * 每 [SLOW_STATE_TTL_MS] 才真正重读一次；表盘上这些字段的语义都容忍几秒的滞后
     * （内存占用、剩余空间、音量、亮度、坐标），而每帧重读它们会直接掉帧。
     */
    private class SlowDeviceState(
        val ramTotalBytes: Long?,
        val ramFreeBytes: Long?,
        val storageTotalBytes: Long?,
        val storageFreeBytes: Long?,
        val watchName: String?,
        val screenRound: Boolean?,
        val screenFlatTyre: Boolean?,
        val volumePercent: Int?,
        val screenBrightness: Int?,
        val lowPowerMode: Boolean?,
        val darkMode: Boolean?,
        val latitude: Double?,
        val longitude: Double?,
        val altitudeMeters: Double?,
    )

    @Volatile
    private var slowState: SlowDeviceState? = null

    @Volatile
    private var slowStateReadAtMs: Long = 0L

    @Volatile
    private var calendarCache: List<WatchCalendarEvent>? = null

    @Volatile
    private var calendarReadAtMs: Long = 0L

    // snapshot() 是每帧调用的，而这两项（心率权限检查、{d24h} 的改制式查询）
    // 都是系统设置级的慢变量；按 [SLOW_STATE_TTL_MS] 缓存，省掉每帧的
    // checkSelfPermission 与 Settings.System 查询。
    @Volatile
    private var cachedIs24Hour: Boolean? = null

    @Volatile
    private var is24HourReadAtMs = 0L

    @Volatile
    private var cachedHeartRatePermission: Boolean? = null

    @Volatile
    private var heartRatePermissionReadAtMs = 0L

    private fun is24HourFormatCached(): Boolean? {
        val cached = cachedIs24Hour
        val now = SystemClock.elapsedRealtime()
        if (cached != null && now - is24HourReadAtMs < SLOW_STATE_TTL_MS) return cached
        val fresh = runCatching { DateFormat.is24HourFormat(appContext) }.getOrNull() ?: return cached
        cachedIs24Hour = fresh
        is24HourReadAtMs = now
        return fresh
    }

    private fun hasHeartRatePermissionCached(): Boolean {
        val cached = cachedHeartRatePermission
        val now = SystemClock.elapsedRealtime()
        if (cached != null && now - heartRatePermissionReadAtMs < SLOW_STATE_TTL_MS) return cached
        val fresh = hasHeartRatePermission(appContext)
        cachedHeartRatePermission = fresh
        heartRatePermissionReadAtMs = now
        return fresh
    }

    private fun slowDeviceState(): SlowDeviceState {
        val now = SystemClock.elapsedRealtime()
        val cached = slowState
        if (cached != null && now - slowStateReadAtMs < SLOW_STATE_TTL_MS) return cached
        val ram = ramInfo()
        val storage = storageInfo()
        val configuration = appContext.resources.configuration
        val location = activeLocation()
        val fresh = SlowDeviceState(
            ramTotalBytes = ram?.first,
            ramFreeBytes = ram?.second,
            storageTotalBytes = storage?.first,
            storageFreeBytes = storage?.second,
            watchName = watchName(),
            screenRound = configuration.isScreenRound,
            // 「扁轮胎」= 圆屏但底部被截平，于是高度小于宽度。Wear 上没有专门的
            // 查询接口，这个比值判断是通行做法。
            screenFlatTyre = configuration.isScreenRound &&
                configuration.screenHeightDp < configuration.screenWidthDp,
            volumePercent = mediaVolumePercent(),
            screenBrightness = runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull(),
            lowPowerMode = powerManager?.isPowerSaveMode,
            darkMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES,
            latitude = location?.latitude,
            longitude = location?.longitude,
            // {aalt} 只使用定位对象实际提供的海拔；中国天气未提供时保持缺失，按变量约定显示 0。
            altitudeMeters = location?.elevation,
        )
        slowState = fresh
        slowStateReadAtMs = now
        return fresh
    }

    /** 总内存 / 可用内存，字节。 */
    private fun ramInfo(): Pair<Long, Long>? {
        val manager = activityManager ?: return null
        return runCatching {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem.takeIf { it > 0L }?.let { it to info.availMem.coerceIn(0L, it) }
        }.getOrNull()
    }

    /** 数据分区的总容量 / 可用容量，字节。 */
    private fun storageInfo(): Pair<Long, Long>? = runCatching {
        val stats = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stats.blockCountLong * stats.blockSizeLong
        val available = stats.availableBlocksLong * stats.blockSizeLong
        total.takeIf { it > 0L }?.let { it to available.coerceIn(0L, it) }
    }.getOrNull()

    /** {avol}：媒体音量的百分比。 */
    private fun mediaVolumePercent(): Int? {
        val manager = audioManager ?: return null
        return runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return@runCatching null
            manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        }.getOrNull()
    }

    /**
     * {awname}：用户给设备起的名字，取不到就退回「厂商 + 型号」。
     *
     * `Settings.Global.DEVICE_NAME` 在部分 ROM 上是空的，而这个标签在表盘上通常
     * 直接当标题显示，空着比退化的名字更难看。
     */
    private fun watchName(): String? {
        val configured = runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf(String::isNotBlank)
        return configured
            ?: listOf(Build.MANUFACTURER, Build.MODEL)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
    }

    /**
     * {areboot}：上次重启时刻 `yyyy-MM-dd HH:mm`。
     *
     * 用「当前时间 − 开机以来的时长」推出来。跟着传入的 [nowEpochMs] 算而不是自己取
     * 当前时间，快照才是自洽的一帧。
     */
    private fun lastRebootText(nowEpochMs: Long): String? = runCatching {
        val bootedAt = nowEpochMs - SystemClock.elapsedRealtime()
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(bootedAt))
    }.getOrNull()

    /**
     * {alat} / {alon} / {aalt}：只读天气已经落盘的那份定位，不在渲染路径上发起定位请求。
     *
     * 这里刻意不申请权限 —— 渲染线程不该弹系统对话框。要授权引导的话由宿主 Activity
     * 走 [WatchLocationPermissionPolicy]（先用 `isRequestNeededFor` 判断这张表盘是否
     * 真的用到了坐标，再 `request`），免得没用位置标签的表盘也被弹一次。
     */
    private fun activeLocation(): WatchWeatherLocation? =
        (weatherSource as? WatchWeatherRepository)?.activeLocation()

    override fun requestRefresh() {
        if (!started) return
        refreshBatteryFallback()
        refreshWifiSignal()
        refreshBluetoothState()
        (weatherSource as? WatchWeatherRepository)?.requestRefresh()
            ?: runCatching { weatherSource.snapshot(System.currentTimeMillis()) }
        if (!ambientMode && hasHeartRatePermission(appContext)) {
            // 很多手表的心率传感器只在（重新）注册监听时才开始一次新的测量，
            // 所以点按心率组件时重新武装传感器来触发刷新。这也覆盖了
            // “先启动、后授权”的场景：授权后的第一次点按会把传感器接上。
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (sensor != null) {
                runCatching { sensorManager.unregisterListener(this, sensor) }
                registerSensor(Sensor.TYPE_HEART_RATE)
            }
        } else {
            heartRate = null
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        if (sensorEvent.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            compassHeading = headingFromRotationVector(sensorEvent.values)
            return
        }
        if (sensorEvent.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val x = sensorEvent.values.getOrNull(0)?.takeIf(Float::isFinite) ?: return
            val y = sensorEvent.values.getOrNull(1)?.takeIf(Float::isFinite) ?: return
            val z = sensorEvent.values.getOrNull(2)?.takeIf(Float::isFinite) ?: return
            magneticField = floatArrayOf(x, y, z)
            compassHeading = headingFromMagnetometer()
            return
        }
        if (sensorEvent.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val x = sensorEvent.values.getOrNull(0)?.takeIf(Float::isFinite) ?: return
            val y = sensorEvent.values.getOrNull(1)?.takeIf(Float::isFinite) ?: return
            val z = sensorEvent.values.getOrNull(2)?.takeIf(Float::isFinite) ?: return
            gyroscope = Triple(x, y, z)
            return
        }
        if (sensorEvent.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = sensorEvent.values.getOrNull(0)?.takeIf(Float::isFinite) ?: return
            val y = sensorEvent.values.getOrNull(1)?.takeIf(Float::isFinite) ?: return
            val z = sensorEvent.values.getOrNull(2)?.takeIf(Float::isFinite) ?: return
            accelerometer = Triple(x, y, z)
            if (hasGyroscopeSensor) return
            // Wear OS devices frequently omit a physical gyro. Match CloX's
            // fallback by deriving a low-pass angular signal from tilt.
            val roll = Math.toDegrees(
                Math.atan2(x.toDouble(), Math.sqrt((y * y + z * z).toDouble()))
            ).toFloat()
            val pitch = Math.toDegrees(
                Math.atan2(y.toDouble(), Math.sqrt((x * x + z * z).toDouble()))
            ).toFloat()
            val deltaRoll = roll - previousRoll
            val deltaPitch = pitch - previousPitch
            smoothedSimulatedPitch = deltaPitch * 0.1f + smoothedSimulatedPitch * 0.9f
            smoothedSimulatedRoll = deltaRoll * 0.1f + smoothedSimulatedRoll * 0.9f
            previousRoll = roll
            previousPitch = pitch
            gyroscope = Triple(-smoothedSimulatedPitch, smoothedSimulatedRoll, 0f)
            return
        }
        val value = sensorEvent.values.firstOrNull()?.takeIf(Float::isFinite) ?: return
        when (sensorEvent.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> updateDailySteps(value.roundToInt().coerceAtLeast(0))
            Sensor.TYPE_HEART_RATE -> heartRate = value.roundToInt().takeIf { it > 0 }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * ROTATION_VECTOR → 方位角 0–360（0 = 北）。
     *
     * `getOrientation` 给的方位角是 −π..π 弧度，转成度之后要归一化到 0..360 ——
     * 表盘拿 {sct} 直接当角度用，负数会让指针指反。
     */
    private fun headingFromRotationVector(values: FloatArray): Float? = runCatching {
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        normalizeHeading(Math.toDegrees(orientation[0].toDouble()).toFloat())
    }.getOrNull()

    /**
     * 磁力计 + 加速度计融合。没有 ROTATION_VECTOR 的机型才走这条。
     *
     * 加速度计的值是给陀螺仪兜底时已经在存的那份，这里直接复用，不再多注册一次。
     */
    private fun headingFromMagnetometer(): Float? {
        val magnetic = magneticField ?: return null
        val gravity = accelerometer ?: return null
        return runCatching {
            val rotation = FloatArray(9)
            val inclination = FloatArray(9)
            val gravityArray = floatArrayOf(gravity.first, gravity.second, gravity.third)
            if (!SensorManager.getRotationMatrix(rotation, inclination, gravityArray, magnetic)) {
                return null
            }
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotation, orientation)
            normalizeHeading(Math.toDegrees(orientation[0].toDouble()).toFloat())
        }.getOrNull()
    }

    private fun normalizeHeading(degrees: Float): Float? {
        if (!degrees.isFinite()) return null
        return ((degrees % 360f) + 360f) % 360f
    }

    @Suppress("DEPRECATION")
    private fun refreshWifiSignal() {
        wifiSignalFraction = runCatching {
            val rssi = wifiManager?.connectionInfo?.rssi ?: return@runCatching null
            if (rssi <= -127) return@runCatching null
            (WifiManager.calculateSignalLevel(rssi, 5) / 4f).coerceIn(0f, 1f)
        }.getOrNull()
    }

    @Suppress("MissingPermission")
    private fun refreshBluetoothState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            bluetoothEnabled = null
            return
        }
        bluetoothEnabled = runCatching { bluetoothManager?.adapter?.isEnabled }.getOrNull()
    }

    private fun registerSensor(type: Int) {
        if (!watchSensorActiveInMode(type, ambientMode)) return
        val sensor = sensorManager?.getDefaultSensor(type) ?: return
        runCatching {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun refreshBatteryFallback() {
        val capacity = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        batteryPercent = capacity?.takeIf { it in 0..100 } ?: batteryPercent
        charging = runCatching { batteryManager?.isCharging }.getOrNull() ?: charging
    }

    private fun restoreDailySteps(nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(stepLock) {
            val dayKey = dayKey(nowEpochMs)
            if (
                stepPreferences.getLong(KEY_STEP_DAY, Long.MIN_VALUE) == dayKey &&
                stepPreferences.contains(KEY_STEP_TOTAL)
            ) {
                steps = stepPreferences.getInt(KEY_STEP_TOTAL, 0).coerceAtLeast(0)
            } else {
                steps = null
            }
        }
    }

    private fun updateDailySteps(rawCounter: Int, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(stepLock) {
            val storedDay = stepPreferences.getLong(KEY_STEP_DAY, Long.MIN_VALUE)
            val previous = if (
                storedDay != Long.MIN_VALUE &&
                stepPreferences.contains(KEY_STEP_RAW) &&
                stepPreferences.contains(KEY_STEP_TOTAL)
            ) {
                DailyStepState(
                    dayKey = storedDay,
                    rawCounter = stepPreferences.getInt(KEY_STEP_RAW, 0).coerceAtLeast(0),
                    steps = stepPreferences.getInt(KEY_STEP_TOTAL, 0).coerceAtLeast(0),
                )
            } else {
                null
            }
            val next = accumulateDailySteps(previous, dayKey(nowEpochMs), rawCounter)
            stepPreferences.edit()
                .putLong(KEY_STEP_DAY, next.dayKey)
                .putInt(KEY_STEP_RAW, next.rawCounter)
                .putInt(KEY_STEP_TOTAL, next.steps)
                .apply()
            steps = next.steps
        }
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryPercent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).roundToInt().coerceIn(0, 100)
        } else {
            batteryPercent
        }
        charging = watchChargingState(
            status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            ),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            previous = charging,
        )
        // {btc} / {btf} / {btcd} / {btfd}：EXTRA_TEMPERATURE 的单位是**十分之一摄氏度**。
        // 拿不到时是 -1（不是 0），所以要单独判掉，否则会把「读不到」显示成 -0.1°C。
        batteryTemperatureCelsius = intent
            .getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE && it > -1_000 && it < 1_000 }
            ?.let { it / 10f }
            ?: batteryTemperatureCelsius
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun dayKey(nowEpochMs: Long): Long = Calendar.getInstance().run {
        timeInMillis = nowEpochMs
        get(Calendar.YEAR).toLong() * 1_000L + get(Calendar.DAY_OF_YEAR)
    }

    private companion object {
        const val STEP_PREFERENCES = "watchmaker_daily_steps"
        const val KEY_STEP_DAY = "day"
        const val KEY_STEP_RAW = "raw_counter"
        const val KEY_STEP_TOTAL = "daily_total"

        /**
         * 慢变量的重读间隔。
         *
         * 2 秒是「表盘上看不出滞后」与「不每帧跑 StatFs / ContentResolver」之间的折中：
         * 内存占用、剩余空间、音量、亮度、坐标这些字段没有一个需要逐帧精度。
         */
        const val SLOW_STATE_TTL_MS = 2_000L

        /**
         * 日程的重读间隔。
         *
         * 比慢变量的 TTL 长一个量级：`CalendarContract.Instances` 要跨进程查询并展开
         * 重复日程，而「下一个会议几点」容忍分钟级滞后。
         */
        const val CALENDAR_TTL_MS = 60_000L
    }
}

@Composable
fun rememberWatchmakerWatchDataProvider(
    active: Boolean,
    weatherSource: WatchWeatherSource? = null,
    permissionRevision: Any? = null,
    ambientMode: Boolean = false,
): WatchDataProvider {
    val context = LocalContext.current.applicationContext
    // Keep the nullable argument as an injection seam. When no caller owns a
    // source, use the application default cache instead of a permanently-null
    // provider; that is what lets the launcher and editor share future weather
    // updates without touching a store API.
    val resolvedWeatherSource = remember(context, weatherSource) {
        weatherSource ?: WatchWeatherSources.applicationSource
            ?: WatchWeatherRepository.default(context)
    }
    val provider = remember(context, resolvedWeatherSource) {
        AndroidWatchDataProvider(context, resolvedWeatherSource)
    }
    DisposableEffect(provider, active, permissionRevision, ambientMode) {
        provider.setAmbientMode(ambientMode)
        if (active) provider.start() else provider.stop()
        onDispose(provider::stop)
    }
    return provider
}
