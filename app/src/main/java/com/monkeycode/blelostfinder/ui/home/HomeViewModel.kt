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

    private val _isDeviceAlarmPlaying = MutableStateFlow(false)
    val isDeviceAlarmPlaying: StateFlow<Boolean> = _isDeviceAlarmPlaying.asStateFlow()

    val isMonitoringRunning = isRunning

    private val _phoneAlarmTriggered = MutableStateFlow(false)
    val phoneAlarmTriggered: StateFlow<Boolean> = _phoneAlarmTriggered.asStateFlow()

    init {
        loadDevice()
        observeBleState()
        observeBleEvents()
        connectToDevice()

        // 启动时如果正在响铃，显示弹窗
        if (alarmSoundManager.isPlaying()) {
            _phoneAlarmTriggered.value = true
            Log.d("HomeViewModel", "启动时检测到正在响铃，显示弹窗")
        }
    }

    fun connectToDevice() {
        try {
            bleManager.connectDirectly(BleManager.I_DEVICE_MAC)
            Log.d("HomeViewModel", "已调用 connectDirectly()")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "连接失败：${e.message}", e)
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
                if (state is BleConnectionState.Connected) {
                    Log.d("HomeViewModel", "设备已连接，重置报警状态")
                    _isDeviceAlarmPlaying.value = false
                }
            }
        }
    }

    // 核心修复：双击事件只处理弹窗，响铃由 Service 全权负责
    // 弹窗状态根据实际响铃状态设置，避免和 AlarmTriggered 事件时序竞争
    private fun observeBleEvents() {
        viewModelScope.launch {
            bleManager.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.ButtonPressed -> {
                        Log.d("HomeViewModel", "检测到防丢器单击，忽略")
                    }
                    is BleEvent.DoubleButtonPressed -> {
                        // 核心修复：根据实际响铃状态设置弹窗，而不是切换状态
                        // 这样无论 AlarmTriggered 和 DoubleButtonPressed 谁先谁后，
                        // 最终弹窗状态都和实际响铃状态一致
                        val isPlaying = alarmSoundManager.isPlaying()
                        _phoneAlarmTriggered.value = isPlaying
                        Log.d("HomeViewModel", "检测到防丢器双击，响铃状态=$isPlaying，弹窗=${isPlaying}")
                    }
                    is BleEvent.AlarmTriggered -> {
                        // Service 触发报警（断连或双击），显示弹窗
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
                val ringtonePath = settingsManager.alarmRingtonePath.first()
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
                Log.d("HomeViewModel", "已通知 Service 重置报警状态")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "通知 Service 失败", e)
            }

            Log.d("HomeViewModel", "停止所有响铃并关闭弹窗")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "停止响铃失败", e)
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