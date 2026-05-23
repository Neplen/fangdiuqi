package com.monkeycode.blelostfinder.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
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

        private const val DOUBLE_PRESS_TIMEOUT = 2000L
        private var lastButtonPressTime = 0L

        private var lastDoubleClickTime = 0L
        private const val DOUBLE_CLICK_COOLDOWN = 800L

        const val I_DEVICE_NAME = "iTAG"
        const val I_DEVICE_MAC = "FF:FF:11:8C:4E:3B"

        val ALERT_SERVICE_UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
        val ALERT_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

        val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        val CUSTOM_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CUSTOM_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        // 核心修复：新增断连报警配置特征值（i-Searching 同款）
        val DISCONNECT_ALARM_CHARACTERISTIC_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

        const val ALERT_COMMAND = 0x01.toByte()
        const val ALERT_STOP_COMMAND = 0x00.toByte()
        // 断连报警配置命令
        const val DISCONNECT_ALARM_ENABLE = 0x01.toByte()
        const val DISCONNECT_ALARM_DISABLE = 0x00.toByte()
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var alertCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var customCharacteristic: BluetoothGattCharacteristic? = null
    private var disconnectAlarmCharacteristic: BluetoothGattCharacteristic? = null

    // ==================== 核心修复：缓存待写入的断连报警配置 ====================
    // 当 FFE2 特征值尚未发现时，先缓存状态，等服务发现完成后自动写入
    private var pendingDisconnectAlarmState: Boolean? = null

    private var deviceMacToConnect: String? = null

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _rssi = MutableStateFlow(-100)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _bleEvents = MutableSharedFlow<BleEvent>(replay = 0, extraBufferCapacity = 1)
    val bleEvents: SharedFlow<BleEvent> = _bleEvents.asSharedFlow()

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== 新增：统一决策防丢器断连报警配置 ====================

    /**
     * 判断当前是否应该开启防丢器的"断连自动报警"功能
     * 优先级：WiFi勿扰 > 定时勿扰 > 断连自动报警开关
     */
    fun shouldEnableDeviceDisconnectAlarm(
        isWifiDndEnabled: Boolean,
        isWifiConnected: Boolean,
        isScheduleDndEnabled: Boolean,
        isInDndTimeRange: Boolean,
        isDisconnectAlarmEnabled: Boolean
    ): Boolean {
        // 1. WiFi勿扰最高优先级：开启且当前已连接WiFi → 防丢器不报警
        if (isWifiDndEnabled && isWifiConnected) {
            Log.d(TAG, "决策结果：WiFi勿扰生效，防丢器断连报警禁用")
            return false
        }

        // 2. 定时勿扰：开启且当前处于勿扰时段 → 防丢器不报警
        if (isScheduleDndEnabled && isInDndTimeRange) {
            Log.d(TAG, "决策结果：定时勿扰生效，防丢器断连报警禁用")
            return false
        }

        // 3. 最后由"断连自动报警"开关决定
        Log.d(TAG, "决策结果：由断连报警开关决定=$isDisconnectAlarmEnabled")
        return isDisconnectAlarmEnabled
    }

    /**
     * 向防丢器同步当前的断连报警配置
     * 在设置变化、WiFi变化、时间变化时调用
     */
    fun syncDeviceDisconnectAlarmConfig(
        isWifiDndEnabled: Boolean,
        isWifiConnected: Boolean,
        isScheduleDndEnabled: Boolean,
        isInDndTimeRange: Boolean,
        isDisconnectAlarmEnabled: Boolean
    ) {
        val shouldEnable = shouldEnableDeviceDisconnectAlarm(
            isWifiDndEnabled,
            isWifiConnected,
            isScheduleDndEnabled,
            isInDndTimeRange,
            isDisconnectAlarmEnabled
        )
        setDisconnectAlarmEnabled(shouldEnable)
    }

    private val bleCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: $status, newState: $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connected
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                    startRssiPolling()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "设备已断开，准备自动重连...")
                    _connectionState.value = BleConnectionState.Disconnected
                    bluetoothGatt = null
                    alertCharacteristic = null
                    batteryCharacteristic = null
                    customCharacteristic = null
                    disconnectAlarmCharacteristic = null
                    pendingDisconnectAlarmState = null  // 断连后清除缓存

                    rssiPollingJob?.cancel()
                    rssiPollingJob = null

                    managerScope.launch {
                        try {
                            _bleEvents.emit(BleEvent.Disconnected)
                        } catch (e: Exception) {
                            Log.e(TAG, "发送断连事件失败", e)
                        }
                    }

                    deviceMacToConnect?.let { mac ->
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

                customCharacteristic?.let {
                    gatt.setCharacteristicNotification(it, true)
                    it.descriptors.firstOrNull()?.let { descriptor ->
                        gatt.writeDescriptor(descriptor)
                    }
                }

                // 核心修复：发现断连报警配置特征值
                disconnectAlarmCharacteristic = gatt.getService(CUSTOM_SERVICE_UUID)
                    ?.getCharacteristic(DISCONNECT_ALARM_CHARACTERISTIC_UUID)
                Log.d(TAG, "断连报警特征值: ${disconnectAlarmCharacteristic != null}")

                // ==================== 核心修复：服务发现完成后，写入缓存的断连报警配置 ====================
                pendingDisconnectAlarmState?.let { pending ->
                    Log.d(TAG, "服务发现完成，写入缓存的断连报警配置: $pending")
                    setDisconnectAlarmEnabled(pending)
                }

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
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastDoubleClickTime < DOUBLE_CLICK_COOLDOWN) {
                    Log.d(TAG, "双击冷却期内，忽略重复通知")
                    return
                }

                if (currentTime - lastButtonPressTime < DOUBLE_PRESS_TIMEOUT) {
                    lastButtonPressTime = 0
                    lastDoubleClickTime = currentTime

                    managerScope.launch {
                        try {
                            _bleEvents.emit(BleEvent.DoubleButtonPressed)
                            Log.d(TAG, "双击事件已 emit")
                        } catch (e: Exception) {
                            Log.e(TAG, "发送双击事件失败", e)
                        }
                    }
                    Log.d(TAG, "检测到双击事件")
                } else {
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
            deviceMacToConnect = macAddress

            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

            if (currentAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                send(BleConnectionState.Error("设备不支持蓝牙"))
                return@channelFlow
            }

            if (!currentAdapter.isEnabled) {
                Log.e(TAG, "蓝牙未开启")
                send(BleConnectionState.Error("请先开启蓝牙"))

                while (!currentAdapter.isEnabled) {
                    kotlinx.coroutines.delay(1000)
                }
                Log.d(TAG, "蓝牙已开启")
            }

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

            launch {
                try {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
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

    @SuppressLint("MissingPermission")
    fun connectDirectly(macAddress: String) {
        try {
            deviceMacToConnect = macAddress

            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

            if (currentAdapter == null) {
                Log.e(TAG, "设备不支持蓝牙")
                return
            }

            if (!currentAdapter.isEnabled) {
                Log.w(TAG, "蓝牙未开启，等待开启")
                return
            }

            bluetoothAdapter = currentAdapter

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
                bluetoothDevice?.connectGatt(context, false, bleCallback)
                Log.d(TAG, "GATT 直接连接已发起")
            } catch (e: Exception) {
                Log.e(TAG, "直接连接 GATT 失败", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectDirectly 方法异常", e)
        }
    }

    private var rssiPollingJob: kotlinx.coroutines.Job? = null

    private fun startRssiPolling() {
        rssiPollingJob?.cancel()

        rssiPollingJob = GlobalScope.launch(Dispatchers.Main) {
            try {
                while (true) {
                    kotlinx.coroutines.delay(1000)

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

    // 核心修复：配置防丢器断连报警行为（i-Searching 同款实现）
    @SuppressLint("MissingPermission")
    fun setDisconnectAlarmEnabled(enabled: Boolean) {
        try {
            disconnectAlarmCharacteristic?.let { characteristic ->
                bluetoothGatt?.writeCharacteristic(
                    characteristic.apply {
                        value = if (enabled) byteArrayOf(DISCONNECT_ALARM_ENABLE) else byteArrayOf(DISCONNECT_ALARM_DISABLE)
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                )
                pendingDisconnectAlarmState = null  // 写入成功，清除缓存
                Log.d(TAG, "已设置防丢器断连报警: $enabled")
            } ?: run {
                // 特征值未就绪，缓存状态，等服务发现后自动写入
                pendingDisconnectAlarmState = enabled
                Log.w(TAG, "断连报警特征值未找到，已缓存状态: $enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "配置断连报警失败", e)
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

    fun reconnectIfDisconnected() {
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        if (deviceMacToConnect != null && currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                cleanupGatt()
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

    private fun cleanupGatt() {
        bluetoothGatt?.let { oldGatt ->
            try {
                Log.d(TAG, "清理旧 GATT 资源")
                try { oldGatt.disconnect() } catch (e: Exception) {}
                try { oldGatt.close() } catch (e: Exception) {}
                try {
                    val mBluetoothGattField = oldGatt.javaClass.getDeclaredField("mBluetoothGatt")
                    mBluetoothGattField.isAccessible = true
                    val mBluetoothGatt = mBluetoothGattField.get(oldGatt)
                    if (mBluetoothGatt != null) {
                        val closeMethod = mBluetoothGatt.javaClass.getMethod("close")
                        closeMethod.invoke(mBluetoothGatt)
                    }
                } catch (e: Exception) {}
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
            disconnectAlarmCharacteristic = null
            pendingDisconnectAlarmState = null
        }
    }

    suspend fun emitAlarmEvent(reason: String) {
        try {
            _bleEvents.emit(BleEvent.AlarmTriggered(reason))
        } catch (e: Exception) {
            Log.e(TAG, "发送报警事件失败", e)
        }
    }
}