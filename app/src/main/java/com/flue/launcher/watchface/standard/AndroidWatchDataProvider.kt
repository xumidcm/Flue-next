package com.flue.launcher.watchface.standard

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.net.wifi.WifiManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.roundToInt

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
    private val stepPreferences = appContext.getSharedPreferences(STEP_PREFERENCES, Context.MODE_PRIVATE)
    private val stepLock = Any()
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
    private var previousRoll = 0f
    private var previousPitch = 0f
    private var smoothedSimulatedPitch = 0f
    private var smoothedSimulatedRoll = 0f

    @Volatile
    private var wifiSignalFraction: Float? = null

    @Volatile
    private var bluetoothEnabled: Boolean? = null

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

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            restoreDailySteps()
            registerSensor(Sensor.TYPE_STEP_COUNTER)
        } else {
            steps = null
        }
        if (hasHeartRatePermission(appContext)) {
            registerSensor(Sensor.TYPE_HEART_RATE)
        }
        if (hasGyroscopeSensor) {
            registerSensor(Sensor.TYPE_GYROSCOPE)
        } else {
            // Wear OS devices frequently omit a physical gyro. Match CloX's
            // fallback by deriving a low-pass angular signal from tilt.
            registerSensor(Sensor.TYPE_ACCELEROMETER)
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
    }

    override fun snapshot(nowEpochMs: Long): WatchDataSnapshot {
        val weather = runCatching { weatherSource.snapshot(nowEpochMs) }
            .getOrNull()
            ?.let(::canonicalWatchWeatherSnapshot)
        val gyro = gyroscope
        return WatchDataSnapshot(
            batteryPercent = batteryPercent,
            isCharging = charging,
            steps = steps,
            heartRateBpm = heartRate,
            gyroscopeX = gyro?.first,
            gyroscopeY = gyro?.second,
            gyroscopeZ = gyro?.third,
            wifiSignalFraction = wifiSignalFraction,
            bluetoothEnabled = bluetoothEnabled,
            // {cnc}：渲染层不能反向依赖启动器的 NotificationListenerService，
            // 由宿主通过 WatchNotificationCounts 这个 seam 推进来。
            notificationCount = WatchNotificationCounts.current(),
            // {d24h}：DateFormat 需要 Context，而 tag 解析器是纯函数，所以在
            // provider 侧读。用户在系统设置里改制式后，下一帧快照就跟上。
            is24Hour = runCatching { DateFormat.is24HourFormat(appContext) }.getOrNull(),
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
        )
    }

    override fun requestRefresh() {
        if (!started) return
        refreshBatteryFallback()
        refreshWifiSignal()
        refreshBluetoothState()
        (weatherSource as? WatchWeatherRepository)?.requestRefresh()
            ?: runCatching { weatherSource.snapshot(System.currentTimeMillis()) }
        if (hasHeartRatePermission(appContext)) {
            // 很多手表的心率传感器只在（重新）注册监听时才开始一次新的测量，
            // 所以点按心率组件时重新武装传感器来触发刷新。这也覆盖了
            // “先启动、后授权”的场景：授权后的第一次点按会把传感器接上。
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (sensor != null) {
                runCatching { sensorManager.unregisterListener(this, sensor) }
                registerSensor(Sensor.TYPE_HEART_RATE)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        if (sensorEvent.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val x = sensorEvent.values.getOrNull(0)?.takeIf(Float::isFinite) ?: return
            val y = sensorEvent.values.getOrNull(1)?.takeIf(Float::isFinite) ?: return
            val z = sensorEvent.values.getOrNull(2)?.takeIf(Float::isFinite) ?: return
            gyroscope = Triple(x, y, z)
            return
        }
        if (sensorEvent.sensor.type == Sensor.TYPE_ACCELEROMETER && !hasGyroscopeSensor) {
            val x = sensorEvent.values.getOrNull(0)?.takeIf(Float::isFinite) ?: return
            val y = sensorEvent.values.getOrNull(1)?.takeIf(Float::isFinite) ?: return
            val z = sensorEvent.values.getOrNull(2)?.takeIf(Float::isFinite) ?: return
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
        charging = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> false
            else -> charging
        }
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
    }
}

@Composable
fun rememberWatchmakerWatchDataProvider(
    active: Boolean,
    weatherSource: WatchWeatherSource? = null,
    permissionRevision: Any? = null,
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
    DisposableEffect(provider, active, permissionRevision) {
        if (active) provider.start() else provider.stop()
        onDispose(provider::stop)
    }
    return provider
}
