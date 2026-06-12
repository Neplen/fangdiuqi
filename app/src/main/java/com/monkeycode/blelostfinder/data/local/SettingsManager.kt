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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
        val DISCONNECT_ALARM_ENABLED = booleanPreferencesKey("disconnect_alarm_enabled")
        val WIFI_DND_ENABLED = booleanPreferencesKey("wifi_dnd_enabled")
        val SCHEDULE_DND_ENABLED = booleanPreferencesKey("schedule_dnd_enabled")
        val DND_START_TIME = stringPreferencesKey("dnd_start_time")
        val DND_END_TIME = stringPreferencesKey("dnd_end_time")
        val ALARM_RINGTONE_PATH = stringPreferencesKey("alarm_ringtone_path")
        val AUTO_START_ENABLED = booleanPreferencesKey("auto_start_enabled")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val GO_OUT_REMINDER_ENABLED = booleanPreferencesKey("go_out_reminder_enabled")
        val HOME_WIFI_SSID = stringPreferencesKey("home_wifi_ssid")
        val GO_OUT_RINGTONE_PATH = stringPreferencesKey("go_out_ringtone_path")
    }

    val deviceName: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_NAME] ?: ""
    }

    val deviceMac: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_MAC] ?: ""
    }

    val isDisconnectAlarmEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DISCONNECT_ALARM_ENABLED] ?: true
    }

    val isWifiDndEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIFI_DND_ENABLED] ?: false
    }

    val isScheduleDndEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SCHEDULE_DND_ENABLED] ?: false
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

    /**
     * 保存报警铃声路径，如果是本地文件则复制到APP私有目录防清理
     */
    suspend fun updateAlarmRingtonePath(path: String?) {
        val savedPath = copyRingtoneToPrivateDir(path, "alarm_ringtone")
        context.dataStore.edit { preferences ->
            if (savedPath != null) {
                preferences[ALARM_RINGTONE_PATH] = savedPath
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

    /**
     * 保存出门提醒铃声路径，如果是本地文件则复制到APP私有目录防清理
     */
    suspend fun updateGoOutRingtonePath(path: String?) {
        val savedPath = copyRingtoneToPrivateDir(path, "go_out_ringtone")
        context.dataStore.edit { preferences ->
            if (savedPath != null) {
                preferences[GO_OUT_RINGTONE_PATH] = savedPath
            } else {
                preferences.remove(GO_OUT_RINGTONE_PATH)
            }
        }
    }

    /**
     * 将铃声文件复制到APP私有目录，防止被清理软件删除
     * 系统铃声URI（content://media/...）直接保留，不会被清理
     * 只有本地文件路径（/storage/...）才需要复制
     */
    private fun copyRingtoneToPrivateDir(path: String?, fileName: String): String? {
        if (path == null) return null

        // 系统URI直接保留，不会被清理
        if (path.startsWith("content://") || path.startsWith("android.resource://")) {
            return path
        }

        // 已经是私有目录的，不重复复制
        if (path.contains(context.filesDir.absolutePath)) {
            return path
        }

        return try {
            val srcFile = File(path)
            if (!srcFile.exists()) {
                Log.w(TAG, "铃声源文件不存在: $path")
                return path // 回退到原路径，让调用方处理失败
            }

            val ringtoneDir = File(context.filesDir, "ringtones")
            if (!ringtoneDir.exists()) {
                ringtoneDir.mkdirs()
            }

            val destFile = File(ringtoneDir, "$fileName.${srcFile.extension}")
            FileInputStream(srcFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "铃声已复制到私有目录: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "复制铃声到私有目录失败", e)
            path // 回退到原路径
        }
    }

    companion object {
        private const val TAG = "SettingsManager"
    }
}