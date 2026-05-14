package com.monkeycode.blelostfinder.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
    
    // 保存要连接的设备地址，用于断连后重连
    private var deviceMacToConnect: String? = null
    
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()
    
    private val _rssi = MutableStateFlow(-100)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _bleEvents = MutableSharedFlow<BleEvent>(replay = 1)
    val bleEvents: SharedFlow<BleEvent> = _bleEvents.asSharedFlow()
    
    // Handler 用于延迟重连（ avoid recursive type checking issue with coroutines in anonymous objects）
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bleCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: $status, newState: $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connected
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                    // 核心修复：连接成功后启动 RSSI 轮询（确保 GATT 有效）
                    startRssiPolling()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "设备已断开，准备自动重连...")
                    _connectionState.value = BleConnectionState.Disconnected
                    bluetoothGatt = null
                    alertCharacteristic = null
                    batteryCharacteristic = null
                    customCharacteristic = null
                    
                    // 取消 RSSI 轮询
                    rssiPollingJob?.cancel()
                    rssiPollingJob = null
                    
                    // 断连时立即触发防丢器报警（通过 BLE 命令）
                    // 注意：此时 GATT 已断开，无法发送 BLE 命令，需要在 HomeViewModel 中处理
                    // 这里只发送断连事件，让上层处理报警逻辑
                    mainHandler.post {
                        try {
                            _bleEvents.tryEmit(BleEvent.Disconnected)
                        } catch (e: Exception) {
                            Log.e(TAG, "发送断连事件失败", e)
                        }
                    }
                    
                    // 自动重连逻辑：3 秒后尝试重连
                    deviceMacToConnect?.let { mac ->
                        // 使用 Handler 延迟重连，避免在匿名对象中使用协程导致递归类型检查问题
                        mainHandler.postDelayed({
                            try {
                                Log.d(TAG, "开始自动重连设备：$mac")
                                scheduleReconnect(mac)
                            } catch (e: Exception) {
                                Log.e(TAG, "自动重连失败", e)
                            }
                        }, 3000)
                    }
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
                    // 使用 Handler 发送事件，避免在匿名对象中使用协程
                    mainHandler.post {
                        try {
                            _bleEvents.tryEmit(BleEvent.DoubleButtonPressed)
                        } catch (e: Exception) {
                            Log.e(TAG, "发送双击事件失败", e)
                        }
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
        try {
            // 保存设备地址用于重连
            deviceMacToConnect = macAddress
            
            // 每次连接前都重新获取蓝牙适配器
            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            
            if (currentAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                send(BleConnectionState.Error("设备不支持蓝牙"))
                return@channelFlow
            }
            
            if (!currentAdapter.isEnabled) {
                Log.e(TAG, "蓝牙未开启")
                send(BleConnectionState.Error("请先开启蓝牙"))
                
                // 等待蓝牙开启
                while (!currentAdapter.isEnabled) {
                    kotlinx.coroutines.delay(1000)
                }
                Log.d(TAG, "蓝牙已开启")
            }
            
            // 更新本地的适配器引用
            bluetoothAdapter = currentAdapter

            _connectionState.value = BleConnectionState.Connecting
            send(BleConnectionState.Connecting)
            
            Log.d(TAG, "开始连接设备：$macAddress")

            bluetoothDevice = try {
                currentAdapter.getRemoteDevice(macAddress)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "无效的 MAC 地址：$macAddress", e)
                send(BleConnectionState.Error("无效的设备地址"))
                return@channelFlow
            } catch (e: SecurityException) {
                Log.e(TAG, "缺少蓝牙连接权限", e)
                send(BleConnectionState.Error("缺少蓝牙连接权限"))
                return@channelFlow
            }

            try {
                bluetoothDevice?.connectGatt(context, false, bleCallback)
                Log.d(TAG, "GATT 连接已发起")
            } catch (e: Exception) {
                Log.e(TAG, "连接 GATT 失败", e)
                send(BleConnectionState.Error("连接失败：${e.message}"))
                return@channelFlow
            }
            
            // Start RSSI polling every 1 second
            launch {
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
                Log.d(TAG, "Flow 取消监听")
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect 方法异常", e)
            send(BleConnectionState.Error("连接异常：${e.message}"))
            close()
        }
    }
    
   /**
     * 直接发起连接（不依赖 Flow 收集，用于后台服务）
     * 这个方法会立即执行连接逻辑，适合在后台场景使用
     * 
     * 核心修复：
     * 1. 强制关闭旧 GATT（即使失败也要重置状态）
     * 2. 连接成功回调中会自动启动 RSSI 轮询
     */
    @SuppressLint("MissingPermission")
    fun connectDirectly(macAddress: String) {
        try {
            // 保存设备地址用于重连
            deviceMacToConnect = macAddress
            
            // 每次连接前都重新获取蓝牙适配器（后台场景必需）
            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            
            if (currentAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                return
            }
            
            if (!currentAdapter.isEnabled) {
                Log.w(TAG, "蓝牙未开启，等待开启")
                return
            }
            
            // 更新本地的适配器引用
            bluetoothAdapter = currentAdapter
            
            // 核心修复：强制清理旧 GATT（解决"僵尸 GATT"问题）
            cleanupGatt()

            _connectionState.value = BleConnectionState.Connecting
            Log.d(TAG, "直接连接设备：$macAddress")

            bluetoothDevice = try {
                currentAdapter.getRemoteDevice(macAddress)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "无效的 MAC 地址：$macAddress", e)
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "缺少蓝牙连接权限", e)
                return
            }

            try {
                // 创建新 GATT 连接
                bluetoothDevice?.connectGatt(context, false, bleCallback)
                Log.d(TAG, "GATT 直接连接已发起")
                
                // 核心修复 2: 移除这里的 startRssiPolling()
                // RSSI 轮询会在 onConnectionStateChange(STATE_CONNECTED) 中启动
                // 这样确保 GATT 完全连接后再开始轮询
            } catch (e: Exception) {
                Log.e(TAG, "直接连接 GATT 失败", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectDirectly 方法异常", e)
        }
    }
    
    /**
     * 启动 RSSI 轮询（统一由连接成功回调调用）
     */
    private var rssiPollingJob: kotlinx.coroutines.Job? = null
    
    private fun startRssiPolling() {
        // 取消之前的轮询
        rssiPollingJob?.cancel()
        
        // 使用 GlobalScope 启动独立轮询
        rssiPollingJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                while (true) {
                    kotlinx.coroutines.delay(1000)  // 1 秒轮询一次
                    
                    // 检查蓝牙适配器状态
                    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                        Log.e(TAG, "蓝牙适配器不可用，停止 RSSI 轮询")
                        break
                    }
                    
                    if (bluetoothGatt != null) {
                        try {
                            bluetoothGatt?.readRemoteRssi()
                        } catch (e: Exception) {
                            Log.e(TAG, "RSSI 读取失败", e)
                        }
                    } else {
                        Log.d(TAG, "GATT 未连接，停止 RSSI 轮询")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RSSI 轮询异常", e)
            }
        }
        Log.d(TAG, "RSSI 轮询已启动")
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
    
    /**
     * 执行重连逻辑（在回调外部调用，避免递归类型检查问题）
     */
    private fun scheduleReconnect(mac: String) {
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) 
            as? BluetoothManager)?.adapter
        
        if (currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                val device = currentAdapter.getRemoteDevice(mac)
                device.connectGatt(context, false, bleCallback)
                Log.d(TAG, "自动重连 GATT 已发起")
            } catch (e: Exception) {
                Log.e(TAG, "自动重连失败", e)
            }
        } else {
            Log.e(TAG, "蓝牙未开启，等待开启后重连")
        }
    }
    
    /**
     * 蓝牙关闭后重新开启时，或检测到设备断开时，自动重连设备
     * 这个方法会在以下场景被调用：
     * 1. MainActivity 收到蓝牙开启广播
     * 2. BleMonitorService 定期调用（锁屏状态下）
     * 3. 用户回到 APP 主页
     * 
     * 后台场景核心修复：
     * - 使用 connectDirectly() 而不是 connect()
     * - connect() 是 channelFlow，需要被 collect 才会执行
     * - 后台时 HomeViewModel 可能不被 collect，导致连接逻辑不执行
     */
    fun reconnectIfDisconnected() {
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        
        if (deviceMacToConnect != null && currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                
                // 核心修复：重连前强制清理旧 GATT
                cleanupGatt()
                
                // 直接调用 connectDirectly()
                connectDirectly(deviceMacToConnect!!)
                
                Log.d(TAG, "蓝牙重连已发起：${deviceMacToConnect}")
            } catch (e: Exception) {
                Log.e(TAG, "蓝牙重连失败", e)
            }
        } else {
            if (deviceMacToConnect == null) {
                Log.d(TAG, "没有保存的设备地址，跳过重连")
            } else if (currentAdapter == null) {
                Log.w(TAG, "蓝牙适配器不可用，等待初始化")
            } else {
                Log.w(TAG, "蓝牙未开启，等待用户手动开启")
            }
        }
    }
    
    /**
     * 强制清理 GATT 资源（解决僵尸连接问题）
     */
    private fun cleanupGatt() {
        bluetoothGatt?.let { oldGatt ->
            try {
                Log.d(TAG, "清理旧 GATT 资源")
                
                // 1. 断开连接
                try { oldGatt.disconnect() } catch (e: Exception) {}
                
                // 2. 关闭 GATT
                try { oldGatt.close() } catch (e: Exception) {}
                
                // 3. 反射关闭内部对象
                try {
                    val mBluetoothGattField = oldGatt.javaClass.getDeclaredField("mBluetoothGatt")
                    mBluetoothGattField.isAccessible = true
                    val mBluetoothGatt = mBluetoothGattField.get(oldGatt)
                    if (mBluetoothGatt != null) {
                        val closeMethod = mBluetoothGatt.javaClass.getMethod("close")
                        closeMethod.invoke(mBluetoothGatt)
                    }
                } catch (e: Exception) {}
                
                // 4. 清除回调
                try {
                    val callbackField = oldGatt.javaClass.getDeclaredField("mCallback")
                    callbackField.isAccessible = true
                    callbackField.set(oldGatt, null)
                } catch (e: Exception) {}
                
            } catch (e: Exception) {
                Log.e(TAG, "清理 GATT 异常", e)
            }
            bluetoothGatt = null
            alertCharacteristic = null
            batteryCharacteristic = null
            customCharacteristic = null
        }
    }
    
    /**
     * 发送报警触发事件（Service 调用，通知 ViewModel 显示弹窗）
     */
    fun emitAlarmEvent(reason: String) {
        try {
            _bleEvents.tryEmit(BleEvent.AlarmTriggered(reason))
        } catch (e: Exception) {
            Log.e(TAG, "发送报警事件失败", e)
        }
    }
}
