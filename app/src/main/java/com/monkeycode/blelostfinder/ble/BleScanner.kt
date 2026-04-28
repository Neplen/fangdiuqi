package com.monkeycode.blelostfinder.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ScanResultWrapper(
    val device: BluetoothDevice,
    val rssi: Int,
    val scanRecord: android.bluetooth.le.ScanRecord?
) {
    val name: String? get() = scanRecord?.deviceName ?: device.name
    val macAddress: String get() = device.address
}

@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleScanner"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var isScanning = false

    init {
        initialize()
    }

    private fun initialize() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Flow<ScanResultWrapper> = channelFlow {
        try {
            if (!isBluetoothEnabled()) {
                Log.e(TAG, "Bluetooth not enabled")
                close()
                return@channelFlow
            }
    
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
                Log.e(TAG, "BluetoothLeScanner not available")
                close()
                return@channelFlow
            }
    
            isScanning = true
    
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)
                    
                    try {
                        // 不过滤设备，显示所有 BLE 设备
                        val scanResult = ScanResultWrapper(
                            device = result.device,
                            rssi = result.rssi,
                            scanRecord = result.scanRecord
                        )
                        
                        trySend(scanResult)
                    } catch (e: Exception) {
                        Log.e(TAG, "处理扫描结果失败", e)
                    }
                }
    
                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    Log.e(TAG, "Scan failed: $errorCode")
                    isScanning = false
                }
            }
    
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
    
            try {
                // 不带过滤器扫描所有设备
                scanner.startScan(emptyList<ScanFilter>(), scanSettings, scanCallback)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied", e)
                close()
                return@channelFlow
            } catch (e: Exception) {
                Log.e(TAG, "启动扫描失败", e)
                close()
                return@channelFlow
            }
    
            awaitClose {
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping scan", e)
                }
                isScanning = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "startScan 异常", e)
            close()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            try {
                scanner?.stopScan(object : ScanCallback() {})
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
            isScanning = false
            Log.d(TAG, "Scan stopped")
        }
    }
}
