package com.monkeycode.blelostfinder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.monkeycode.blelostfinder.ble.BleConnectionState
import com.monkeycode.blelostfinder.ble.BleEvent
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.data.local.SettingsManager
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import com.monkeycode.blelostfinder.ui.settings.AlarmSoundManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class BleMonitorService : Service() {
    companion object {
        private const val TAG = "BleMonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "ble_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_MONITORING = "com.monkeycode.blelostfinder.STOP_MONITORING"
        const val ACTION_STOP_PHONE_ALARM = "com.monkeycode.blelostfinder.STOP_PHONE_ALARM"

        private const val DEFAULT_ALARM_DELAY = 60

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    @Inject
    lateinit var bleManager: BleManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var deviceRepository: DeviceRepository

    @Inject
    lateinit var alarmSoundManager: AlarmSoundManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alarmMutex = Mutex()

    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiManager: WifiManager? = null

    private var isMonitoring = false
    private var currentDevice: BleDevice? = null
    private var deviceMac: String = BleManager.I_DEVICE_MAC

    private var alarmTriggerTime: Long? = null
    private var isAlarmPlaying = false
    private var isWifiDndActive = false
    private var currentAlarmDelay = DEFAULT_ALARM_DELAY

    private var isScheduleDndEnabled = false
    private var dndStartTime = "21:00"
    private var dndEndTime = "08:00"

    private var isDisconnectAlarmEnabled = true

    private var deviceAlarmRetriggerTime: Long? = null
    private val DEVICE_ALARM_RETRY_INTERVAL = 30000L

    private var wifiLock: WifiManager.WifiLock? = null
    private var rssiMonitorJob: kotlinx.coroutines.Job? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val HEARTBEAT_INTERVAL = 60000L

    // ==================== 新增：缓存各开关状态，用于统一决策 ====================
    private var cachedWifiDndEnabled = false
    private var cachedScheduleDndEnabled = false
    private var cachedDisconnectAlarmEnabled = true
    private var cachedIsWifiConnected = false
    private var lastDndRangeState = false
    private var lastWifiDndActiveState = false
    private var lastShouldTriggerPhoneAlarm = false
    private var bluetoothDisconnectedTime: Long? = null

    // ==================== 核心修复：记录上次实际同步给防丢器的配置，避免重复写入 ====================
    private var lastSyncedDisconnectAlarmState: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        _isRunning.value = true
        initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Log.d(TAG, "Service started with intent: ${intent?.action}")

            when (intent?.action) {
                ACTION_STOP_MONITORING -> {
                    stopMonitoring()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_STOP_PHONE_ALARM -> {
                    serviceScope.launch {
                        alarmMutex.withLock {
                            if (isAlarmPlaying) {
                                stopAlarmIfPlayingLocked()
                                Log.d(TAG, "收到外部停止命令，已重置报警状态")
                            }
                        }
                    }
                }
            }

            try {
                startForeground(NOTIFICATION_ID, createNotification())
                Log.d(TAG, "前台服务启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground 失败", e)
            }

            acquireWakeLock()
            startMonitoring()

            Log.d(TAG, "监控服务启动完成")
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand 异常", e)
            return START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopMonitoring()
        releaseWakeLock()
        heartbeatJob?.cancel()
        heartbeatJob = null
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun initialize() {
        try {
            if (!bleManager.initialize()) {
                Log.e(TAG, "Failed to initialize BLE")
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE 初始化失败", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BLE 监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持 BLE 监控服务前台运行"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, BleMonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BLE 防丢器")
            .setContentText("正在监控设备...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止监控", stopPendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BleLostFinder::MonitorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }

        wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "BleLostFinder::WifiLock"
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wifiLock = null
    }

    // ==================== 核心修复：统一同步防丢器断连报警配置（增加去重） ====================
    /**
     * 根据当前所有策略状态，计算是否应该开启防丢器断连报警，并同步给防丢器
     * 在连接状态下，任何策略变化时都应调用此方法
     */
    private fun syncDeviceAlarmConfig() {
        if (bleManager.connectionState.value !is BleConnectionState.Connected) {
            Log.d(TAG, "设备未连接，跳过同步防丢器配置")
            return
        }

        val isInDndRange = isInDndTimeRange()
        val shouldEnable = bleManager.shouldEnableDeviceDisconnectAlarm(
            isWifiDndEnabled = cachedWifiDndEnabled,
            isWifiConnected = cachedIsWifiConnected,
            isScheduleDndEnabled = cachedScheduleDndEnabled,
            isInDndTimeRange = isInDndRange,
            isDisconnectAlarmEnabled = cachedDisconnectAlarmEnabled
        )

        // 核心修复：去重，状态没变不重复写入，避免占满BLE队列影响手动操作
        if (shouldEnable == lastSyncedDisconnectAlarmState) {
            Log.d(TAG, "防丢器断连报警状态未变化($shouldEnable)，跳过重复同步")
            return
        }

        bleManager.setDisconnectAlarmEnabled(shouldEnable)
        lastSyncedDisconnectAlarmState = shouldEnable
        Log.d(TAG, "已同步防丢器断连报警配置: $shouldEnable (WiFi勿扰=$cachedWifiDndEnabled, WiFi连接=$cachedIsWifiConnected, 定时勿扰=$cachedScheduleDndEnabled, 在时段内=$isInDndRange, 断连开关=$cachedDisconnectAlarmEnabled)")
    }

    // ==================== 核心修复：检查是否需要补偿报警 ====================
    private fun checkCompensateAlarm() {
        if (bleManager.connectionState.value is BleConnectionState.Connected) {
            return
        }
        if (isAlarmPlaying) {
            return
        }

        val currentShouldTrigger = shouldTriggerPhoneAlarm()
        Log.d(TAG, "补偿报警检查: 之前=$lastShouldTriggerPhoneAlarm, 现在=$currentShouldTrigger, 蓝牙断连时间=$bluetoothDisconnectedTime")

        if (!lastShouldTriggerPhoneAlarm && currentShouldTrigger && bluetoothDisconnectedTime != null) {
            serviceScope.launch {
                alarmMutex.withLock {
                    if (!isAlarmPlaying) {
                        Log.d(TAG, "策略条件变化，触发补偿报警")
                        triggerPhoneAlarmLocked("断连报警（补偿）", ignoreDnd = false)
                    }
                }
            }
        }

        lastShouldTriggerPhoneAlarm = currentShouldTrigger
    }

    private fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring")
            return
        }

        isMonitoring = true

        try {
            serviceScope.launch {
                try {
                    deviceRepository.getDeviceByMac(deviceMac)?.let { device ->
                        currentDevice = device
                        currentAlarmDelay = device.alarmDelaySeconds
                        Log.d(TAG, "加载设备配置：延迟=$currentAlarmDelay 秒")
                    } ?: run {
                        val defaultDevice = BleDevice(
                            macAddress = deviceMac,
                            name = "iTAG",
                            rssiThreshold = -90,
                            alarmDelaySeconds = DEFAULT_ALARM_DELAY
                        )
                        deviceRepository.insertDevice(defaultDevice)
                        currentDevice = defaultDevice
                        currentAlarmDelay = DEFAULT_ALARM_DELAY
                    }
                    Log.d(TAG, "设备配置已加载")
                } catch (e: Exception) {
                    Log.e(TAG, "设备加载失败", e)
                }
            }

            serviceScope.launch {
                try {
                    settingsManager.isDisconnectAlarmEnabled.collect { enabled ->
                        cachedDisconnectAlarmEnabled = enabled
                        isDisconnectAlarmEnabled = enabled
                        Log.d(TAG, "断连报警开关: $enabled")
                        syncDeviceAlarmConfig()
                        checkCompensateAlarm()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "断连报警监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    combine(
                        settingsManager.isWifiDndEnabled,
                        kotlinx.coroutines.flow.flow {
                            while (true) {
                                val wifiConnected = isWifiConnected()
                                emit(wifiConnected)
                                delay(5000)
                            }
                        }
                    ) { dndEnabled, wifiConnected ->
                        cachedWifiDndEnabled = dndEnabled
                        cachedIsWifiConnected = wifiConnected
                        dndEnabled && wifiConnected
                    }.collect { active ->
                        isWifiDndActive = active
                        Log.d(TAG, "WiFi DND active: $active")
                        syncDeviceAlarmConfig()
                        checkCompensateAlarm()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi DND 收集失败", e)
                }
            }

            serviceScope.launch {
                try {
                    settingsManager.isScheduleDndEnabled.collect { enabled ->
                        cachedScheduleDndEnabled = enabled
                        isScheduleDndEnabled = enabled
                        Log.d(TAG, "定时勿扰开关: $enabled")
                        syncDeviceAlarmConfig()
                        checkCompensateAlarm()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "定时勿扰开关监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    settingsManager.dndStartTime.collect { time ->
                        dndStartTime = time
                        Log.d(TAG, "定时勿扰开始时间: $time")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "定时勿扰开始时间监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    settingsManager.dndEndTime.collect { time ->
                        dndEndTime = time
                        Log.d(TAG, "定时勿扰结束时间: $time")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "定时勿扰结束时间监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    while (isMonitoring) {
                        delay(60000)

                        if (bleManager.connectionState.value is BleConnectionState.Connected) {
                            val currentlyInRange = isInDndTimeRange()
                            if (currentlyInRange != lastDndRangeState) {
                                lastDndRangeState = currentlyInRange
                                Log.d(TAG, "定时勿扰时段状态变化: $currentlyInRange，同步防丢器配置")
                                syncDeviceAlarmConfig()
                            }
                        }
                        checkCompensateAlarm()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "定时勿扰时段检查失败", e)
                }
            }

            serviceScope.launch {
                try {
                    bleManager.connectionState.collect { state ->
                        handleConnectionState(state)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "连接状态监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            deviceRepository.getDeviceByMacFlow(deviceMac)
                                .onEach { device ->
                                    device?.let {
                                        currentAlarmDelay = it.alarmDelaySeconds
                                        Log.d(TAG, "设置更新：延迟=$currentAlarmDelay 秒")
                                    }
                                }
                                .launchIn(this)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "设置监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    bleManager.bleEvents.collect { event ->
                        try {
                            handleBleEvent(event)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理 BLE 事件失败", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BLE 事件收集失败", e)
                }
            }

            startRssiMonitoring()
            startHeartbeat()

            Log.d(TAG, "Monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "startMonitoring 异常", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isMonitoring) {
                delay(HEARTBEAT_INTERVAL)

                val connectionState = bleManager.connectionState.value
                if (connectionState is BleConnectionState.Disconnected) {
                    Log.d(TAG, "心跳检测：设备断开，触发重连")
                    bleManager.reconnectIfDisconnected()
                }

                notificationManager.notify(NOTIFICATION_ID, createNotification())
            }
        }
        Log.d(TAG, "心跳机制已启动")
    }

    private fun stopMonitoring() {
        isMonitoring = false
        rssiMonitorJob?.cancel()
        serviceScope.launch {
            alarmMutex.withLock {
                stopAlarmIfPlayingLocked()
            }
        }
        Log.d(TAG, "Monitoring stopped")
    }

    private fun startRssiMonitoring() {
        rssiMonitorJob = serviceScope.launch {
            while (isMonitoring) {
                delay(1000)

                val connectionState = bleManager.connectionState.value

                if (connectionState is BleConnectionState.Disconnected) {
                    bleManager.reconnectIfDisconnected()
                }
            }
        }
        Log.d(TAG, "RSSI 监控已启动（简化版）")
    }

    private fun handleConnectionState(state: BleConnectionState) {
        serviceScope.launch {
            when (state) {
                is BleConnectionState.Connected -> {
                    Log.d(TAG, "Connected to device，连接成功")
                    alarmTriggerTime = null
                    deviceAlarmRetriggerTime = null
                    bluetoothDisconnectedTime = null
                    lastShouldTriggerPhoneAlarm = false

                    // 核心修复：连接成功后强制重置同步状态，确保FFE2配置一定会被写入
                    lastSyncedDisconnectAlarmState = null
                    syncDeviceAlarmConfig()

                    if (isAlarmPlaying) {
                        alarmMutex.withLock {
                            stopAlarmIfPlayingLocked()
                        }
                        Log.d(TAG, "设备已重连，立即停止所有报警")
                    }

                    currentDevice?.let { device ->
                        deviceRepository.updateDevice(device.copy(
                            lastConnectedTime = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                }
                is BleConnectionState.Disconnected -> {
                    Log.d(TAG, "Disconnected from device，设备已断开")

                    recordDisconnectionLocation(deviceMac)

                    currentDevice?.let { device ->
                        deviceRepository.updateDevice(device.copy(
                            lastDisconnectedTime = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }

                    bluetoothDisconnectedTime = System.currentTimeMillis()
                    lastShouldTriggerPhoneAlarm = shouldTriggerPhoneAlarm()

                    alarmMutex.withLock {
                        if (!isAlarmPlaying && shouldTriggerPhoneAlarm()) {
                            triggerPhoneAlarmLocked("断连报警", ignoreDnd = false)
                        }
                    }
                }
                is BleConnectionState.Connecting -> {
                    Log.d(TAG, "Connecting to device，连接中...")
                }
                is BleConnectionState.Error -> {
                    Log.e(TAG, "BLE Error: ${state.message}")
                }
                else -> {}
            }
        }
    }

    private fun shouldTriggerPhoneAlarm(): Boolean {
        if (cachedWifiDndEnabled && cachedIsWifiConnected) {
            Log.d(TAG, "手机报警判断：WiFi勿扰生效，不报警")
            return false
        }

        if (cachedScheduleDndEnabled && isInDndTimeRange()) {
            Log.d(TAG, "手机报警判断：定时勿扰生效，不报警")
            return false
        }

        Log.d(TAG, "手机报警判断：由断连报警开关决定=$cachedDisconnectAlarmEnabled")
        return cachedDisconnectAlarmEnabled
    }

    private fun handleBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.ButtonPressed -> {
                Log.d(TAG, "Device button single press - ignored")
            }
            is BleEvent.DoubleButtonPressed -> {
                serviceScope.launch {
                    alarmMutex.withLock {
                        if (isAlarmPlaying) {
                            Log.d(TAG, "检测到双击，正在报警中，停止报警")
                            stopAlarmIfPlayingLocked()
                        } else {
                            Log.d(TAG, "检测到双击，触发手机报警")
                            triggerPhoneAlarmLocked("防丢器双击触发", ignoreDnd = true)
                        }
                    }
                }
            }
            is BleEvent.AlarmTriggered -> {
                // Service 触发报警时自己发送的事件，无需处理
            }
            is BleEvent.Disconnected -> {
                // 断连事件已在 handleConnectionState 中处理
            }
            else -> {}
        }
    }

    private fun recordDisconnectionLocation(deviceMac: String) {
        Log.d(TAG, "Device disconnected: $deviceMac (location recording disabled)")
    }

    private suspend fun triggerPhoneAlarmLocked(reason: String, ignoreDnd: Boolean = false) {
        if (!ignoreDnd && isInDndMode()) {
            Log.d(TAG, "In DND mode, not triggering alarm: $reason")
            return
        }

        isAlarmPlaying = true
        Log.d(TAG, "Triggering phone alarm: $reason")

        try {
            alarmSoundManager.stopPlaying()
            val ringtonePath = settingsManager.alarmRingtonePath.firstOrNull()
            alarmSoundManager.playAlarm(ringtonePath)
            bleManager.emitAlarmEvent(reason)
        } catch (e: Exception) {
            Log.e(TAG, "触发报警失败", e)
            isAlarmPlaying = false
        }
    }

    private fun stopAlarmIfPlayingLocked() {
        isAlarmPlaying = false
        bleManager.stopAlarm()
        alarmSoundManager.stopPlaying()
    }

    private fun stopAlarmIfPlaying() {
        serviceScope.launch {
            alarmMutex.withLock {
                stopAlarmIfPlayingLocked()
            }
        }
    }

    private fun isInDndMode(): Boolean {
        if (isWifiDndActive) {
            Log.d(TAG, "WiFi DND is active")
            return true
        }

        if (!isScheduleDndEnabled) {
            return false
        }

        return isInDndTimeRange()
    }

    private fun isInDndTimeRange(): Boolean {
        if (!isScheduleDndEnabled) return false

        val calendar = Calendar.getInstance()
        val currentTime = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val (startHour, startMinute) = dndStartTime.split(":").map { it.toInt() }
        val (endHour, endMinute) = dndEndTime.split(":").map { it.toInt() }

        val startTime = startHour * 60 + startMinute
        val endTime = endHour * 60 + endMinute

        return if (startTime <= endTime) {
            currentTime in startTime..endTime
        } else {
            currentTime >= startTime || currentTime <= endTime
        }
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) 
                as? android.net.ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            Log.d(TAG, "WiFi 连接状态: $isWifi")
            isWifi
        } catch (e: Exception) {
            Log.e(TAG, "检查 WiFi 状态失败", e)
            false
        }
    }
}