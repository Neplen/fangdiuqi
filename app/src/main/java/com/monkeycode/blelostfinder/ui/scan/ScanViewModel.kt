package com.monkeycode.blelostfinder.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.ble.BleScanner
import com.monkeycode.blelostfinder.ble.ScanResultWrapper
import com.monkeycode.blelostfinder.data.local.SettingsManager
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    application: Application,
    private val bleScanner: BleScanner,
    private val bleManager: BleManager,
    private val deviceRepository: DeviceRepository,
    private val settingsManager: SettingsManager
) : AndroidViewModel(application) {

    private val _scanResults = MutableStateFlow<List<ScanResultWrapper>>(emptyList())
    val scanResults: StateFlow<List<ScanResultWrapper>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow<String?>(null)
    val connectionState: StateFlow<String?> = _connectionState.asStateFlow()

    private val deviceMap = mutableMapOf<String, ScanResultWrapper>()

    fun startScan() {
        viewModelScope.launch {
            deviceMap.clear()
            _scanResults.value = emptyList()
            _isScanning.value = true

            bleScanner.startScan().collect { scanResult ->
                // 去重：同一个 MAC 只保留最后一次
                if (!deviceMap.containsKey(scanResult.macAddress)) {
                    deviceMap[scanResult.macAddress] = scanResult
                    _scanResults.value = deviceMap.values.toList()
                }
            }
        }
    }

    fun stopScan() {
        _isScanning.value = false
        bleScanner.stopScan()
    }

    // ===== 修改：连接成功后同时保存设备名称和 MAC 到 DataStore =====
    fun connectToDevice(scanResult: ScanResultWrapper) {
        viewModelScope.launch {
            try {
                _isScanning.value = false
                bleScanner.stopScan()

                val deviceName = scanResult.name ?: "BLE Device"

                // 保存设备到数据库
                val device = BleDevice(
                    macAddress = scanResult.macAddress,
                    name = deviceName,
                    rssiThreshold = -90
                )
                deviceRepository.insertDevice(device)

                // ===== 修改：同时保存设备名称和 MAC 到 DataStore =====
                settingsManager.updateDeviceMac(scanResult.macAddress)
                settingsManager.updateDeviceName(deviceName)

                // 设置 BleManager 的目标 MAC（用于断连后自动重连）
                bleManager.setDeviceMacToConnect(scanResult.macAddress)

                // 开始连接
                _connectionState.value = "正在连接 ${scanResult.name}..."

                bleManager.connect(scanResult.macAddress).collect { state ->
                    when (state) {
                        is com.monkeycode.blelostfinder.ble.BleConnectionState.Connected -> {
                            _connectionState.value = "连接成功！${scanResult.name}"
                        }
                        is com.monkeycode.blelostfinder.ble.BleConnectionState.Disconnected -> {
                            _connectionState.value = "已断开：${scanResult.name}"
                        }
                        is com.monkeycode.blelostfinder.ble.BleConnectionState.Error -> {
                            _connectionState.value = "连接错误：${state.message}"
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = "连接失败：${e.message}"
            }
        }
    }
}