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
import kotlinx.coroutines.flow.first
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
    
    // 报警按钮状态
    private val _isDeviceAlarmPlaying = MutableStateFlow(false)
    val isDeviceAlarmPlaying: StateFlow<Boolean> = _isDeviceAlarmPlaying.asStateFlow()
    
    // 手机报警状态
    private var isPhoneAlarmPlaying = false
    
    // 服务运行状态
    val isMonitoringRunning = isRunning
    
    // 手机报警弹窗触发
    private val _phoneAlarmTriggered = MutableStateFlow(false)
    val phoneAlarmTriggered: StateFlow<Boolean> = _phoneAlarmTriggered.asStateFlow()
    
    init {
        loadDevice()
        observeBleState()
        observeBleEvents()
        connectToDevice()
    }
    
    fun connectToDevice() {
        viewModelScope.launch {
            try {
                bleManager.connect(BleManager.I_DEVICE_MAC).collect { state ->
                    // 自动重连逻辑已在 BleManager 中实现
                    // 这里只需要监听状态
                    Log.d("HomeViewModel", "连接状态：${state::class.simpleName}")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "连接失败：${e.message}", e)
            }
        }
    }
    
    private fun loadDevice() {
        viewModelScope.launch {
            deviceRepository.getDeviceByMacFlow(BleManager.I_DEVICE_MAC).collect { device: BleDevice? ->
                _device.value = device
            }
        }
    }
    
    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.connectionState.collect { state: BleConnectionState ->
                _connectionState.value = state
                // 连接成功时重置报警状态
                if (state is BleConnectionState.Connected) {
                    Log.d("HomeViewModel", "设备已连接，重置报警状态")
                    _isDeviceAlarmPlaying.value = false
                    if (isPhoneAlarmPlaying) {
                        stopPhoneAlarm()
                    }
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
                        if (isPhoneAlarmPlaying) {
                            Log.d("HomeViewModel", "检测到防丢器双击，正在报警中，停止报警")
                            stopPhoneAlarm()
                        } else {
                            Log.d("HomeViewModel", "检测到防丢器双击，触发手机报警")
                            triggerPhoneAlarm()
                        }
                    }
                    is BleEvent.Disconnected -> {
                        Log.d("HomeViewModel", "检测到断连事件，触发手机和防丢器同时报警")
                        triggerBothAlarms()
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
    
    fun triggerBothAlarms() {
        viewModelScope.launch {
            try {
                Log.d("HomeViewModel", "触发双向报警：手机 + 防丢器（防丢器固件会自动报警，APP 触发手机报警）")
                // 注意：断连后无法通过 BLE 命令控制防丢器，防丢器固件会在断连时自动报警
                // 这里只需要触发手机报警即可
                triggerPhoneAlarm()
                // 更新 UI 状态，显示按钮为"已报警"
                _isDeviceAlarmPlaying.value = true
            } catch (e: Exception) {
                Log.e("HomeViewModel", "触发双向报警失败", e)
            }
        }
    }
    
    fun triggerPhoneAlarm() {
        viewModelScope.launch {
            try {
                // 先停止之前的铃声，防止叠加
                alarmSoundManager.stopPlaying()
                // 播放手机警报（循环播放）
                val ringtonePath = settingsManager.alarmRingtonePath.first()
                alarmSoundManager.playAlarm(ringtonePath)
                // 触发弹窗
                _phoneAlarmTriggered.value = true
                isPhoneAlarmPlaying = true
                Log.d("HomeViewModel", "触发手机响铃，铃声类型：${ringtonePath ?: "默认"}")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "触发手机响铃失败", e)
            }
        }
    }
    
    fun stopPhoneAlarm() {
    // 同步立即停止，不使用协程，解决铃声无法停止的问题
            try {
                alarmSoundManager.stopPlaying()
                _phoneAlarmTriggered.value = false
                isPhoneAlarmPlaying = false
                Log.d("HomeViewModel", "停止手机响铃")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "停止手机响铃失败", e)
            }
    }
    
    fun clearPhoneAlertDialog() {
        _phoneAlarmTriggered.value = false
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
