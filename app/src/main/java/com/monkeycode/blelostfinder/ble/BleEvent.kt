package com.monkeycode.blelostfinder.ble

sealed class BleEvent {
    object RssiUpdate : BleEvent()
    object BatteryLevelUpdate : BleEvent()
    object ButtonPressed : BleEvent()
    object DoubleButtonPressed : BleEvent()  // 双击事件
    data class AlarmTriggered(val reason: String) : BleEvent()
    data class LocationRecorded(val latitude: Double, val longitude: Double) : BleEvent()
}
