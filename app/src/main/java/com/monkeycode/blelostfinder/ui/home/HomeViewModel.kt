package com.monkeycode.blelostfinder.ui.home

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkeycode.blelostfinder.ble.BleConnectionState
import com.monkeycode.blelostfinder.ble.BleEvent
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.data.local.SettingsManager
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import com.monkeycode.blelostfinder.service.BleMonitorService
import com.monkeycode.blelostfinder.service.BleMonitorService.Companion.isRunning
import com.monkeycode.blelostfinder.ui.settings.AlarmSoundManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val bleManager: BleManager,
    private val deviceRepository: DeviceRepository,
    private val settingsManager: SettingsManager,
    private val alarmSoundManager: AlarmSoundManager
) : AndroidViewModel(application) {

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _device = MutableStateFlow<BleDevice?>(null)
    val device: StateFlow<BleDevice?> = _device.asStateFlow()

    val rssi: StateFlow<Int> = bleManager.rssi
    val batteryLevel: StateFlow<Int> = bleManager.batteryLevel

    val connectedDeviceName: StateFlow<String?> = bleManager.connectedDeviceName

    private val _isDeviceAlarmPlaying = MutableStateFlow(false)
    val isDeviceAlarmPlaying: StateFlow<Boolean> = _isDeviceAlarmPlaying.asStateFlow()

    val isMonitoringRunning = isRunning

    private val _phoneAlarmTriggered = MutableStateFlow(false)
    val phoneAlarmTriggered: StateFlow<Boolean> = _phoneAlarmTriggered.asStateFlow()

    // 核心修复：新增当前报警类型，用于区分断连报警和出门提醒，确保弹窗颜色正确
    private val _currentAlarmType = MutableStateFlow<String?>(null)
    val currentAlarmType: StateFlow<String?> = _currentAlarmType.asStateFlow()

    // 出门提醒已合并到报警系统，使用统一的弹窗状态

    private val _isDeviceBound = MutableStateFlow(false)
    val isDeviceBound: StateFlow<Boolean> = _isDeviceBound.asStateFlow()

    init {
        checkDeviceBound()
        observeBleState()
        observeBleEvents()

        // 核心修复：移除启动时检查alarmSoundManager.isPlaying()，避免MediaPlayer残留导致误触发
        // 弹窗状态由Service广播或BLE事件驱动，不依赖MediaPlayer状态
        Log.d("HomeViewModel", "初始化完成，等待Service广播或BLE事件触发弹窗")
    }

    private fun checkDeviceBound() {
        viewModelScope.launch {
            val savedMac = settingsManager.deviceMac.firstOrNull()
            val hasDevice = !savedMac.isNullOrEmpty()
            _isDeviceBound.value = hasDevice
            Log.d("HomeViewModel", "设备绑定状态: $hasDevice, MAC=$savedMac")

            if (hasDevice) {
                loadDevice(savedMac!!)
                // 核心修复：如果监控服务已在运行，说明蓝牙已连接或正在连接，不重复发起
                // 避免 connectDirectly() 的 cleanupGatt() 触发断连报警
                if (!BleMonitorService.isRunning.value) {
                    connectToDevice()
                } else {
                    Log.d("HomeViewModel", "监控服务已在运行，跳过自动连接，避免重复断连")
                }
            } else {
                Log.d("HomeViewModel", "未绑定设备，等待用户扫描绑定")
            }
        }
    }

    fun connectToDevice() {
        viewModelScope.launch {
            try {
                val savedMac = settingsManager.deviceMac.firstOrNull()
                if (!savedMac.isNullOrEmpty()) {
                    // 核心修复：如果蓝牙已连接，不重复发起连接，避免 cleanupGatt() 触发断连报警
                    if (bleManager.connectionState.value is BleConnectionState.Connected) {
                        Log.d("HomeViewModel", "蓝牙已连接，跳过重复连接")
                        return@launch
                    }
                    bleManager.setDeviceMacToConnect(savedMac)
                    bleManager.connectDirectly(savedMac)
                    Log.d("HomeViewModel", "已调用 connectDirectly() MAC=$savedMac")
                } else {
                    Log.d("HomeViewModel", "未绑定设备，无法自动连接")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "连接失败：${e.message}", e)
            }
        }
    }

    private fun loadDevice(macAddress: String) {
        viewModelScope.launch {
            deviceRepository.getDeviceByMacFlow(macAddress).collect { device: BleDevice? ->
                _device.value = device
            }
        }
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.connectionState.collect { state: BleConnectionState ->
                _connectionState.value = state
                if (state is BleConnectionState.Connected) {
                    Log.d("HomeViewModel", "设备已连接，重置报警状态")
                    _isDeviceAlarmPlaying.value = false
                }
            }
        }
    }

    private fun observeBleEvents() {
        viewModelScope.launch {
            bleManager.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.ButtonPressed -> {
                        Log.d("HomeViewModel", "检测到防丢器单击，忽略")
                    }
                    is BleEvent.DoubleButtonPressed -> {
                        val isPlaying = alarmSoundManager.isPlaying()
                        _phoneAlarmTriggered.value = isPlaying
                        Log.d("HomeViewModel", "检测到防丢器双击，响铃状态=$isPlaying，弹窗=$isPlaying")
                    }
                    is BleEvent.AlarmTriggered -> {
                        Log.d("HomeViewModel", "收到报警事件：${event.reason}")
                        if (!_phoneAlarmTriggered.value) {
                            _phoneAlarmTriggered.value = true
                        }
                        // 核心修复：根据报警原因设置报警类型
                        _currentAlarmType.value = if (event.reason.contains("出门提醒")) {
                            "go_out"
                        } else {
                            "disconnect"
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun startDeviceAlarm() {
        viewModelScope.launch {
            try {
                bleManager.startAlarm()
                _isDeviceAlarmPlaying.value = true
                Log.d("HomeViewModel", "触发防丢器响铃")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "触发防丢器响铃失败", e)
                _isDeviceAlarmPlaying.value = false
            }
        }
    }

    fun stopDeviceAlarm() {
        viewModelScope.launch {
            try {
                bleManager.stopAlarm()
                _isDeviceAlarmPlaying.value = false
                Log.d("HomeViewModel", "停止防丢器响铃")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "停止防丢器响铃失败", e)
            }
        }
    }

    fun toggleDeviceAlarm() {
        if (_isDeviceAlarmPlaying.value) {
            stopDeviceAlarm()
        } else {
            startDeviceAlarm()
        }
    }

    fun triggerPhoneAlarm() {
        viewModelScope.launch {
            try {
                alarmSoundManager.stopPlaying()
                val ringtonePath = settingsManager.alarmRingtonePath.firstOrNull()
                alarmSoundManager.playAlarm(ringtonePath)
                _phoneAlarmTriggered.value = true
                Log.d("HomeViewModel", "触发手机响铃，铃声类型：${ringtonePath ?: "默认"}")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "触发手机响铃失败", e)
            }
        }
    }

    fun stopPhoneAlarm() {
        try {
            if (_isDeviceAlarmPlaying.value) {
                viewModelScope.launch {
                    bleManager.stopAlarm()
                }
                _isDeviceAlarmPlaying.value = false
            }
            alarmSoundManager.stopPlaying()
            // 核心修复：确保弹窗状态和报警类型被清除，避免重复触发和弹窗颜色错误
            _phoneAlarmTriggered.value = false
            _currentAlarmType.value = null

            val context = getApplication<Application>().applicationContext
            val stopIntent = Intent(context, BleMonitorService::class.java).apply {
                action = BleMonitorService.ACTION_STOP_PHONE_ALARM
            }
            try {
                context.startService(stopIntent)
                Log.d("HomeViewModel", "已通知 Service 重置断连报警状态")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "通知 Service 失败", e)
            }

            Log.d("HomeViewModel", "停止所有响铃并关闭弹窗")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "停止响铃失败", e)
        }
    }

    /**
     * 停止出门提醒（已合并到报警系统，直接调用 stopPhoneAlarm）
     */
    fun stopGoOutReminder() {
        stopPhoneAlarm()
    }

    /**
     * 检查报警铃声是否正在播放
     */
    fun isAlarmSoundPlaying(): Boolean = alarmSoundManager.isPlaying()

    /**
     * 同步报警状态：如果铃声正在播放但状态未同步，恢复弹窗状态
     */
    fun syncAlarmState() {
        if (alarmSoundManager.isPlaying() && !_phoneAlarmTriggered.value) {
            _phoneAlarmTriggered.value = true
            Log.d("HomeViewModel", "同步报警状态：铃声正在播放，恢复弹窗")
        }
    }

    fun clearPhoneAlertDialog() {
        _phoneAlarmTriggered.value = false
        _currentAlarmType.value = null
    }

    // 出门提醒已合并，无需独立清除方法

    fun startMonitoring() {
        try {
            val context = getApplication<Application>().applicationContext
            val serviceIntent = Intent(context, BleMonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("HomeViewModel", "前台服务已启动")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "启动服务失败：${e.message}", e)
        }
    }

    fun stopMonitoring() {
        val context = getApplication<Application>().applicationContext
        val serviceIntent = Intent(context, BleMonitorService::class.java)
        context.stopService(serviceIntent)
    }
}