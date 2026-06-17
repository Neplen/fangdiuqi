package com.monkeycode.blelostfinder.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DEVICE_MAC = stringPreferencesKey("device_mac")
        // 核心修复：删除 RSSI_THRESHOLD，新增断连报警开关
        val DISCONNECT_ALARM_ENABLED = booleanPreferencesKey("disconnect_alarm_enabled")
        val WIFI_DND_ENABLED = booleanPreferencesKey("wifi_dnd_enabled")
        val SCHEDULE_DND_ENABLED = booleanPreferencesKey("schedule_dnd_enabled")
        val DND_START_TIME = stringPreferencesKey("dnd_start_time")
        val DND_END_TIME = stringPreferencesKey("dnd_end_time")
        val ALARM_RINGTONE_PATH = stringPreferencesKey("alarm_ringtone_path")
        val AUTO_START_ENABLED = booleanPreferencesKey("auto_start_enabled")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        // 出门提醒功能配置
        val GO_OUT_REMINDER_ENABLED = booleanPreferencesKey("go_out_reminder_enabled")
        val HOME_WIFI_SSID = stringPreferencesKey("home_wifi_ssid")
        val GO_OUT_RINGTONE_PATH = stringPreferencesKey("go_out_ringtone_path")
    }

    // ===== 修改：deviceName 和 deviceMac 默认值改为空字符串 =====
    // 空字符串表示未绑定设备，首次安装后不会自动连接
    val deviceName: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_NAME] ?: ""
    }

    val deviceMac: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_MAC] ?: ""
    }

    // 核心修复：断连自动报警开关，默认开启
    val isDisconnectAlarmEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DISCONNECT_ALARM_ENABLED] ?: true
    }

    val isWifiDndEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIFI_DND_ENABLED] ?: false  // 核心修复：默认关闭，避免首次安装就生效
    }

    val isScheduleDndEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SCHEDULE_DND_ENABLED] ?: false  // 核心修复：默认关闭
    }

    val dndStartTime: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DND_START_TIME] ?: "21:00"
    }

    val dndEndTime: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DND_END_TIME] ?: "08:00"
    }

    val alarmRingtonePath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ALARM_RINGTONE_PATH]
    }

    val isMonitoringEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MONITORING_ENABLED] ?: false
    }

    // 出门提醒功能配置
    val isGoOutReminderEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GO_OUT_REMINDER_ENABLED] ?: false
    }

    val homeWifiSsid: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[HOME_WIFI_SSID] ?: ""
    }

    val goOutRingtonePath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[GO_OUT_RINGTONE_PATH]
    }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[DEVICE_NAME] = name
        }
    }

    suspend fun updateDeviceMac(mac: String) {
        context.dataStore.edit { preferences ->
            preferences[DEVICE_MAC] = mac
        }
    }

    // 核心修复：新增断连报警开关更新
    suspend fun updateDisconnectAlarmEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DISCONNECT_ALARM_ENABLED] = enabled
        }
    }

    suspend fun updateWifiDndEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WIFI_DND_ENABLED] = enabled
        }
    }

    suspend fun updateScheduleDndEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SCHEDULE_DND_ENABLED] = enabled
        }
    }

    suspend fun updateDndTime(startTime: String, endTime: String) {
        context.dataStore.edit { preferences ->
            preferences[DND_START_TIME] = startTime
            preferences[DND_END_TIME] = endTime
        }
    }

    suspend fun updateAlarmRingtonePath(path: String?) {
        context.dataStore.edit { preferences ->
            if (path != null) {
                preferences[ALARM_RINGTONE_PATH] = path
            } else {
                preferences.remove(ALARM_RINGTONE_PATH)
            }
        }
    }

    suspend fun updateMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MONITORING_ENABLED] = enabled
        }
    }

    // 出门提醒功能配置
    suspend fun updateGoOutReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GO_OUT_REMINDER_ENABLED] = enabled
        }
    }

    suspend fun saveHomeWifi(ssid: String) {
        context.dataStore.edit { preferences ->
            preferences[HOME_WIFI_SSID] = ssid
        }
    }

    suspend fun updateGoOutRingtonePath(path: String?) {
        context.dataStore.edit { preferences ->
            if (path != null) {
                preferences[GO_OUT_RINGTONE_PATH] = path
            } else {
                preferences.remove(GO_OUT_RINGTONE_PATH)
            }
        }
    }
}