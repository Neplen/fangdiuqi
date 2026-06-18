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

        // 核心修复：与 i-Searching 保持一致，双击超时改为 1000ms
        // 原 2000ms 过长，增加误触概率
        private const val DOUBLE_PRESS_TIMEOUT = 1000L
        private var lastButtonPressTime = -1L
        private var lastDoubleClickTime = 0L
        private const val DOUBLE_CLICK_COOLDOWN = 800L

        // ===== 修改：删除硬编码的 I_DEVICE_NAME 和 I_DEVICE_MAC =====
        // 设备信息改为从 DataStore/Room 动态读取，支持更换防丢器

        val ALERT_SERVICE_UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
        val ALERT_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

        val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        val CUSTOM_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CUSTOM_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        val DISCONNECT_ALARM_CHARACTERISTIC_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

        const val ALERT_COMMAND = 0x01.toByte()
        const val ALERT_STOP_COMMAND = 0x00.toByte()
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

    private data class WriteRequest(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray
    )
    private val writeQueue = mutableListOf<WriteRequest>()
    private var isWriting = false

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

    // ===== 修改：新增已连接设备名称 Flow，供主页动态显示 =====
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mainHandler = Handler(Looper.getMainLooper())

    fun shouldEnableDeviceDisconnectAlarm(
        isWifiDndEnabled: Boolean,
        isWifiConnected: Boolean,
        isScheduleDndEnabled: Boolean,
        isInDndTimeRange: Boolean,
        isDisconnectAlarmEnabled: Boolean
    ): Boolean {
        if (isWifiDndEnabled && isWifiConnected) {
            Log.d(TAG, "决策结果：WiFi勿扰生效，防丢器断连报警禁用")
            return false
        }

        if (isScheduleDndEnabled && isInDndTimeRange) {
            Log.d(TAG, "决策结果：定时勿扰生效，防丢器断连报警禁用")
            return false
        }

        Log.d(TAG, "决策结果：由断连报警开关决定=$isDisconnectAlarmEnabled")
        return isDisconnectAlarmEnabled
    }

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

    private fun queueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        writeQueue.add(WriteRequest(characteristic, value))
        if (!isWriting) {
            processWriteQueue()
        }
    }

    private fun processWriteQueue() {
        if (writeQueue.isEmpty()) {
            isWriting = false
            return
        }
        val gatt = bluetoothGatt
        if (gatt == null || _connectionState.value !is BleConnectionState.Connected) {
            Log.w(TAG, "GATT未连接，清空写入队列(${writeQueue.size}个)")
            writeQueue.clear()
            isWriting = false
            return
        }
        isWriting = true
        val request = writeQueue.removeAt(0)
        request.characteristic.value = request.value
        request.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success = gatt.writeCharacteristic(request.characteristic)
        if (!success) {
            Log.e(TAG, "writeCharacteristic返回false，1ms后重试下一个")
            mainHandler.postDelayed({ processWriteQueue() }, 1)
        } else {
            Log.d(TAG, "已发送写入请求: ${request.characteristic.uuid}, 队列剩余: ${writeQueue.size}")
        }
    }

    private val bleCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: $status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connected
                    bluetoothGatt = gatt
                    // ===== 修改：连接成功后从 BluetoothDevice 获取名称 =====
                    _connectedDeviceName.value = gatt.device.name ?: "未知设备"
                    gatt.discoverServices()
                    startRssiPolling()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "设备已断开，准备自动重连...")
                    _connectionState.value = BleConnectionState.Disconnected

                    try {
                        gatt.close()
                        Log.d(TAG, "GATT已关闭，释放原生资源")
                    } catch (e: Exception) {
                        Log.e(TAG, "关闭GATT异常", e)
                    }

                    bluetoothGatt = null
                    alertCharacteristic = null
                    batteryCharacteristic = null
                    customCharacteristic = null
                    disconnectAlarmCharacteristic = null
                    pendingDisconnectAlarmState = null
                    writeQueue.clear()
                    isWriting = false

                    // 核心修复：断开连接时重置双击检测状态，防止跨会话干扰
                    // 避免重连后旧状态导致单击被误判为双击
                    lastButtonPressTime = -1L
                    lastDoubleClickTime = 0L

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

                disconnectAlarmCharacteristic = gatt.getService(CUSTOM_SERVICE_UUID)
                    ?.getCharacteristic(DISCONNECT_ALARM_CHARACTERISTIC_UUID)
                Log.d(TAG, "断连报警特征值: ${disconnectAlarmCharacteristic != null}")

                pendingDisconnectAlarmState?.let { pending ->
                    Log.d(TAG, "服务发现完成，写入缓存的断连报警配置: $pending")
                    setDisconnectAlarmEnabled(pending)
                }

                batteryCharacteristic?.let {
                    mainHandler.postDelayed({
                        try {
                            gatt.readCharacteristic(it)
                            Log.d(TAG, "延迟读取电池电量")
                        } catch (e: Exception) {
                            Log.e(TAG, "延迟读取电池失败", e)
                        }
                    }, 500)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            Log.d(TAG, "Characteristic read(new): ${characteristic.uuid}, status: $status, value: ${value.contentToString()}")
            handleCharacteristicRead(characteristic, value, status)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            Log.d(TAG, "Characteristic read(legacy): ${characteristic.uuid}, status: $status, value: ${value?.contentToString()}")
            if (value != null) {
                handleCharacteristicRead(characteristic, value, status)
            }
        }

        private fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                    if (value.isNotEmpty()) {
                        val battery = value[0].toInt() and 0xFF
                        _batteryLevel.value = battery
                        Log.d(TAG, "电池电量读取成功: $battery%")
                    } else {
                        Log.w(TAG, "电池电量读取返回空值")
                    }
                }
            } else {
                Log.e(TAG, "Characteristic read failed: ${characteristic.uuid}, status: $status")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            // 核心修复：添加 null 安全检查，防止旧版 API 中 characteristic.value 为 null 导致崩溃
            val value = characteristic.value ?: return
            onCharacteristicValueChanged(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onCharacteristicValueChanged(characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(TAG, "Characteristic write complete: ${characteristic?.uuid}, status: $status")
            processWriteQueue()
        }

        private fun onCharacteristicValueChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "Characteristic changed: ${characteristic.uuid}, value: ${value.contentToString()}")
            if (characteristic.uuid == CUSTOM_CHARACTERISTIC_UUID) {
                // ==================== 核心修复：与 i-Searching 保持一致 ====================
                // i-Searching 的 MyApplication.onCharacteristicChanged 中明确检查了：
                // if (value.length <= 0 || value[0] != 1) { return; }
                // 防丢器一次按键通常会发送两次通知：按下(value[0]==1)和释放(value[0]==0)
                // 如果不过滤，释放通知会被误判为第二次点击，导致"单击触发报警"
                if (value.isEmpty() || value[0] != 1.toByte()) {
                    Log.d(TAG, "忽略非按下通知: value=${value.contentToString()}")
                    return
                }

                val currentTime = System.currentTimeMillis()

                if (currentTime - lastDoubleClickTime < DOUBLE_CLICK_COOLDOWN) {
                    Log.d(TAG, "双击冷却期内，忽略重复通知")
                    return
                }

                // 核心修复：添加时间间隔下限(50ms)，防止BLE堆栈重复上报通知导致误判为双击
                // i-Searching 的 MediaPlayerTools.OnFDQClick 中也有类似下限判断(>10ms)
                if (lastButtonPressTime != -1L && currentTime - lastButtonPressTime < 50) {
                    Log.d(TAG, "通知间隔过短(${currentTime - lastButtonPressTime}ms)，忽略重复通知")
                    return
                }

                if (lastButtonPressTime == -1L || currentTime - lastButtonPressTime >= DOUBLE_PRESS_TIMEOUT) {
                    lastButtonPressTime = currentTime
                    Log.d(TAG, "检测到第一次点击，等待第二次...")
                } else {
                    lastButtonPressTime = -1L
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
        alertCharacteristic?.let { characteristic ->
            queueWrite(characteristic, byteArrayOf(ALERT_COMMAND))
            Log.d(TAG, "Alarm start 已加入写入队列")
        } ?: Log.w(TAG, "报警特征值未找到，无法启动报警")
    }

    @SuppressLint("MissingPermission")
    fun stopAlarm() {
        alertCharacteristic?.let { characteristic ->
            queueWrite(characteristic, byteArrayOf(ALERT_STOP_COMMAND))
            Log.d(TAG, "Alarm stop 已加入写入队列")
        } ?: Log.w(TAG, "报警特征值未找到，无法停止报警")
    }

    @SuppressLint("MissingPermission")
    fun setDisconnectAlarmEnabled(enabled: Boolean) {
        disconnectAlarmCharacteristic?.let { characteristic ->
            queueWrite(
                characteristic,
                if (enabled) byteArrayOf(DISCONNECT_ALARM_ENABLE) else byteArrayOf(DISCONNECT_ALARM_DISABLE)
            )
            pendingDisconnectAlarmState = null
            Log.d(TAG, "断连报警配置已加入写入队列: $enabled")
        } ?: run {
            pendingDisconnectAlarmState = enabled
            Log.w(TAG, "FFE2未就绪，已缓存断连报警状态: $enabled")
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

    @SuppressLint("MissingPermission")
    fun readBattery() {
        batteryCharacteristic?.let { characteristic ->
            bluetoothGatt?.let { gatt ->
                try {
                    gatt.readCharacteristic(characteristic)
                    Log.d(TAG, "主动读取电池电量")
                } catch (e: Exception) {
                    Log.e(TAG, "主动读取电池失败", e)
                }
            }
        } ?: Log.w(TAG, "电池特征值未找到，无法读取")
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

    // ===== 修改：reconnectIfDisconnected 不再使用硬编码 MAC，改为从 DataStore 读取 =====
    // 由 BleMonitorService 或 HomeViewModel 传入实际保存的 MAC
    fun reconnectIfDisconnected(macAddress: String? = deviceMacToConnect) {
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        if (macAddress != null && currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                cleanupGatt()
                connectDirectly(macAddress)
                Log.d(TAG, "蓝牙重连已发起：$macAddress")
            } catch (e: Exception) {
                Log.e(TAG, "蓝牙重连失败", e)
            }
        } else {
            if (macAddress == null) {
                Log.d(TAG, "没有保存的设备地址，跳过重连（请进入扫描页绑定设备）")
            } else if (currentAdapter == null) {
                Log.w(TAG, "蓝牙适配器不可用，等待初始化")
            } else {
                Log.w(TAG, "蓝牙未开启，等待用户手动开启")
            }
        }
    }

    // ===== 新增：设置当前要连接/重连的 MAC 地址（由扫描页或 Service 调用） =====
    fun setDeviceMacToConnect(mac: String?) {
        deviceMacToConnect = mac
        Log.d(TAG, "设置目标设备 MAC: $mac")
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
            writeQueue.clear()
            isWriting = false
        }
    }

    // ===== 修复BUG3：强制重置BLE状态，用于HomeFragment连接按钮点击时 =====
    fun resetBleState() {
        Log.d(TAG, "强制重置BLE状态...")

        // 1. 断开并清理现有GATT
        cleanupGatt()

        // 2. 重置连接状态
        _connectionState.value = BleConnectionState.Disconnected
        _connectedDeviceName.value = null
        _rssi.value = -100
        _batteryLevel.value = -1

        // 3. 重置双击检测状态
        lastButtonPressTime = -1L
        lastDoubleClickTime = 0L

        // 4. 取消RSSI轮询
        rssiPollingJob?.cancel()
        rssiPollingJob = null

        // 5. 重新初始化蓝牙适配器
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            Log.d(TAG, "BLE状态重置完成，蓝牙适配器=${bluetoothAdapter != null}")
        } catch (e: Exception) {
            Log.e(TAG, "BLE状态重置时初始化适配器失败", e)
            bluetoothAdapter = null
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