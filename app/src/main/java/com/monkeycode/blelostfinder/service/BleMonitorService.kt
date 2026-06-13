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
import kotlinx.coroutines.flow.collectLatest
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
        // 合并：出门提醒和断连报警共用同一个停止广播，通过 extra 区分类型
        const val EXTRA_ALARM_TYPE = "alarm_type"
        const val ALARM_TYPE_DISCONNECT = "disconnect"
        const val ALARM_TYPE_GO_OUT = "go_out"

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

    @Inject
    lateinit var wifiMonitor: WifiMonitor

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alarmMutex = Mutex()

    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiManager: WifiManager? = null

    private var isMonitoring = false
    private var currentDevice: BleDevice? = null
    private var deviceMac: String? = null

    private var isAlarmPlaying = false
    private var isWifiDndActive = false

    private var isScheduleDndEnabled = false
    private var dndStartTime = "21:00"
    private var dndEndTime = "08:00"

    private var isDisconnectAlarmEnabled = true

    private var cachedGoOutReminderEnabled = false
    private var cachedHomeWifiSsid = ""

    // 核心修复：新增"用户已确认断连"标志
    private var isDisconnectAlarmAcknowledged = false

    // 合并：统一报警状态，不再区分断连报警和出门提醒
    private var currentAlarmType: String? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var rssiMonitorJob: kotlinx.coroutines.Job? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val HEARTBEAT_INTERVAL = 60000L

    private var cachedWifiDndEnabled = false
    private var cachedScheduleDndEnabled = false
    private var cachedDisconnectAlarmEnabled = true
    private var cachedIsWifiConnected = false
    private var lastDndRangeState = false
    private var lastWifiDndActiveState = false
    private var lastShouldTriggerPhoneAlarm = false
    private var bluetoothDisconnectedTime: Long? = null

    private var lastSyncedDisconnectAlarmState: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        _isRunning.value = true

        // 注册 WiFi 监听
        wifiMonitor.register()
        Log.d(TAG, "WiFi monitor registered")

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
                    val alarmType = intent.getStringExtra(EXTRA_ALARM_TYPE) ?: ALARM_TYPE_DISCONNECT
                    serviceScope.launch {
                        alarmMutex.withLock {
                            if (isAlarmPlaying) {
                                stopAlarmIfPlayingLocked()
                                Log.d(TAG, "收到外部停止命令，已重置报警状态，类型=$alarmType")
                            }
                        }
                        // 核心修复：用户点击"好的"确认，标记已知晓断连
                        isDisconnectAlarmAcknowledged = true
                        Log.d(TAG, "用户已确认报警，当前断连期间不再重复触发")
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

        // 注销 WiFi 监听
        wifiMonitor.unregister()
        Log.d(TAG, "WiFi monitor unregistered")

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

        if (shouldEnable == lastSyncedDisconnectAlarmState) {
            Log.d(TAG, "防丢器断连报警状态未变化($shouldEnable)，跳过重复同步")
            return
        }

        bleManager.setDisconnectAlarmEnabled(shouldEnable)
        lastSyncedDisconnectAlarmState = shouldEnable
        Log.d(TAG, "已同步防丢器断连报警配置: $shouldEnable (WiFi勿扰=$cachedWifiDndEnabled, WiFi连接=$cachedIsWifiConnected, 定时勿扰=$cachedScheduleDndEnabled, 在时段内=$isInDndRange, 断连开关=$cachedDisconnectAlarmEnabled)")
    }

    private fun checkCompensateAlarm() {
        if (bleManager.connectionState.value is BleConnectionState.Connected) {
            return
        }
        if (isAlarmPlaying) {
            return
        }
        // 核心修复：用户已确认断连，不再补偿报警
        if (isDisconnectAlarmAcknowledged) {
            Log.d(TAG, "用户已确认断连，跳过补偿报警")
            return
        }

        val currentShouldTrigger = shouldTriggerPhoneAlarm()
        Log.d(TAG, "补偿报警检查: 之前=$lastShouldTriggerPhoneAlarm, 现在=$currentShouldTrigger, 蓝牙断连时间=$bluetoothDisconnectedTime, 已确认=$isDisconnectAlarmAcknowledged")

        if (!lastShouldTriggerPhoneAlarm && currentShouldTrigger && bluetoothDisconnectedTime != null) {
            serviceScope.launch {
                alarmMutex.withLock {
                    if (!isAlarmPlaying) {
                        Log.d(TAG, "策略条件变化，触发补偿报警")
                        triggerPhoneAlarmLocked(ALARM_TYPE_DISCONNECT, "断连报警（补偿）", ignoreDnd = false)
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
                    val savedMac = settingsManager.deviceMac.firstOrNull()
                    if (!savedMac.isNullOrEmpty()) {
                        deviceMac = savedMac
                        bleManager.setDeviceMacToConnect(savedMac)
                        Log.d(TAG, "从 DataStore 加载已绑定设备 MAC: $savedMac")

                        deviceRepository.getDeviceByMac(savedMac)?.let { device ->
                            currentDevice = device
                            Log.d(TAG, "加载设备配置完成: ${device.name}")
                        } ?: run {
                            Log.w(TAG, "数据库中未找到设备记录: $savedMac")
                        }
                    } else {
                        Log.d(TAG, "未绑定任何设备，等待用户扫描绑定")
                    }
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
                        Log.d(TAG, "定时勿扰结束时间：$time")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "定时勿扰结束时间监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    settingsManager.isGoOutReminderEnabled.collectLatest { enabled ->
                        cachedGoOutReminderEnabled = enabled
                        Log.d(TAG, "出门提醒开关：$enabled")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "出门提醒开关监听失败", e)
                }
            }

            serviceScope.launch {
                try {
                    settingsManager.homeWifiSsid.collectLatest { ssid ->
                        cachedHomeWifiSsid = ssid
                        Log.d(TAG, "家庭 WiFi SSID: $ssid")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "家庭 WiFi SSID 监听失败", e)
                }
            }

            // ==================== 出门提醒核心逻辑（合并版）====================
            serviceScope.launch {
                try {
                    Log.d(TAG, "开始监听WiFi断开事件...")
                    wifiMonitor.wifiDisconnectedEvent.collect { wifiInfo ->
                        Log.d(TAG, "收到WiFi断开事件：SSID=${wifiInfo.ssid}")
                        Log.d(TAG, "当前状态：出门提醒开关=$cachedGoOutReminderEnabled, 定时勿扰=$isScheduleDndEnabled, 在时段内=${isInDndTimeRange()}, 家庭SSID=$cachedHomeWifiSsid")

                        if (!cachedGoOutReminderEnabled) {
                            Log.d(TAG, "出门提醒功能关闭，忽略")
                            return@collect
                        }

                        // 新增：定时勿扰时段内，不触发出门提醒（避免路由器凌晨重启惊醒用户）
                        if (isScheduleDndEnabled && isInDndTimeRange()) {
                            Log.d(TAG, "定时勿扰生效中，跳过出门提醒")
                            return@collect
                        }

                        // 检查是否是家庭 WiFi
                        val isHomeWifi = wifiMonitor.isHomeWifi(
                            ssid = wifiInfo.ssid,
                            homeSsid = cachedHomeWifiSsid
                        )
                        Log.d(TAG, "WiFi匹配检查：isHomeWifi=$isHomeWifi, 输入SSID=${wifiInfo.ssid}, 家庭SSID=$cachedHomeWifiSsid")

                        if (!isHomeWifi) {
                            Log.d(TAG, "非家庭 WiFi，忽略出门提醒")
                            return@collect
                        }

                        // 合并：出门提醒直接走报警通道，用 ALARM_TYPE_GO_OUT 区分
                        Log.d(TAG, "检测到断开家庭 WiFi，触发出门提醒（合并到报警通道）")
                        alarmMutex.withLock {
                            triggerPhoneAlarmLocked(ALARM_TYPE_GO_OUT, "出门提醒", ignoreDnd = false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi 断开事件监听失败", e)
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
                    deviceMac?.let { mac ->
                        bleManager.reconnectIfDisconnected(mac)
                    } ?: Log.d(TAG, "未绑定设备，跳过重连")
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
                    deviceMac?.let { mac ->
                        bleManager.reconnectIfDisconnected(mac)
                    } ?: Log.d(TAG, "未绑定设备，跳过重连")
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
                    // 核心修复：重新连接后，重置"用户已确认"标志，允许下次断连正常报警
                    isDisconnectAlarmAcknowledged = false
                    bluetoothDisconnectedTime = null
                    lastShouldTriggerPhoneAlarm = false

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

                    recordDisconnectionLocation(deviceMac ?: "")

                    currentDevice?.let { device ->
                        deviceRepository.updateDevice(device.copy(
                            lastDisconnectedTime = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }

                    // 修复：断连时强制刷新WiFi状态，确保shouldTriggerPhoneAlarm判断准确
                    val currentWifiConnected = isWifiConnected()
                    cachedIsWifiConnected = currentWifiConnected
                    Log.d(TAG, "断连时强制刷新WiFi状态: connected=$currentWifiConnected")

                    bluetoothDisconnectedTime = System.currentTimeMillis()
                    lastShouldTriggerPhoneAlarm = shouldTriggerPhoneAlarm()

                    alarmMutex.withLock {
                        // 核心修复：增加"用户已确认"判断，已确认则不再触发断连自动报警
                        if (!isAlarmPlaying && shouldTriggerPhoneAlarm() && !isDisconnectAlarmAcknowledged) {
                            triggerPhoneAlarmLocked(ALARM_TYPE_DISCONNECT, "断连报警", ignoreDnd = false)
                        } else if (isDisconnectAlarmAcknowledged) {
                            Log.d(TAG, "用户已确认断连，跳过断连自动报警")
                        }
                    }

                    // 修复：首次断连只有防丢器响的问题——延迟1秒后再次检查补偿
                    serviceScope.launch {
                        delay(1000)
                        if (!isAlarmPlaying && shouldTriggerPhoneAlarm() && !isDisconnectAlarmAcknowledged) {
                            alarmMutex.withLock {
                                if (!isAlarmPlaying) {
                                    Log.d(TAG, "延迟补偿：首次断连手机未报警，现在补报")
                                    triggerPhoneAlarmLocked(ALARM_TYPE_DISCONNECT, "断连报警（延迟补偿）", ignoreDnd = false)
                                }
                            }
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
                            triggerPhoneAlarmLocked(ALARM_TYPE_DISCONNECT, "防丢器双击触发", ignoreDnd = true)
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

    // 合并：统一报警触发方法，通过 alarmType 区分断连报警和出门提醒
    private suspend fun triggerPhoneAlarmLocked(alarmType: String, reason: String, ignoreDnd: Boolean = false) {
        if (!ignoreDnd && isInDndMode()) {
            Log.d(TAG, "In DND mode, not triggering alarm: $reason")
            return
        }

        isAlarmPlaying = true
        currentAlarmType = alarmType
        Log.d(TAG, "Triggering phone alarm: type=$alarmType, reason=$reason")

        try {
            alarmSoundManager.stopPlaying()

            // 根据类型选择铃声
            val ringtonePath = if (alarmType == ALARM_TYPE_GO_OUT) {
                settingsManager.goOutRingtonePath.firstOrNull()
            } else {
                settingsManager.alarmRingtonePath.firstOrNull()
            }

            alarmSoundManager.playAlarm(ringtonePath)
            bleManager.emitAlarmEvent(reason)

            // 发送广播显示弹窗，带上 alarmType
            val intent = Intent("com.monkeycode.blelostfinder.SHOW_PHONE_ALARM")
            intent.setPackage(packageName)
            intent.putExtra(EXTRA_ALARM_TYPE, alarmType)
            sendBroadcast(intent)

            Log.d(TAG, "报警已触发，广播已发送，类型=$alarmType")
        } catch (e: Exception) {
            Log.e(TAG, "触发报警失败", e)
            isAlarmPlaying = false
            currentAlarmType = null
        }
    }

    private fun stopAlarmIfPlayingLocked() {
        isAlarmPlaying = false
        currentAlarmType = null
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