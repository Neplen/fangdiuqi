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

    // 新增：出门提醒弹窗触发状态（独立于断连报警）
    private val _goOutReminderTriggered = MutableStateFlow(false)
    val goOutReminderTriggered: StateFlow<Boolean> = _goOutReminderTriggered.asStateFlow()

    private val _isDeviceBound = MutableStateFlow(false)
    val isDeviceBound: StateFlow<Boolean> = _isDeviceBound.asStateFlow()

    init {
        checkDeviceBound()
        observeBleState()
        observeBleEvents()

        // 启动时如果正在响铃，显示弹窗
        if (alarmSoundManager.isPlaying()) {
            _phoneAlarmTriggered.value = true
            Log.d("HomeViewModel", "启动时检测到正在响铃，显示弹窗")
        }
    }

    private fun checkDeviceBound() {
        viewModelScope.launch {
            val savedMac = settingsManager.deviceMac.firstOrNull()
            val hasDevice = !savedMac.isNullOrEmpty()
            _isDeviceBound.value = hasDevice
            Log.d("HomeViewModel", "设备绑定状态: $hasDevice, MAC=$savedMac")

            if (hasDevice) {
                loadDevice(savedMac!!)
                connectToDevice()
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

    // ==================== 核心修复：强制重置BLE并连接 ====================
    // 问题：蓝牙僵死后，旧GATT未释放导致新连接被系统忽略
    // 修复：先调用 cleanupGatt() 强制清理所有资源，再发起新连接
    // 用途：用户手动点击"连接"按钮时调用，确保能恢复僵死状态
    fun forceResetBleAndConnect() {
        viewModelScope.launch {
            try {
                val savedMac = settingsManager.deviceMac.firstOrNull()
                if (!savedMac.isNullOrEmpty()) {
                    Log.d("HomeViewModel", "强制重置BLE并连接: MAC=$savedMac")
                    // 第1步：强制清理所有BLE资源（断开、关闭、反射清理、状态重置）
                    bleManager.cleanupGatt()
                    // 第2步：延迟100ms确保系统蓝牙堆栈完成清理
                    kotlinx.coroutines.delay(100)
                    // 第3步：设置目标MAC并发起新连接
                    bleManager.setDeviceMacToConnect(savedMac)
                    bleManager.connectDirectly(savedMac)
                    Log.d("HomeViewModel", "强制重置后连接已发起")
                } else {
                    Log.d("HomeViewModel", "未绑定设备，无法连接")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "强制重置并连接失败：${e.message}", e)
            }
        }
    }
    // =================================================================

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
            _phoneAlarmTriggered.value = false

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
     * 停止出门提醒（独立方法，不影响断连报警状态）
     */
    fun stopGoOutReminder() {
        try {
            alarmSoundManager.stopPlaying()
            _goOutReminderTriggered.value = false

            val context = getApplication<Application>().applicationContext
            val stopIntent = Intent(context, BleMonitorService::class.java).apply {
                action = BleMonitorService.ACTION_STOP_GO_OUT_REMINDER
            }
            try {
                context.startService(stopIntent)
                Log.d("HomeViewModel", "已通知 Service 停止出门提醒")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "通知 Service 停止出门提醒失败", e)
            }

            Log.d("HomeViewModel", "停止出门提醒")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "停止出门提醒失败", e)
        }
    }

    fun clearPhoneAlertDialog() {
        _phoneAlarmTriggered.value = false
    }

    fun clearGoOutReminderDialog() {
        _goOutReminderTriggered.value = false
    }

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