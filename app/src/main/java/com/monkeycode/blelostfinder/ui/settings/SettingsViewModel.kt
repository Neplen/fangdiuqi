package com.monkeycode.blelostfinder.ui.settings

import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkeycode.blelostfinder.data.local.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsManager: SettingsManager
) : AndroidViewModel(application) {
    
    val deviceName: Flow<String> = settingsManager.deviceName
    val rssiThreshold: Flow<Int> = settingsManager.rssiThreshold
    val alarmDelay: Flow<Int> = settingsManager.alarmDelay
    val isWifiDndEnabled: Flow<Boolean> = settingsManager.isWifiDndEnabled
    val isScheduleDndEnabled: Flow<Boolean> = settingsManager.isScheduleDndEnabled
    val dndStartTime: Flow<String> = settingsManager.dndStartTime
    val dndEndTime: Flow<String> = settingsManager.dndEndTime
    
    fun updateRssiThreshold(threshold: Int) {
        viewModelScope.launch {
            settingsManager.updateRssiThreshold(threshold)
        }
    }
    
    fun updateAlarmDelay(seconds: Int) {
        viewModelScope.launch {
            settingsManager.updateAlarmDelay(seconds)
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
    
    fun selectRingtone(type: Int) {
        viewModelScope.launch {
            val path = when (type) {
                0 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.toString()  // 系统默认铃声
                1 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString()    // 警报声
                2 -> getCustomRecordingPath()  // 自定义录音
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
        // 正确路径：/storage/emulated/0/Android/data/com.monkeycode.blelostfinder/files/Music/alarms/alarm_sound.m4a
        val baseDir = context.getExternalFilesDir(null)
        if (baseDir == null) return null
        
        val audioDir = File(baseDir, "Music/alarms")
        val file = File(audioDir, "alarm_sound.m4a")
        return if (file.exists()) file.absolutePath else null
    }
}
