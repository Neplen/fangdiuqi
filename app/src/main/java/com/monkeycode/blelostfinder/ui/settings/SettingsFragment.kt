package com.monkeycode.blelostfinder.ui.settings

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

    companion object {
        private const val TAG = "SettingsFragment"
    }

    // 本地文件选择器
    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.saveRingtoneUri(uri.toString())
                previewRingtone(uri)
                Toast.makeText(requireContext(), "已选择本地铃声", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 系统闹钟铃声选择器
    private val systemRingtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.let { data ->
                val uri: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                uri?.let {
                    viewModel.saveRingtoneUri(it.toString())
                    previewRingtone(it)
                    Toast.makeText(requireContext(), "已选择系统铃声", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(requireContext(), "未选择铃声", Toast.LENGTH_SHORT).show()
                }
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
                launch {
                    viewModel.deviceName.collect { name ->
                        binding.etDeviceName.setText(name)
                    }
                }

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
            "选择系统铃声"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择报警铃声")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        // 使用系统默认闹钟铃声
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
        ringtonePickerLauncher.launch(intent)
    }

    /**
     * 打开系统闹钟铃声选择器
     * 列出系统所有闹钟铃声供用户选择
     */
    private fun openSystemRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹钟铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            // 如果有已保存的铃声，设置为当前选中
            val currentUri = viewModel.getCurrentRingtoneUri()
            if (currentUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
            }
        }
        try {
            systemRingtonePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开系统铃声选择器失败", e)
            Toast.makeText(requireContext(), "无法打开系统铃声选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun previewRingtone(uri: Uri) {
        stopPreview()

        try {
            currentMediaPlayer = MediaPlayer().apply {
                setDataSource(requireContext(), uri)
                setAudioStreamType(AudioManager.STREAM_ALARM)
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

            Log.d(TAG, "Previewing ringtone: $uri")
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

    override fun onDestroyView() {
        stopPreview()
        super.onDestroyView()
        _binding = null
    }
}