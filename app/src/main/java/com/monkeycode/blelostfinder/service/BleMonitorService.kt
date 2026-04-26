package com.monkeycode.blelostfinder.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.annotation.OptIn
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.ble.BleConnectionState
import com.monkeycode.blelostfinder.ble.BleEvent
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.data.local.SettingsManager
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.model.LocationRecord
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow

@AndroidEntryPoint
class BleMonitorService : Service() {
    companion object {
        private const val TAG = "BleMonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "ble_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_MONITORING = "com.monkeycode.blelostfinder.STOP_MONITORING"
        
        private const val DEFAULT_RSSI_THRESHOLD = -90
        private const val DEFAULT_ALARM_DELAY = 60 // seconds
        private const val LOCATION_FILTER_TIME = 30000L // 30 seconds
    }

    @Inject
    lateinit var bleManager: BleManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var deviceRepository: DeviceRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiManager: WifiManager? = null
    
    private var isMonitoring = false
    private var currentDevice: BleDevice? = null
    private var deviceMac: String = BleManager.I_DEVICE_MAC
    
    // RSSI monitoring
    private var lastValidRssi = -100
    private var lastRecordedRssi = -100
    private var alarmTriggerTime: Long? = null
    private var isAlarmPlaying = false
    private var isWifiDndActive = false
    
    private var wifiLock: WifiManager.WifiLock? = null

    private val monitorJob = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, createNotification())
        acquireWakeLock()
        startMonitoring()
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopMonitoring()
        releaseWakeLock()
        serviceScope.cancel()
        monitorJob.cancel()
        super.onDestroy()
    }

    private fun initialize() {
        if (!bleManager.initialize()) {
            Log.e(TAG, "Failed to initialize BLE")
            return
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

    private fun updateNotificationText(text: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BLE 防丢器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BleLostFinder::MonitorWakeLock").apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
        
        // Acquire WiFi lock to keep network active
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

    @OptIn(androidx.annotation.ExperimentalStdlibApi::class)
    private fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring")
            return
        }

        isMonitoring = true
        
        // Get device settings
        serviceScope.launch {
            deviceRepository.getDeviceByMac(deviceMac)?.let { device ->
                currentDevice = device
                bleManager.connect(device.macAddress).collect { state ->
                    handleConnectionState(state, device)
                }
            } ?: run {
                // No device in DB, create default
                val defaultDevice = BleDevice(
                    macAddress = deviceMac,
                    name = "iTAG",
                    rssiThreshold = DEFAULT_RSSI_THRESHOLD,
                    alarmDelaySeconds = DEFAULT_ALARM_DELAY
                )
                deviceRepository.insertDevice(defaultDevice)
                currentDevice = defaultDevice

                bleManager.connect(deviceMac).collect { state ->
                    handleConnectionState(state, defaultDevice)
                }
            }
        }
        
        // Monitor WiFi DND state
        serviceScope.launch {
            settingsManager.isWifiDndEnabled.collect { enabled ->
                isWifiDndActive = enabled && isWifiConnected()
                Log.d(TAG, "WiFi DND active: $isWifiDndActive")
            }
        }
        
        // Listen for BLE events
        serviceScope.launch {
            bleManager.bleEvents.collect { event ->
                handleBleEvent(event)
            }
        }
        
        Log.d(TAG, "Monitoring started")
        updateNotificationText("监控中...")
    }

    private fun stopMonitoring() {
        isMonitoring = false
        bleManager.disconnect()
        stopAlarmIfPlaying()
        Log.d(TAG, "Monitoring stopped")
        updateNotificationText("监控已停止")
    }

    @OptIn(androidx.annotation.ExperimentalStdlibApi::class)
    private fun handleConnectionState(state: BleConnectionState, device: BleDevice) {
        serviceScope.launch {
            when (state) {
                is BleConnectionState.Connected -> {
                    Log.d(TAG, "Connected to device")
                    alarmTriggerTime = null
                    updateNotificationText("已连接 - ${device.name}")
                    
                    // Update last connected time
                    deviceRepository.updateDevice(device.copy(
                        lastConnectedTime = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    ))
                }
                is BleConnectionState.Disconnected -> {
                    Log.d(TAG, "Disconnected from device")
                    updateNotificationText("已断开 - ${device.name}")
                    
                    // Record disconnection location
                    recordDisconnectionLocation(device.macAddress)
                    
                    // Update last disconnected time
                    deviceRepository.updateDevice(device.copy(
                        lastDisconnectedTime = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    ))
                    
                    // Try to reconnect after delay
                    withContext(Dispatchers.IO) {
                        delay(5000)
                        if (isMonitoring) {
                            bleManager.connect(device.macAddress)
                        }
                    }
                }
                is BleConnectionState.Connecting -> {
                    updateNotificationText("连接中...")
                }
                is BleConnectionState.Error -> {
                    Log.e(TAG, "BLE Error: ${state.message}")
                    updateNotificationText("错误：${state.message}")
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
                is BleEvent.AlarmTriggered -> {
                    Log.d(TAG, "Alarm triggered: ${event.reason}")
                }
                is BleEvent.LocationRecorded -> {
                    Log.d(TAG, "Location recorded")
                }
                else -> {}
            }
        }
    }

    private fun recordDisconnectionLocation(deviceMac: String) {
        // Location recording is disabled until map feature is configured
        // This can be implemented later when needed
        Log.d(TAG, "Device disconnected: $deviceMac (location recording disabled)")
    }

    private fun triggerPhoneAlarm(reason: String) {
        if (isAlarmPlaying) return
        
        // Check if in DND mode
        if (isInDndMode()) {
            Log.d(TAG, "In DND mode, not triggering alarm: $reason")
            return
        }
        
        isAlarmPlaying = true
        Log.d(TAG, "Triggering phone alarm: $reason")
        
        // TODO: Implement alarm playing with MediaPlayer
    }

    private fun stopAlarmIfPlaying() {
        isAlarmPlaying = false
        bleManager.stopAlarm()
        alarmSoundManager.stopPlaying()
    }

    @OptIn(androidx.annotation.ExperimentalStdlibApi::class)
    private fun isInDndMode(): Boolean {
        // Check WiFi DND
        if (isWifiDndActive) {
            Log.d(TAG, "WiFi DND is active")
            return true
        }
        
        // Check schedule DND
        val calendar = Calendar.getInstance()
        val currentTime = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        
        val currentDevice = this.currentDevice ?: return false
        
        val (startHour, startMinute) = currentDevice.dndStartTime.split(":").map { it.toInt() }
        val (endHour, endMinute) = currentDevice.dndEndTime.split(":").map { it.toInt() }
        
        val startTime = startHour * 60 + startMinute
        val endTime = endHour * 60 + endMinute
        
        return if (startTime <= endTime) {
            // Normal range (e.g., 21:00 - 08:00 next day)
            currentTime in startTime..endTime
        } else {
            // Overnight range (e.g., 21:00 - 08:00)
            currentTime >= startTime || currentTime <= endTime
        }
    }

    private fun isWifiConnected(): Boolean {
        val networkInfo = wifiManager?.connectionInfo
        return networkInfo?.ssid != null && networkInfo.ssid != "<unknown ssid>"
    }

    private suspend fun updateDevice(device: BleDevice) {
        deviceRepository.updateDevice(device)
    }
}
