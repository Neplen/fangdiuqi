package com.monkeycode.blelostfinder

import android.app.Application
import android.util.Log
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.ui.settings.AlarmSoundManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BleLostFinderApplication : Application() {
    
    @Inject
    lateinit var alarmSoundManager: AlarmSoundManager

    @Inject
    lateinit var bleManager: BleManager
    
    override fun onCreate() {
        super.onCreate()
        
        try {
            // 初始化录音目录
            alarmSoundManager.initializeRecordingDir()
            // APP启动时主动连接设备（关键！）
            bleManager.initConnectionOnAppStart()
            Log.d(TAG, "APP 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "APP 初始化失败", e)
        }
    }
    
    companion object {
        private const val TAG = "BleLostFinderApp"
    }
}