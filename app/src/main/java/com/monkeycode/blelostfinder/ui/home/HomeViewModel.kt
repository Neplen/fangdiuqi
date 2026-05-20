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

    // 报警按钮状态（手动控制防丢器响铃）
    private val _isDeviceAlarmPlaying = MutableStateFlow(false)
    val isDeviceAlarmPlaying: StateFlow<Boolean> = _isDeviceAlarmPlaying.asStateFlow()

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

        // 核心修复：启动时如果 Service 正在响铃（如过夜后），立即显示弹窗
        // 因为 replay=0 的 SharedFlow 不会把旧事件发给新订阅者
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

    // 核心修复：双击事件只处理弹窗显示/关闭，不再处理响铃
    // 响铃统一由 BleMonitorService 处理，解决前后台状态竞争
    private fun observeBleEvents() {
        viewModelScope.launch {
            bleManager.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.ButtonPressed -> {
                        Log.d("HomeViewModel", "检测到防丢器单击，忽略")
                    }
                    is BleEvent.DoubleButtonPressed -> {
                        // 只处理弹窗：响铃由 Service 全权负责
                        Log.d("HomeViewModel", "检测到防丢器双击，切换弹窗状态")
                        _phoneAlarmTriggered.value = !_phoneAlarmTriggered.value
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

    // 保留方法：供手动测试或外部调用触发手机报警
    // 正常流程下，手机报警由 Service 自动处理，ViewModel 不再主动触发
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

    // 核心修复：stopPhoneAlarm 停止弹窗和铃声，同时通知 Service 重置 isAlarmPlaying
    // 否则 Service 的 isAlarmPlaying 仍为 true，后续断连不会自动报警
    fun stopPhoneAlarm() {
        try {
            // 停止防丢器响铃（如果手动触发过）
            if (_isDeviceAlarmPlaying.value) {
                viewModelScope.launch {
                    bleManager.stopAlarm()
                }
                _isDeviceAlarmPlaying.value = false
            }
            // 停止手机铃声
            alarmSoundManager.stopPlaying()
            _phoneAlarmTriggered.value = false

            // 核心修复：发送 Intent 通知 Service 重置报警状态
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