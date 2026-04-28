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
    
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        createNotificationChannel()
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
    
            // 确保前台服务启动成功
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                Log.d(TAG, "前台服务启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground 失败", e)
                // 降级处理：尝试普通启动
            }
            
            acquireWakeLock()
            startMonitoring()
            
            Log.d(TAG, "监控服务启动完成")
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand 异常", e)
            // 确保即使出错也返回 START_STICKY，让系统重试
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
        serviceScope.cancel()
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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BleLostFinder::MonitorWakeLock").apply {
            acquire(10 * 60 * 1000L)
        }
        
        wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL,
            "BleLostFinder::WifiLock"
        )?.apply {
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
                    } ?: run {
                        val defaultDevice = BleDevice(
                            macAddress = deviceMac,
                            name = "iTAG",
                            rssiThreshold = DEFAULT_RSSI_THRESHOLD,
                            alarmDelaySeconds = DEFAULT_ALARM_DELAY
                        )
                        deviceRepository.insertDevice(defaultDevice)
                        currentDevice = defaultDevice
                    }
    
                    bleManager.connect(deviceMac).onEach { state ->
                        try {
                            handleConnectionState(state)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理连接状态失败", e)
                        }
                    }.launchIn(this)
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
            
            Log.d(TAG, "Monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "startMonitoring 异常", e)
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        bleManager.disconnect()
        stopAlarmIfPlaying()
        Log.d(TAG, "Monitoring stopped")
    }

    private fun handleConnectionState(state: BleConnectionState) {
        serviceScope.launch {
            when (state) {
                is BleConnectionState.Connected -> {
                    Log.d(TAG, "Connected to device")
                    alarmTriggerTime = null
                    
                    currentDevice?.let { device ->
                        deviceRepository.updateDevice(device.copy(
                            lastConnectedTime = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                }
                is BleConnectionState.Disconnected -> {
                    Log.d(TAG, "Disconnected from device")
                    
                    recordDisconnectionLocation(deviceMac)
                    
                    currentDevice?.let { device ->
                        deviceRepository.updateDevice(device.copy(
                            lastDisconnectedTime = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                    
                    kotlinx.coroutines.delay(5000)
                    if (isMonitoring) {
                        bleManager.connect(deviceMac)
                    }
                }
                is BleConnectionState.Connecting -> {
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
                    Log.d(TAG, "Device button pressed - trigger phone alarm")
                    triggerPhoneAlarm("防丢器按键触发")
                }
                else -> {}
            }
        }
    }

    private fun recordDisconnectionLocation(deviceMac: String) {
        Log.d(TAG, "Device disconnected: $deviceMac (location recording disabled)")
    }

    private fun triggerPhoneAlarm(reason: String) {
        if (isAlarmPlaying) return
        
        if (isInDndMode()) {
            Log.d(TAG, "In DND mode, not triggering alarm: $reason")
            return
        }
        
        isAlarmPlaying = true
        Log.d(TAG, "Triggering phone alarm: $reason")
        
        serviceScope.launch {
            try {
                val ringtonePath = settingsManager.alarmRingtonePath.firstOrNull()
                alarmSoundManager.playAlarm(ringtonePath)
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
