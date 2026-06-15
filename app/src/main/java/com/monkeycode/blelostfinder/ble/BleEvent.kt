package com.monkeycode.blelostfinder.ble

sealed class BleEvent {
    object RssiUpdate : BleEvent()
    object BatteryLevelUpdate : BleEvent()
    object ButtonPressed : BleEvent()
    object DoubleButtonPressed : BleEvent()
    object Disconnected : BleEvent()
    // 核心修复：增加 alarmType 字段，用于区分断连报警和出门提醒
    data class AlarmTriggered(val reason: String, val alarmType: String = "disconnect") : BleEvent()
    data class LocationRecorded(val latitude: Double, val longitude: Double) : BleEvent()
}