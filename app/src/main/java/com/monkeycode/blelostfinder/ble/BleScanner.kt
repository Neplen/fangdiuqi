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
            // ==================== 核心修复：每次扫描前重新获取蓝牙适配器和扫描器 ====================
            // 问题：如果蓝牙出现过错误（如GATT僵死），旧的 scanner 引用可能失效
            // 表现：startScan() 后 onScanResult 从不回调，显示0设备
            // 修复：每次扫描都重新从系统获取最新的 BluetoothLeScanner 实例
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

            // 重新获取 scanner，不使用 init 中缓存的引用
            val currentScanner = currentAdapter.bluetoothLeScanner ?: run {
                Log.e(TAG, "BluetoothLeScanner not available (可能蓝牙底层异常)")
                close()
                return@channelFlow
            }

            // 更新缓存的 adapter 和 scanner
            bluetoothAdapter = currentAdapter
            scanner = currentScanner
            // =================================================================================

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
                currentScanner.startScan(emptyList<ScanFilter>(), scanSettings, scanCallback)
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
                    currentScanner.stopScan(scanCallback)
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
                // 使用当前缓存的 scanner，如果为null则尝试重新获取
                val currentScanner = scanner ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
                currentScanner?.stopScan(object : ScanCallback() {})
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
            isScanning = false
            Log.d(TAG, "Scan stopped")
        }
    }
}