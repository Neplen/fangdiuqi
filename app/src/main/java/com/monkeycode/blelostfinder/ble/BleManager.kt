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
                // Button pressed event
                kotlinx.coroutines.GlobalScope.launch {
                    _bleEvents.emit(BleEvent.ButtonPressed)
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
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        return bluetoothAdapter != null && bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Flow<BleConnectionState> = channelFlow {
        if (bluetoothAdapter == null) {
            send(BleConnectionState.Error("Bluetooth adapter not initialized"))
            close()
            return@channelFlow
        }

        _connectionState.value = BleConnectionState.Connecting
        send(BleConnectionState.Connecting)

        bluetoothDevice = bluetoothAdapter?.getRemoteDevice(macAddress)
        bluetoothDevice?.connectGatt(context, false, bleCallback)
        
        // Start RSSI polling
        launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                bluetoothGatt?.readRemoteRssi()
            }
        }
        
        awaitClose {
            disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            _connectionState.value = BleConnectionState.Disconnecting
            gatt.disconnect()
            gatt.close()
            bluetoothGatt = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startAlarm() {
        alertCharacteristic?.let { characteristic ->
            bluetoothGatt?.writeCharacteristic(
                characteristic.apply {
                    value = byteArrayOf(ALERT_COMMAND)
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            )
            Log.d(TAG, "Alarm started")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAlarm() {
        alertCharacteristic?.let { characteristic ->
            bluetoothGatt?.writeCharacteristic(
                characteristic.apply {
                    value = byteArrayOf(ALERT_STOP_COMMAND)
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            )
            Log.d(TAG, "Alarm stopped")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readBatteryLevel(): Int? {
        return withContext(Dispatchers.IO) {
            batteryCharacteristic?.let { characteristic ->
                bluetoothGatt?.readCharacteristic(characteristic)
                kotlinx.coroutines.delay(500)
                _batteryLevel.value
            }
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
}
