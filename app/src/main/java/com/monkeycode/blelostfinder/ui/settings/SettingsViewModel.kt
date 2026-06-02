package com.monkeycode.blelostfinder.ui.settings

import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkeycode.blelostfinder.data.local.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsManager: SettingsManager
) : AndroidViewModel(application) {

    // 核心修复：删除 RSSI 阈值，新增断连报警开关
    private val _isDisconnectAlarmEnabled = MutableStateFlow(true)
    val isDisconnectAlarmEnabled: StateFlow<Boolean> = _isDisconnectAlarmEnabled.asStateFlow()

    // 核心修复：移除 deviceName Flow（设置页不再显示设备名称）
    val isWifiDndEnabled: Flow<Boolean> = settingsManager.isWifiDndEnabled
    val isScheduleDndEnabled: Flow<Boolean> = settingsManager.isScheduleDndEnabled
    val dndStartTime: Flow<String> = settingsManager.dndStartTime
    val dndEndTime: Flow<String> = settingsManager.dndEndTime

    init {
        viewModelScope.launch {
            settingsManager.isDisconnectAlarmEnabled.collect { _isDisconnectAlarmEnabled.value = it }
        }
    }

    // 核心修复：新增断连报警开关更新
    fun updateDisconnectAlarmEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateDisconnectAlarmEnabled(enabled)
        }
    }

    fun updateWifiDndEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateWifiDndEnabled(enabled)
        }
    }

    fun updateScheduleDndEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.updateScheduleDndEnabled(enabled)
        }
    }

    fun updateDndTime(startTime: String, endTime: String) {
        viewModelScope.launch {
            settingsManager.updateDndTime(startTime, endTime)
        }
    }

    fun saveRingtoneUri(uriString: String) {
        viewModelScope.launch {
            settingsManager.updateAlarmRingtonePath(uriString.ifEmpty { null })
        }
    }

    fun selectRingtone(type: Int) {
        viewModelScope.launch {
            val path = when (type) {
                0 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.toString()
                1 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString()
                2 -> getCustomRecordingPath()
                else -> null
            }
            settingsManager.updateAlarmRingtonePath(path)
        }
    }

    fun saveRecordingComplete() {
        viewModelScope.launch {
            val path = getCustomRecordingPath()
            settingsManager.updateAlarmRingtonePath(path)
        }
    }

    private fun getCustomRecordingPath(): String? {
        val context = getApplication<Application>().applicationContext
        val baseDir = context.getExternalFilesDir(null)
        if (baseDir == null) return null

        val audioDir = File(baseDir, "Music/alarms")
        val file = File(audioDir, "alarm_sound.m4a")
        return if (file.exists()) file.absolutePath else null
    }
}