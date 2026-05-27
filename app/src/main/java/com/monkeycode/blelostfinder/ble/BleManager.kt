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

        // 与 i-Searching 完全一致
        private const val DOUBLE_PRESS_TIMEOUT = 1000L
        private const val MIN_DOUBLE_PRESS_INTERVAL = 10L
        private const val DOUBLE_CLICK_COOLDOWN = 800L

        const val I_DEVICE_NAME = "iTAG"
        const val I_DEVICE_MAC = "FF:FF:11:8C:4E:3B"

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

    // BLE写入队列
    private data class WriteRequest(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray
    )
    private val writeQueue = mutableListOf<WriteRequest>()
    private var isWriting = false

    // 核心修复：写入超时机制，防止队列永久卡住
    private var writeTimeoutRunnable: Runnable? = null
    private const val WRITE_TIMEOUT_MS = 5000L

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

    // 双击检测状态
    private var lastClickTime = 0L
    private var lastDoubleClickTime = 0L
    private var clickTimeoutRunnable: Runnable? = null

    fun shouldEnableDeviceDisconnectAlarm(
        isWifiDndEnabled: Boolean,
        isWifiConnected: Boolean,
        isScheduleDndEnabled: Boolean,
        isInDndTimeRange: Boolean,
        isDisconnectAlarmEnabled: Boolean
    ): Boolean {
        if (isWifiDndEnabled && isWifiConnected) return false
        if (isScheduleDndEnabled && isInDndTimeRange) return false
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
            isWifiDndEnabled, isWifiConnected, isScheduleDndEnabled,
            isInDndTimeRange, isDisconnectAlarmEnabled
        )
        setDisconnectAlarmEnabled(shouldEnable)
    }

    // ==================== 核心修复：带超时的写入队列 ====================
    private fun queueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        writeQueue.add(WriteRequest(characteristic, value))
        if (!isWriting) {
            processWriteQueue()
        }
    }

    private fun processWriteQueue() {
        if (writeQueue.isEmpty()) {
            isWriting = false
            cancelWriteTimeout()
            return
        }
        val gatt = bluetoothGatt
        if (gatt == null || _connectionState.value !is BleConnectionState.Connected) {
            Log.w(TAG, "GATT未连接，清空写入队列(${writeQueue.size}个)")
            writeQueue.clear()
            isWriting = false
            cancelWriteTimeout()
            return
        }
        isWriting = true
        val request = writeQueue.removeAt(0)
        request.characteristic.value = request.value
        request.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success = gatt.writeCharacteristic(request.characteristic)
        if (!success) {
            Log.e(TAG, "writeCharacteristic返回false，100ms后重试")
            mainHandler.postDelayed({ processWriteQueue() }, 100)
        } else {
            Log.d(TAG, "已发送写入请求: ${request.characteristic.uuid}, 队列剩余: ${writeQueue.size}")
            scheduleWriteTimeout()
        }
    }

    private fun scheduleWriteTimeout() {
        cancelWriteTimeout()
        val runnable = Runnable {
            if (isWriting) {
                Log.w(TAG, "写入超时(${WRITE_TIMEOUT_MS}ms)，强制重置队列状态")
                isWriting = false
                if (writeQueue.isNotEmpty()) {
                    processWriteQueue()
                }
            }
        }
        writeTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, WRITE_TIMEOUT_MS)
    }

    private fun cancelWriteTimeout() {
        writeTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            writeTimeoutRunnable = null
        }
    }
    // =================================================================

    private val bleCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: $status, newState: $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    resetClickState()
                    _connectionState.value = BleConnectionState.Connected
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                    startRssiPolling()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    resetClickState()
                    cancelWriteTimeout()
                    _connectionState.value = BleConnectionState.Disconnected
                    bluetoothGatt = null
                    alertCharacteristic = null
                    batteryCharacteristic = null
                    customCharacteristic = null
                    disconnectAlarmCharacteristic = null
                    pendingDisconnectAlarmState = null
                    writeQueue.clear()
                    isWriting = false
                    rssiPollingJob?.cancel()
                    rssiPollingJob = null
                    managerScope.launch {
                        try { _bleEvents.emit(BleEvent.Disconnected) } catch (e: Exception) {}
                    }
                    deviceMacToConnect?.let { mac ->
                        mainHandler.postDelayed({
                            try { scheduleReconnect(mac) } catch (e: Exception) {}
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
                    setDisconnectAlarmEnabled(pending)
                }

                batteryCharacteristic?.let {
                    mainHandler.postDelayed({
                        try { gatt.readCharacteristic(it) } catch (e: Exception) {}
                    }, 500)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleCharacteristicRead(characteristic, value, status)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (value != null) handleCharacteristicRead(characteristic, value, status)
        }

        private fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                if (value.isNotEmpty()) {
                    _batteryLevel.value = value[0].toInt() and 0xFF
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

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            Log.d(TAG, "Characteristic write complete: ${characteristic?.uuid}, status: $status")
            cancelWriteTimeout()
            processWriteQueue()
        }

        // ==================== 核心修复：i-Searching 同款双击检测 ====================
        private fun onCharacteristicValueChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == CUSTOM_CHARACTERISTIC_UUID) {
                if (value.isEmpty() || value[0] != 1.toByte()) return

                val currentTime = System.currentTimeMillis()

                // 冷却期
                if (currentTime - lastDoubleClickTime < DOUBLE_CLICK_COOLDOWN) {
                    Log.d(TAG, "冷却期内，忽略")
                    return
                }

                val lastTime = lastClickTime
                if (lastTime == 0L) {
                    // 第一次点击
                    lastClickTime = currentTime
                    scheduleClickTimeout()
                    Log.d(TAG, "第一次点击")
                    return
                }

                val interval = currentTime - lastTime
                if (interval < DOUBLE_PRESS_TIMEOUT && interval > MIN_DOUBLE_PRESS_INTERVAL) {
                    // 有效双击
                    cancelClickTimeout()
                    lastClickTime = 0L
                    lastDoubleClickTime = currentTime
                    managerScope.launch {
                        try { _bleEvents.emit(BleEvent.DoubleButtonPressed) } catch (e: Exception) {}
                    }
                    Log.d(TAG, "双击事件，间隔${interval}ms")
                } else {
                    // 太长或太短，视为新的第一次
                    cancelClickTimeout()
                    lastClickTime = currentTime
                    scheduleClickTimeout()
                    Log.d(TAG, "间隔${interval}ms，刷新为第一次")
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssi
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {}
    }

    // 单击超时清零
    private fun scheduleClickTimeout() {
        cancelClickTimeout()
        val runnable = Runnable {
            if (lastClickTime != 0L) {
                Log.d(TAG, "单击超时，清零")
                lastClickTime = 0L
            }
        }
        clickTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, DOUBLE_PRESS_TIMEOUT + 100)
    }

    private fun cancelClickTimeout() {
        clickTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            clickTimeoutRunnable = null
        }
    }

    private fun resetClickState() {
        cancelClickTimeout()
        lastClickTime = 0L
        lastDoubleClickTime = 0L
    }

    fun initialize(): Boolean {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
        } catch (e: Exception) {
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Flow<BleConnectionState> = channelFlow {
        try {
            deviceMacToConnect = macAddress
            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (currentAdapter == null) {
                send(BleConnectionState.Error("设备不支持蓝牙"))
                return@channelFlow
            }
            if (!currentAdapter.isEnabled) {
                send(BleConnectionState.Error("请先开启蓝牙"))
                while (!currentAdapter.isEnabled) { kotlinx.coroutines.delay(1000) }
            }
            bluetoothAdapter = currentAdapter
            _connectionState.value = BleConnectionState.Connecting
            send(BleConnectionState.Connecting)
            bluetoothDevice = try {
                currentAdapter.getRemoteDevice(macAddress)
            } catch (e: Exception) {
                send(BleConnectionState.Error("无效地址"))
                return@channelFlow
            }
            bluetoothDevice?.connectGatt(context, false, bleCallback)
            launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) break
                    bluetoothGatt?.readRemoteRssi()
                }
            }
            awaitClose {}
        } catch (e: Exception) {
            send(BleConnectionState.Error("连接异常"))
            close()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDirectly(macAddress: String) {
        try {
            deviceMacToConnect = macAddress
            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (currentAdapter == null || !currentAdapter.isEnabled) return
            bluetoothAdapter = currentAdapter
            cleanupGatt()
            _connectionState.value = BleConnectionState.Connecting
            bluetoothDevice = try { currentAdapter.getRemoteDevice(macAddress) } catch (e: Exception) { return }
            bluetoothDevice?.connectGatt(context, false, bleCallback)
        } catch (e: Exception) {}
    }

    private var rssiPollingJob: kotlinx.coroutines.Job? = null

    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = GlobalScope.launch(Dispatchers.Main) {
            try {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) break
                    bluetoothGatt?.readRemoteRssi()
                }
            } catch (e: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.let { gatt ->
                try { _connectionState.value = BleConnectionState.Disconnecting } catch (e: Exception) {}
                try { gatt.disconnect() } catch (e: Exception) {}
                try { gatt.close() } catch (e: Exception) {}
                bluetoothGatt = null
            }
        } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun startAlarm() {
        alertCharacteristic?.let { characteristic ->
            queueWrite(characteristic, byteArrayOf(ALERT_COMMAND))
            Log.d(TAG, "Alarm start 入队")
        } ?: Log.w(TAG, "报警特征值未找到")
    }

    @SuppressLint("MissingPermission")
    fun stopAlarm() {
        alertCharacteristic?.let { characteristic ->
            queueWrite(characteristic, byteArrayOf(ALERT_STOP_COMMAND))
            Log.d(TAG, "Alarm stop 入队")
        } ?: Log.w(TAG, "报警特征值未找到")
    }

    @SuppressLint("MissingPermission")
    fun setDisconnectAlarmEnabled(enabled: Boolean) {
        disconnectAlarmCharacteristic?.let { characteristic ->
            queueWrite(
                characteristic,
                if (enabled) byteArrayOf(DISCONNECT_ALARM_ENABLE) else byteArrayOf(DISCONNECT_ALARM_DISABLE)
            )
            pendingDisconnectAlarmState = null
            Log.d(TAG, "FFE2 入队: $enabled")
        } ?: run {
            pendingDisconnectAlarmState = enabled
            Log.w(TAG, "FFE2未就绪，缓存: $enabled")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readBatteryLevel(): Int? {
        return try {
            withContext(Dispatchers.IO) {
                batteryCharacteristic?.let { characteristic ->
                    try {
                        bluetoothGatt?.readCharacteristic(characteristic)
                        kotlinx.coroutines.delay(500)
                        _batteryLevel.value
                    } catch (e: Exception) { null }
                }
            }
        } catch (e: Exception) { null }
    }

    @SuppressLint("MissingPermission")
    fun readBattery() {
        batteryCharacteristic?.let { characteristic ->
            bluetoothGatt?.let { gatt ->
                try { gatt.readCharacteristic(characteristic) } catch (e: Exception) {}
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private fun scheduleReconnect(mac: String) {
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                currentAdapter.getRemoteDevice(mac).connectGatt(context, false, bleCallback)
            } catch (e: Exception) {}
        }
    }

    fun reconnectIfDisconnected() {
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (deviceMacToConnect != null && currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                cleanupGatt()
                connectDirectly(deviceMacToConnect!!)
            } catch (e: Exception) {}
        }
    }

    private fun cleanupGatt() {
        bluetoothGatt?.let { oldGatt ->
            try {
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
            } catch (e: Exception) {}
            bluetoothGatt = null
            alertCharacteristic = null
            batteryCharacteristic = null
            customCharacteristic = null
            disconnectAlarmCharacteristic = null
            pendingDisconnectAlarmState = null
            writeQueue.clear()
            isWriting = false
            cancelWriteTimeout()
        }
    }

    suspend fun emitAlarmEvent(reason: String) {
        try { _bleEvents.emit(BleEvent.AlarmTriggered(reason)) } catch (e: Exception) {}
    }
}