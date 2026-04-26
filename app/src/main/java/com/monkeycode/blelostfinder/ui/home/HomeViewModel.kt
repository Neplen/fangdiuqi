package com.monkeycode.blelostfinder.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkeycode.blelostfinder.ble.BleConnectionState
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import com.monkeycode.blelostfinder.service.BleMonitorService
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
    private val settingsManager: SettingsManager
) : AndroidViewModel(application) {
    
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()
    
    private val _device = MutableStateFlow<BleDevice?>(null)
    val device: StateFlow<BleDevice?> = _device.asStateFlow()
    
    val rssi = bleManager.rssi
    val batteryLevel = bleManager.batteryLevel
    
    init {
        loadDevice()
        observeBleState()
    }
    
    private fun loadDevice() {
        viewModelScope.launch {
            deviceRepository.getDeviceByMac(BleManager.I_DEVICE_MAC).collect { device: BleDevice? ->
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
    }
    
    fun findPhone() {
    }
    
    fun startMonitoring() {
        val context = getApplication<Application>().applicationContext
        val serviceIntent = android.content.Intent(context, BleMonitorService::class.java)
        context.startForegroundService(serviceIntent)
    }
    
    fun stopMonitoring() {
        val context = getApplication<Application>().applicationContext
        val serviceIntent = android.content.Intent(context, BleMonitorService::class.java)
        context.stopService(serviceIntent)
    }
}
