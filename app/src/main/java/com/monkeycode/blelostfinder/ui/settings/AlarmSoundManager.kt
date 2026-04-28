package com.monkeycode.blelostfinder.ui.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmSoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AlarmSoundManager"
        private const val RECORDING_FILE_NAME = "alarm_sound.m4a"
    }
    
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingFilePath: String = getRecordingFilePath()
    
    private val contextApp get() = context.applicationContext
    
    fun initializeRecordingDir() {
        try {
            // 完整路径：/storage/emulated/0/Android/data/com.monkeycode.blelostfinder/files/Music/alarms/
            val baseDir = contextApp.getExternalFilesDir(null)
            if (baseDir == null) {
                Log.e(TAG, "无法获取外部文件目录")
                return
            }
            
            val audioDir = File(baseDir, "Music/alarms")
            if (!audioDir.exists()) {
                val created = audioDir.mkdirs()
                Log.d(TAG, "创建录音目录：${audioDir.absolutePath}, 成功：$created")
            } else {
                Log.d(TAG, "录音目录已存在：${audioDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建录音目录失败", e)
        }
    }
    
    fun getRecordingFilePath(): String {
        // 确保目录存在
        initializeRecordingDir()
        
        // 完整路径：/storage/emulated/0/Android/data/com.monkeycode.blelostfinder/files/Music/alarms/alarm_sound.m4a
        val baseDir = contextApp.getExternalFilesDir(null)
        if (baseDir == null) {
            Log.e(TAG, "无法获取外部文件目录，使用默认路径")
            val audioDir = File(contextApp.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "alarms")
            val file = File(audioDir, RECORDING_FILE_NAME)
            return file.absolutePath
        }
        
        val audioDir = File(baseDir, "Music/alarms")
        val file = File(audioDir, RECORDING_FILE_NAME)
        Log.d(TAG, "录音文件路径：${file.absolutePath}")
        return file.absolutePath
    }
    
    @Suppress("DEPRECATION")
    fun startRecording(): Boolean {
        if (isRecording) return false
        
        val filePath = getRecordingFilePath()
        
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(contextApp)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(filePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                
                try {
                    prepare()
                    start()
                    isRecording = true
                    Log.d(TAG, "Recording started: $filePath")
                    return true
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to start recording", e)
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaRecorder", e)
            return false
        }
    }
    
    fun stopRecording(): String? {
        if (!isRecording) return null
        
        val filePath = getRecordingFilePath()
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Log.d(TAG, "Recording stopped: $filePath")
            return filePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            return null
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }
    
    fun isRecording(): Boolean = isRecording
    
    fun playAlarm(filePath: String?) {
        stopPlaying()
        
        val audioPath = filePath ?: getRecordingFilePath()
        val file = File(audioPath)
        
        if (!file.exists()) {
            Log.d(TAG, "Custom alarm file not found, using default")
            playDefaultAlarm()
            return
        }
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioPath)
                // 使用响铃音量流，而不是媒体音量
                setAudioStreamType(AudioManager.STREAM_RING)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                prepare()
                isLooping = true
                start()
                Log.d(TAG, "Playing custom alarm: $audioPath")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to play custom alarm", e)
            playDefaultAlarm()
        }
    }
    
    // 添加预览播放方法
    fun previewRingtone(uri: android.net.Uri?) {
        stopPlaying()
        
        if (uri == null) {
            playDefaultAlarm()
            return
        }
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(contextApp, uri)
                setAudioStreamType(AudioManager.STREAM_RING)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                prepare()
                isLooping = false  // 预览只播放一次
                start()
            }
            Log.d(TAG, "Previewing ringtone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preview ringtone", e)
        }
    }
    
    // 停止预览并回放默认铃声（如果正在播放）
    fun stopPreview() {
        stopPlaying()
    }
    
    private fun playDefaultAlarm() {
        try {
            val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                Log.e(TAG, "默认报警铃能为空")
                return
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(contextApp, alarmUri)
                setAudioStreamType(AudioManager.STREAM_RING)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                prepare()
                isLooping = true
                start()
            }
            Log.d(TAG, "Playing default alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play default alarm", e)
        }
    }
    
    fun stopPlaying() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        } finally {
            mediaPlayer = null
        }
    }
    
    fun cleanup() {
        stopPlaying()
        stopRecording()
    }
}
