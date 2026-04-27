package com.monkeycode.blelostfinder.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class ScanResult(
    val device: BluetoothDevice,
    val rssi: Int,
    val scanRecord: ScanRecord
) {
    val name: String get() = scanRecord.deviceName ?: device.name ?: "未知设备"
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
    private var scanner: BluetoothLeScanner? = null
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
    fun startScan(): Flow<ScanResult> = channelFlow {
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
                
                val scanResult = ScanResult(
                    device = result.device,
                    rssi = result.rssi,
                    scanRecord = result.scanRecord
                )
                
                // 只发送给没有 name 的设备，或者 iTAG 相关设备
                if (scanResult.name.isNotBlank() && 
                    (scanResult.name.contains("iTAG", ignoreCase = true) || 
                     scanResult.name.contains("iSearching", ignoreCase = true) ||
                     scanResult.name.contains("Tag", ignoreCase = true))) {
                    trySend(scanResult)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "Scan failed: $errorCode")
                isScanning = false
            }
        }

        val scanFilter = ScanFilter.Builder()
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
            close()
            return@channelFlow
        }

        awaitClose {
            scanner.stopScan(scanCallback)
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            scanner?.stopScan(object : ScanCallback() {})
            isScanning = false
            Log.d(TAG, "Scan stopped")
        }
    }
}