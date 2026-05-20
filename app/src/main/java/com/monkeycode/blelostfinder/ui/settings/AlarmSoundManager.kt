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
        initializeRecordingDir()

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

    // 核心修复：加 synchronized 防止多协程竞争 mediaPlayer 实例导致铃声叠加
    fun playAlarm(ringtonePath: String?) {
        synchronized(this) {
            stopPlayingInternal()

            if (ringtonePath.isNullOrEmpty()) {
                Log.d(TAG, "No custom ringtone path, playing default alarm")
                playDefaultAlarmInternal()
                return
            }

            if (ringtonePath.startsWith("android.resource://") || 
                ringtonePath.startsWith("content://")) {
                val uri = try {
                    android.net.Uri.parse(ringtonePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse ringtone URI", e)
                    playDefaultAlarmInternal()
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
                        isLooping = true
                        start()
                        Log.d(TAG, "Playing ringtone from URI: $ringtonePath")
                    }
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to play ringtone from URI", e)
                    playDefaultAlarmInternal()
                    return
                }
            }

            val file = File(ringtonePath)

            if (!file.exists()) {
                Log.d(TAG, "Custom alarm file not found, using default")
                playDefaultAlarmInternal()
                return
            }

            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(ringtonePath)
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
                    Log.d(TAG, "Playing custom alarm: $ringtonePath")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to play custom alarm", e)
                playDefaultAlarmInternal()
            }
        }
    }

    fun previewRingtone(uri: android.net.Uri?) {
        synchronized(this) {
            stopPlayingInternal()

            if (uri == null) {
                playDefaultAlarmInternal()
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
                    isLooping = false
                    start()
                }
                Log.d(TAG, "Previewing ringtone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preview ringtone", e)
            }
        }
    }

    fun stopPreview() {
        stopPlaying()
    }

    private fun playDefaultAlarmInternal() {
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

    // 核心修复：提供 isPlaying() 方法，供 ViewModel 启动时检测是否在响铃
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    // 对外公开的 stopPlaying，也加锁
    fun stopPlaying() {
        synchronized(this) {
            stopPlayingInternal()
        }
    }

    private fun stopPlayingInternal() {
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