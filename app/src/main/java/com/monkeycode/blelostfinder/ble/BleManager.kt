package com.monkeycode.blelostfinder.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository
) {
    companion object {
        private const val TAG = "BleManager"
        
        // 双击检测时间窗口（毫秒）
        private const val DOUBLE_PRESS_TIMEOUT = 2000L
        private var lastButtonPressTime = 0L
        
        // iTAG Device Info
        const val I_DEVICE_NAME = "iTAG"
        const val I_DEVICE_MAC = "FF:FF:11:8C:4E:3B"
        
        // Alert Notification Service
        val ALERT_SERVICE_UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
        val ALERT_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")
        
        // Battery Service
        val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        
        // Custom Service for Button Press
        val CUSTOM_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CUSTOM_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        
        const val ALERT_COMMAND = 0x01.toByte()
        const val ALERT_STOP_COMMAND = 0x00.toByte()
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var alertCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var customCharacteristic: BluetoothGattCharacteristic? = null
    
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()
    
    private val _rssi = MutableStateFlow(-100)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _bleEvents = MutableSharedFlow<BleEvent>()
    val bleEvents: SharedFlow<BleEvent> = _bleEvents.asSharedFlow()

    private val bleCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: $status, newState: $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connected
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.Disconnected
                    bluetoothGatt = null
                    alertCharacteristic = null
                    batteryCharacteristic = null
                    customCharacteristic = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                alertCharacteristic = gatt.getService(ALERT_SERVICE_UUID)
                    ?.getCharacteristic(ALERT_LEVEL_CHARACTERISTIC_UUID)
                
                batteryCharacteristic = gatt.getService(BATTERY_SERVICE_UUID)
                    ?.getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
                
                customCharacteristic = gatt.getService(CUSTOM_SERVICE_UUID)
                    ?.getCharacteristic(CUSTOM_CHARACTERISTIC_UUID)
                
                // Subscribe to custom characteristic for button press
                customCharacteristic?.let {
                    gatt.setCharacteristicNotification(it, true)
                    it.descriptors.firstOrNull()?.let { descriptor ->
                        gatt.writeDescriptor(descriptor)
                    }
                }
                
                // Read battery level
                batteryCharacteristic?.let {
                    gatt.readCharacteristic(it)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            Log.d(TAG, "Characteristic read: ${characteristic.uuid}, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                    _batteryLevel.value = value[0].toInt()
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            onCharacteristicValueChanged(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onCharacteristicValueChanged(characteristic, value)
        }

        private fun onCharacteristicValueChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "Characteristic changed: ${characteristic.uuid}, value: ${value.contentToString()}")
            if (characteristic.uuid == CUSTOM_CHARACTERISTIC_UUID) {
                // 双击检测逻辑：2 秒内按两次才算双击
                val currentTime = System.currentTimeMillis()
                val lastPressTime = lastButtonPressTime
                
                if (currentTime - lastPressTime < DOUBLE_PRESS_TIMEOUT) {
                    // 检测为双击
                    lastButtonPressTime = 0
                    kotlinx.coroutines.GlobalScope.launch {
                        _bleEvents.emit(BleEvent.DoubleButtonPressed)
                    }
                    Log.d(TAG, "检测到双击事件")
                } else {
                    // 单击，记录时间
                    lastButtonPressTime = currentTime
                    Log.d(TAG, "检测到单击事件，等待第二次点击")
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            Log.d(TAG, "RSSI: $rssi, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Descriptor write: $status")
        }
    }

    fun initialize(): Boolean {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            
            if (bluetoothAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                return false
            }
            
            if (!bluetoothAdapter!!.isEnabled) {
                Log.w(TAG, "蓝牙未开启")
                return false
            }
            
            Log.d(TAG, "蓝牙适配器初始化成功")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "蓝牙适配器初始化失败", e)
            bluetoothAdapter = null
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Flow<BleConnectionState> = channelFlow {
        var isReconnecting = false
        
        try {
            // 持续循环实现自动重连
            while (true) {
                try {
                    // 关键修复：每次连接前都重新获取蓝牙适配器
                    val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    
                    if (currentAdapter == null) {
                        Log.e(TAG, "设备不支持蓝牙")
                        send(BleConnectionState.Error("设备不支持蓝牙"))
                        kotlinx.coroutines.delay(5000)
                        continue
                    }
                    
                    if (!currentAdapter.isEnabled) {
                        Log.e(TAG, "蓝牙未开启，等待开启")
                        if (!isReconnecting) {
                            send(BleConnectionState.Error("请先开启蓝牙"))
                        }
                        // 等待蓝牙开启，每秒检查一次
                        while (!currentAdapter.isEnabled) {
                            kotlinx.coroutines.delay(1000)
                        }
                        Log.d(TAG, "蓝牙已开启，尝试重连")
                        continue
                    }
                    
                    // 更新本地的适配器引用
                    bluetoothAdapter = currentAdapter

                    if (!isReconnecting) {
                        _connectionState.value = BleConnectionState.Connecting
                        send(BleConnectionState.Connecting)
                    }
                    
                    Log.d(TAG, "开始连接设备：$macAddress")

                    bluetoothDevice = try {
                        currentAdapter.getRemoteDevice(macAddress)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "无效的 MAC 地址：$macAddress", e)
                        send(BleConnectionState.Error("无效的设备地址"))
                        kotlinx.coroutines.delay(5000)
                        continue
                    } catch (e: SecurityException) {
                        Log.e(TAG, "缺少蓝牙连接权限", e)
                        send(BleConnectionState.Error("缺少蓝牙连接权限"))
                        kotlinx.coroutines.delay(5000)
                        continue
                    }

                    try {
                        bluetoothDevice?.connectGatt(context, false, bleCallback)
                        Log.d(TAG, "GATT 连接已发起")
                    } catch (e: Exception) {
                        Log.e(TAG, "连接 GATT 失败", e)
                        send(BleConnectionState.Error("连接失败：${e.message}"))
                        kotlinx.coroutines.delay(5000)
                        continue
                    }
                    
                    // Start RSSI polling every 1 second
                    val rssiJob = launch {
                        try {
                            while (true) {
                                kotlinx.coroutines.delay(1000) // 1 秒轮询一次
                                // 检查蓝牙适配器状态
                                if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                                    Log.e(TAG, "蓝牙适配器不可用，停止 RSSI 轮询")
                                    break
                                }
                                if (bluetoothGatt != null) {
                                    bluetoothGatt?.readRemoteRssi()
                                } else {
                                    Log.d(TAG, "GATT 未连接，等待重连")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "RSSI 轮询失败", e)
                        }
                    }
                    
                    awaitClose {
                        // 取消 RSSI 轮询
                        rssiJob.cancel()
                        // 不再自动断开 GATT 连接，保持长连接
                        Log.d(TAG, "Flow 取消监听，但保持 GATT 连接")
                    }
                    
                    // 如果流程走到这里，说明连接断开了，需要重连
                    Log.d(TAG, "连接断开，准备重连...")
                    _connectionState.value = BleConnectionState.Disconnected
                    send(BleConnectionState.Disconnected)
                    isReconnecting = true
                    kotlinx.coroutines.delay(3000) // 3 秒后重连
                    
                } catch (e: Exception) {
                    Log.e(TAG, "连接循环异常", e)
                    send(BleConnectionState.Error("连接异常：${e.message}"))
                    isReconnecting = true
                    kotlinx.coroutines.delay(5000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect 方法异常", e)
            send(BleConnectionState.Error("连接异常：${e.message}"))
            close()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.let { gatt ->
                try {
                    _connectionState.value = BleConnectionState.Disconnecting
                    gatt.disconnect()
                    Log.d(TAG, "GATT 断开连接")
                } catch (e: Exception) {
                    Log.e(TAG, "断开 GATT 连接失败", e)
                }
                
                try {
                    gatt.close()
                    Log.d(TAG, "GATT 已关闭")
                } catch (e: Exception) {
                    Log.e(TAG, "关闭 GATT 失败", e)
                }
                
                bluetoothGatt = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "disconnect 方法异常", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startAlarm() {
        try {
            alertCharacteristic?.let { characteristic ->
                try {
                    bluetoothGatt?.writeCharacteristic(
                        characteristic.apply {
                            value = byteArrayOf(ALERT_COMMAND)
                            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }
                    )
                    Log.d(TAG, "Alarm started")
                } catch (e: Exception) {
                    Log.e(TAG, "startAlarm 写入特征值失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startAlarm 方法异常", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAlarm() {
        try {
            alertCharacteristic?.let { characteristic ->
                bluetoothGatt?.writeCharacteristic(
                    characteristic.apply {
                        value = byteArrayOf(ALERT_STOP_COMMAND)
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                )
                Log.d(TAG, "Alarm stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopAlarm 失败", e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readBatteryLevel(): Int? {
        return try {
            withContext(Dispatchers.IO) {
                try {
                    batteryCharacteristic?.let { characteristic ->
                        try {
                            bluetoothGatt?.readCharacteristic(characteristic)
                            kotlinx.coroutines.delay(500)
                            _batteryLevel.value
                        } catch (e: Exception) {
                            Log.e(TAG, "读取电池失败", e)
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "readBatteryLevel 异常", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readBatteryLevel 外部异常", e)
            null
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
}
