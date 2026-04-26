package com.monkeycode.blelostfinder.ble

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Connecting : BleConnectionState()
    object Connected : BleConnectionState()
    object Disconnecting : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
