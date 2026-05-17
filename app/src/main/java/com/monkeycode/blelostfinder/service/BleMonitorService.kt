package com.monkeycode.blelostfinder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.monkeycode.blelostfinder.data.model.LocationRecord
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import com.monkeycode.blelostfinder.ui.settings.AlarmSoundManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class BleMonitorService : Service() {
    companion object {
        private const val TAG = "BleMonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "ble_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_MONITORING = "com.monkeycode.blelostfinder.STOP_MONITORING"
        
        private const val DEFAULT_RSSI_THRESHOLD = -90
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
    
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiManager: WifiManager? = null
    
    private var isMonitoring = false
    private var currentDevice: BleDevice? = null
    private var deviceMac: String = BleManager.I_DEVICE_MAC
    
    private var alarmTriggerTime: Long? = null
    private var isAlarmPlaying = false
    private var isWifiDndActive = false
    private var currentRssiThreshold = DEFAULT_RSSI_THRESHOLD
    private var currentAlarmDelay = DEFAULT_ALARM_DELAY
    
    private var deviceAlarmRetriggerTime: Long? = null
    private val DEVICE_ALARM_RETRY_INTERVAL = 30000L
    
    private var wifiLock: WifiManager.WifiLock? = null
    private var rssiMonitorJob: kotlinx.coroutines.Job? = null
    
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val HEARTBEAT_INTERVAL = 60000L

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
                        currentRssiThreshold = device.rssiThreshold
                        currentAlarmDelay = device.alarmDelaySeconds
                        Log.d(TAG, "加载设备配置：RSSI 阈值=$currentRssiThreshold, 延迟=$currentAlarmDelay 秒")
                    } ?: run {
                        val defaultDevice = BleDevice(
                            macAddress = deviceMac,
                            name = "iTAG",
                            rssiThreshold = DEFAULT_RSSI_THRESHOLD,
                            alarmDelaySeconds = DEFAULT_ALARM_DELAY
                        )
                        deviceRepository.insertDevice(defaultDevice)
                        currentDevice = defaultDevice
                        currentRssiThreshold = DEFAULT_RSSI_THRESHOLD
                        currentAlarmDelay = DEFAULT_ALARM_DELAY
                    }
                    Log.d(TAG, "设备配置已加载")
                } catch (e: Exception) {
                    Log.e(TAG, "设备加载失败", e)
                }
            }
            
            serviceScope.launch {
                try {
                    settingsManager.isWifiDndEnabled.collect { enabled ->
                        isWifiDndActive = enabled && isWifiConnected()
                        Log.d(TAG, "WiFi DND active: $isWifiDndActive")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi DND 收集失败", e)
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
                                        currentRssiThreshold = it.rssiThreshold
                                        currentAlarmDelay = it.alarmDelaySeconds
                                        Log.d(TAG, "设置更新：RSSI 阈值=$currentRssiThreshold, 延迟=$currentAlarmDelay 秒")
                                    }
                                }
                                .launchIn(this)
                        }
                        
                        launch {
                            settingsManager.isWifiDndEnabled.collect { enabled ->
                                isWifiDndActive = enabled && isWifiConnected()
                                Log.d(TAG, "WiFi DND active: $isWifiDndActive")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "设置监听失败", e)
                }
            }
            
            // 监听 BLE 事件（双击、断连等）
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
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL)
                
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
        stopAlarmIfPlaying()
        Log.d(TAG, "Monitoring stopped")
    }

    private fun startRssiMonitoring() {
        rssiMonitorJob = serviceScope.launch {
            while (isMonitoring) {
                kotlinx.coroutines.delay(1000)
                
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
                    
                    if (isAlarmPlaying) {
                        stopAlarmIfPlaying()
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
                    
                    if (!isAlarmPlaying) {
                        triggerPhoneAlarm("断连报警")
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

    private fun handleBleEvent(event: BleEvent) {
        serviceScope.launch {
            when (event) {
                is BleEvent.ButtonPressed -> {
                    Log.d(TAG, "Device button single press - ignored")
                }
                // ✅ 核心修复：双击在 SERVICE 处理，后台永远生效
                is BleEvent.DoubleButtonPressed -> {
                    Log.d(TAG, "防丢器双击事件 - SERVICE 处理")
                    // 强制修复：无论状态如何，先停止，再反转
                    stopAlarmIfPlaying()
                    if (!isAlarmPlaying) {
                        triggerPhoneAlarm("防丢器双击报警")
                    }
                }
                is BleEvent.AlarmTriggered -> {
                }
                is BleEvent.Disconnected -> {
                }
                else -> {}
            }
        }
    }

    private fun recordDisconnectionLocation(deviceMac: String) {
        Log.d(TAG, "Device disconnected: $deviceMac (location recording disabled)")
    }

    private fun triggerPhoneAlarm(reason: String) {
        if (isAlarmPlaying) {
            Log.d(TAG, "Alarm already playing, skipping")
            return
        }
        
        if (isInDndMode()) {
            Log.d(TAG, "In DND mode, not triggering alarm: $reason")
            return
        }
        
        isAlarmPlaying = true
        Log.d(TAG, "Triggering phone alarm: $reason")
        
        serviceScope.launch {
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
    }

    private fun stopAlarmIfPlaying() {
        isAlarmPlaying = false
        bleManager.stopAlarm()
        alarmSoundManager.stopPlaying()
        Log.d(TAG, "SERVICE：已停止所有报警")
    }

    private fun isInDndMode(): Boolean {
        if (isWifiDndActive) {
            Log.d(TAG, "WiFi DND is active")
            return true
        }
        
        val calendar = Calendar.getInstance()
        val currentTime = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        
        val currentDevice = this.currentDevice ?: return false
        
        val (startHour, startMinute) = currentDevice.dndStartTime.split(":").map { it.toInt() }
        val (endHour, endMinute) = currentDevice.dndEndTime.split(":").map { it.toInt() }
        
        val startTime = startHour * 60 + startMinute
        val endTime = endHour * 60 + endMinute
        
        return if (startTime <= endTime) {
            currentTime in startTime..endTime
        } else {
            currentTime >= startTime || currentTime <= endTime
        }
    }

    private fun isWifiConnected(): Boolean {
        val networkInfo = wifiManager?.connectionInfo
        return networkInfo?.ssid != null && networkInfo.ssid != "<unknown ssid>"
    }
}