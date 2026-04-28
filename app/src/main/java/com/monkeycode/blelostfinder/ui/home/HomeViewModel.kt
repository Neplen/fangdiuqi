package com.monkeycode.blelostfinder.ui.home

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkeycode.blelostfinder.ble.BleConnectionState
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.data.local.SettingsManager
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import com.monkeycode.blelostfinder.service.BleMonitorService
import com.monkeycode.blelostfinder.ui.settings.AlarmSoundManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    
    init {
        loadDevice()
        observeBleState()
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
            }
        }
    }
    
    fun findDevice() {
        bleManager.startAlarm()
        Log.d("HomeViewModel", "触发防丢器响铃")
    }
    
    fun findPhone() {
        // 播放手机警报（循环播放）
        alarmSoundManager.playAlarm(null)
        Log.d("HomeViewModel", "触发手机响铃")
    }
    
    fun stopPhoneAlarm() {
        alarmSoundManager.stopPlaying()
        Log.d("HomeViewModel", "停止手机响铃")
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
