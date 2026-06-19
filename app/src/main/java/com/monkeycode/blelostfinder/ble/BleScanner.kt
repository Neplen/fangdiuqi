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

    // ==================== 核心修复：保存当前扫描回调，确保能正确停止扫描 ====================
    // 问题：stopScan() 使用了新的空 ScanCallback，导致扫描实际上没有停止
    // 表现：再次扫描时系统冲突，返回0设备
    // 修复：保存启动扫描时使用的 callback 实例，stopScan() 时复用
    private var currentScanCallback: ScanCallback? = null
    // =================================================================================

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
        // 每次检查都重新获取蓝牙适配器，确保用户手动开启蓝牙后能正确检测到
        val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Flow<ScanResultWrapper> = channelFlow {
        try {
            // 重新获取蓝牙适配器，确保用户手动开启蓝牙后能正确检测
            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

            if (currentAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                // 扫描方法只返回 ScanResultWrapper 类型，不能返回 BleConnectionState
                // 直接关闭流，不发送任何数据
                close()
                return@channelFlow
            }

            if (!currentAdapter.isEnabled) {
                Log.e(TAG, "蓝牙未开启")
                // 扫描方法只返回 ScanResultWrapper 类型，不能返回 BleConnectionState
                // 直接关闭流，不发送任何数据
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

            // 保存当前扫描回调，用于正确停止扫描
            currentScanCallback = scanCallback

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
                // 清空保存的回调
                currentScanCallback = null
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
                // ==================== 核心修复：使用保存的 callback 停止扫描 ====================
                // 问题：之前使用新的空 ScanCallback，Android 无法匹配到正在运行的扫描
                // 修复：使用 currentScanCallback（即启动扫描时的同一个实例）
                currentScanCallback?.let { callback ->
                    scanner?.stopScan(callback)
                }
                // =============================================================================
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
            isScanning = false
            currentScanCallback = null
            Log.d(TAG, "Scan stopped")
        }
    }
}