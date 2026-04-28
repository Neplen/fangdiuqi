package com.monkeycode.blelostfinder.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.databinding.FragmentSettingsBinding
import com.monkeycode.blelostfinder.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

@AndroidEntryPoint
class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SettingsViewModel by viewModels()
    
    private var isRecording = false
    private var recordingStartTime: Long = 0
    
    companion object {
        private const val TAG = "SettingsFragment"
    }
    
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            toggleRecording()
        } else {
            Toast.makeText(requireContext(), "需要录音权限", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupObservers()
        setupClickListeners()
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.deviceName.collect { name ->
                        binding.etDeviceName.setText(name)
                    }
                }
                
                launch {
                    viewModel.rssiThreshold.collect { threshold ->
                        binding.tvRssiThreshold.text = threshold.toString()
                        binding.sliderRssi.value = threshold.toFloat()
                    }
                }
                
                launch {
                    viewModel.alarmDelay.collect { delay ->
                        binding.tvAlarmDelay.text = delay.toString()
                        binding.sliderAlarmDelay.value = delay.toFloat()
                    }
                }
                
                launch {
                    viewModel.isWifiDndEnabled.collect { enabled ->
                        binding.switchWifiDnd.isChecked = enabled
                    }
                }
                
                launch {
                    viewModel.isScheduleDndEnabled.collect { enabled ->
                        binding.switchScheduleDnd.isChecked = enabled
                    }
                }
                
                launch {
                    viewModel.dndStartTime.collect { time ->
                        binding.tvStartTime.text = time
                    }
                }
                
                launch {
                    viewModel.dndEndTime.collect { time ->
                        binding.tvEndTime.text = time
                    }
                }
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.sliderRssi.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvRssiThreshold.text = value.toInt().toString()
                viewModel.updateRssiThreshold(value.toInt())
            }
        }
        
        binding.sliderAlarmDelay.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvAlarmDelay.text = value.toInt().toString()
                viewModel.updateAlarmDelay(value.toInt())
            }
        }
        
        binding.switchWifiDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateWifiDndEnabled(isChecked)
        }
        
        binding.switchScheduleDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateScheduleDndEnabled(isChecked)
        }
        
        binding.btnSetStartTime.setOnClickListener {
            showTimePicker { hour, minute ->
                val time = String.format("%02d:%02d", hour, minute)
                binding.tvStartTime.text = time
                viewModel.updateDndTime(time, binding.tvEndTime.text.toString())
            }
        }
        
        binding.btnSetEndTime.setOnClickListener {
            showTimePicker { hour, minute ->
                val time = String.format("%02d:%02d", hour, minute)
                binding.tvEndTime.text = time
                viewModel.updateDndTime(binding.tvStartTime.text.toString(), time)
            }
        }
        
        binding.btnBatteryOptimization.setOnClickListener {
            PermissionHelper.openBatteryOptimizationSettings(requireContext())
        }
        
        binding.btnAutoStart.setOnClickListener {
            PermissionHelper.openAppSettings(requireContext())
        }
        
        binding.btnRingtone.setOnClickListener {
            showRingtonePicker()
        }
        
        binding.btnRecord.setOnClickListener {
            checkRecordPermissionAndRecord()
        }
    }
    
    private fun checkRecordPermissionAndRecord() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                toggleRecording()
            }
            else -> {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }
    
    private fun startRecording() {
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        binding.btnRecord.text = "停止录音 (0s)"
        binding.btnRecord.setIconResource(android.R.drawable.ic_media_pause)
        
        // Start timer to update recording duration
        binding.root.postDelayed(object : Runnable {
            override fun run() {
                if (isRecording) {
                    val duration = (System.currentTimeMillis() - recordingStartTime) / 1000
                    binding.btnRecord.text = "停止录音 (${duration}s)"
                    binding.root.postDelayed(this, 1000)
                }
            }
        }, 1000)
        
        Toast.makeText(requireContext(), "正在录音...", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopRecording() {
        if (!isRecording) return
        
        try {
            isRecording = false
            val duration = (System.currentTimeMillis() - recordingStartTime) / 1000
            
            binding.btnRecord.text = "录制自定义铃声"
            binding.btnRecord.setIconResource(android.R.drawable.ic_btn_speak_now)
            
            Toast.makeText(
                requireContext(),
                "录音完成，时长 ${duration}秒",
                Toast.LENGTH_SHORT
            ).show()
            
            // Save recording settings
            viewModel.saveRecordingComplete()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
            Toast.makeText(
                requireContext(),
                "保存录音失败：${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun showTimePicker(callback: (hour: Int, minute: Int) -> Unit) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        
        // 使用 MaterialTimePicker
        val is24Hour = DateFormat.is24HourFormat(requireContext())
        val timeFormat = if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
        
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(timeFormat)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText("选择时间")
            .build()
        
        timePicker.addOnPositiveButtonClickListener {
            callback(timePicker.hour, timePicker.minute)
        }
        
        timePicker.show(requireActivity().supportFragmentManager, "time_picker")
    }
    
    private fun showRingtonePicker() {
        val ringtones = listOf(
            "系统默认铃声",
            "警报声",
            "蜂鸣声",
            "自定义录音"
        )
        
        var currentMediaPlayer: MediaPlayer? = null
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择报警铃声（点击可预览）")
            .setItems(ringtones.toTypedArray()) { dialog, which ->
                // 停止之前的预览
                currentMediaPlayer?.apply {
                    if (isPlaying) stop()
                    release()
                }
                
                // 选择铃声
                viewModel.selectRingtone(which)
                
                // 预览铃声（添加异常保护）
                try {
                    val previewUri = when (which) {
                        0 -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                        1 -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        2 -> null // 自定义录音 - 不预览
                        else -> null
                    }
                    
                    if (previewUri != null) {
                        currentMediaPlayer = MediaPlayer().apply {
                            setDataSource(requireContext(), previewUri)
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
                        
                    // 5 秒后自动停止预览
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            currentMediaPlayer?.apply {
                                if (isPlaying) stop()
                                release()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "停止预览失败", e)
                        }
                    }, 5000)
                    }
                    
                    Toast.makeText(
                        requireContext(),
                        "已选择：${ringtones[which]}",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "铃声预览失败", e)
                    Toast.makeText(
                        requireContext(),
                        "预览失败：${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                // 停止预览
                currentMediaPlayer?.apply {
                    if (isPlaying) stop()
                    release()
                }
                dialog.dismiss()
            }
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
