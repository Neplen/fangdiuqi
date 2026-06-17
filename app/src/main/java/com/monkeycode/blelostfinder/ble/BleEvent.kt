package com.monkeycode.blelostfinder.ble

sealed class BleEvent {
    object RssiUpdate : BleEvent()
    object BatteryLevelUpdate : BleEvent()
    object ButtonPressed : BleEvent()
    object DoubleButtonPressed : BleEvent()
    object Disconnected : BleEvent()
    data class AlarmTriggered(val reason: String) : BleEvent()
    data class LocationRecorded(val latitude: Double, val longitude: Double) : BleEvent()
}
