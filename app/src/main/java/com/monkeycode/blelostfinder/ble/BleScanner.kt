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

    private var currentScanCallback: ScanCallback? = null

    // ===== 修复：增加扫描作用域，确保cancel时正确清理 =====
    private var scanJob: kotlinx.coroutines.Job? = null
    // =====================================================

    init {
        initialize()
    }

    private fun initialize() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            scanner = bluetoothAdapter?.bluetoothLeScanner

            if (bluetoothAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
            } else {
                Log.d(TAG, "BleScanner 初始化成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "BleScanner 初始化失败", e)
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Flow<ScanResultWrapper> = channelFlow {
        try {
            // ===== 修复：如果已有扫描在进行，先停止 =====
            if (isScanning) {
                Log.w(TAG, "已有扫描在进行，先停止旧扫描")
                stopScan()
                kotlinx.coroutines.delay(500)
            }
            // ==========================================

            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

            if (currentAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                close()
                return@channelFlow
            }

            if (!currentAdapter.isEnabled) {
                Log.e(TAG, "蓝牙未开启")
                close()
                return@channelFlow
            }

            val scanner = currentAdapter.bluetoothLeScanner ?: run {
                Log.e(TAG, "BluetoothLeScanner not available")
                close()
                return@channelFlow
            }

            isScanning = true

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)

                    try {
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

            currentScanCallback = scanCallback

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(emptyList<ScanFilter>(), scanSettings, scanCallback)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied", e)
                isScanning = false
                close()
                return@channelFlow
            } catch (e: Exception) {
                Log.e(TAG, "启动扫描失败", e)
                isScanning = false
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
                currentScanCallback = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "startScan 异常", e)
            isScanning = false
            close()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            try {
                currentScanCallback?.let { callback ->
                    scanner?.stopScan(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
            isScanning = false
            currentScanCallback = null
            Log.d(TAG, "Scan stopped")
        }
    }
}