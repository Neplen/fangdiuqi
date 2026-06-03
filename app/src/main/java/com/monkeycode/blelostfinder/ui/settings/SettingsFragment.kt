package com.monkeycode.blelostfinder.ui.settings

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.monkeycode.blelostfinder.databinding.FragmentSettingsBinding
import com.monkeycode.blelostfinder.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.*

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var currentMediaPlayer: MediaPlayer? = null
    
    // 出门提醒铃声预览
    private var goOutReminderMediaPlayer: MediaPlayer? = null

    companion object {
        private const val TAG = "SettingsFragment"
    }

    // 本地音频文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // 根据当前焦点判断是保存哪种铃声
                if (binding.btnGoOutRingtone.hasFocus()) {
                    viewModel.saveGoOutRingtonePath(uri.toString())
                    previewGoOutRingtone(uri)
                    Toast.makeText(requireContext(), "已选择出门提醒铃声", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.saveRingtoneUri(uri.toString())
                    previewRingtone(uri)
                    Toast.makeText(requireContext(), "已选择本地铃声", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 系统铃声选择器（新增）
    private val systemRingtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uri?.let {
                viewModel.saveRingtoneUri(it.toString())
                previewRingtone(it)
                Toast.makeText(requireContext(), "已选择系统铃声", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(requireContext(), "未选择铃声", Toast.LENGTH_SHORT).show()
            }
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
                // ===== 修改：删除 deviceName 监听（设置页不再显示设备名称输入框） =====

                launch {
                    viewModel.isDisconnectAlarmEnabled.collect { enabled ->
                        binding.switchDisconnectAlarm.isChecked = enabled
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
                
                launch {
                    viewModel.isGoOutReminderEnabled.collect { enabled ->
                        binding.switchGoOutReminder.isChecked = enabled
                    }
                }
                
                launch {
                    viewModel.homeWifiSsid.collect { ssid ->
                        binding.etHomeWifiSsid.setText(ssid)
                    }
                }
                
                launch {
                    viewModel.homeWifiBssid.collect { bssid ->
                        binding.etHomeWifiBssid.setText(bssid)
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.switchDisconnectAlarm.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateDisconnectAlarmEnabled(isChecked)
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
        
        binding.switchGoOutReminder.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateGoOutReminderEnabled(isChecked)
        }
        
        binding.btnGoOutRingtone.setOnClickListener {
            showGoOutRingtonePicker()
        }
        
        // 保存 WiFi 信息
        binding.etHomeWifiSsid.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveHomeWifiConfig()
            }
        }
        
        binding.etHomeWifiBssid.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveHomeWifiConfig()
            }
        }
    }

    private fun showTimePicker(callback: (hour: Int, minute: Int) -> Unit) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

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
        val options = arrayOf(
            "报警声",
            "选择本地铃声文件",
            "在系统铃声中选择"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择报警铃声")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        viewModel.saveRingtoneUri(alarmUri?.toString() ?: "")
                        alarmUri?.let { previewRingtone(it) }
                        Toast.makeText(requireContext(), "已选择报警声", Toast.LENGTH_SHORT).show()
                    }
                    1 -> openFilePicker()
                    2 -> openSystemRingtonePicker()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mp3", "audio/wav", "audio/mpeg", "audio/ogg"))
        }
        filePickerLauncher.launch(intent)
    }

    // 新增：打开系统闹钟铃声选择器
    private fun openSystemRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
        }
        systemRingtonePickerLauncher.launch(intent)
    }

    private fun previewRingtone(uri: Uri) {
        stopPreview()

        try {
            currentMediaPlayer = MediaPlayer().apply {
                setDataSource(requireContext(), uri)
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

            currentMediaPlayer?.setOnCompletionListener {
                stopPreview()
            }

            Log.d(TAG, "Previewing ringtone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preview ringtone", e)
            Toast.makeText(requireContext(), "预览失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        try {
            currentMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping preview", e)
        } finally {
            currentMediaPlayer = null
        }
    }
    
    private fun saveHomeWifiConfig() {
        val ssid = binding.etHomeWifiSsid.text.toString().trim()
        val bssid = binding.etHomeWifiBssid.text.toString().trim()
        viewModel.saveHomeWifi(ssid, bssid)
        Log.d(TAG, "Saved home WiFi config: SSID=$ssid, BSSID=$bssid")
    }
    
    private fun showGoOutRingtonePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mp3", "audio/wav", "audio/mpeg", "audio/ogg"))
        }
        filePickerLauncher.launch(intent)
    }
    
    private fun previewGoOutRingtone(uri: android.net.Uri) {
        stopGoOutPreview()
        
        try {
            goOutReminderMediaPlayer = MediaPlayer().apply {
                setDataSource(requireContext(), uri)
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
            
            goOutReminderMediaPlayer?.setOnCompletionListener {
                stopGoOutPreview()
            }
            
            Log.d(TAG, "Previewing go out reminder ringtone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preview go out reminder ringtone", e)
            Toast.makeText(requireContext(), "预览失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopGoOutPreview() {
        try {
            goOutReminderMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping go out preview", e)
        } finally {
            goOutReminderMediaPlayer = null
        }
    }

    override fun onDestroyView() {
        stopPreview()
        stopGoOutPreview()
        super.onDestroyView()
        _binding = null
    }
}