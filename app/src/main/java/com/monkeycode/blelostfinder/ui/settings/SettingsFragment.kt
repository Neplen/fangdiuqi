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

    companion object {
        private const val TAG = "SettingsFragment"
        private const val REQUEST_CODE_PICK_RINGTONE = 1001
    }

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.saveRingtoneUri(uri.toString())
                previewRingtone(uri)
                Toast.makeText(requireContext(), "已选择铃声", Toast.LENGTH_SHORT).show()
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

                // 核心修复：监听断连报警开关
                launch {
                    viewModel.isDisconnectAlarmEnabled.collect { enabled ->
                        binding.switchDisconnectAlarm.isChecked = enabled
                    }
                }

                launch {
                    viewModel.alarmDelay.collect { delay ->
                        binding.tvAlarmDelay.text = "$delay 秒"
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
        // 核心修复：断连报警开关
        binding.switchDisconnectAlarm.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateDisconnectAlarmEnabled(isChecked)
        }

        // 报警延迟输入弹窗
        binding.btnSetAlarmDelay.setOnClickListener {
            showNumberPickerDialog(
                title = "设置报警延迟",
                initialValue = viewModel.alarmDelay.value,
                minValue = 10,
                maxValue = 300,
                unit = " 秒",
                onConfirm = { value ->
                    viewModel.updateAlarmDelay(value)
                }
            )
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

    private fun showNumberPickerDialog(
        title: String,
        initialValue: Int,
        minValue: Int,
        maxValue: Int,
        unit: String,
        onConfirm: (Int) -> Unit
    ) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(initialValue.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                       android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(48, 16, 48, 16)
            setHint("范围：$minValue ~ $maxValue$unit")
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    var value = text.toIntOrNull() ?: initialValue
                    value = value.coerceIn(minValue, maxValue)
                    onConfirm(value)
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("重置") { _, _ ->
                val newValue = when (title) {
                    "设置报警延迟" -> 60
                    else -> initialValue
                }
                editText.setText(newValue.toString())
            }
            .show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val newValue = when (title) {
                "设置报警延迟" -> 60
                else -> initialValue
            }
            editText.setText(newValue.toString())
        }
    }

    private fun showRingtonePicker() {
        val options = arrayOf(
            "报警声",
            "选择本地铃声文件",
            "使用系统默认铃声"
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
                    2 -> {
                        viewModel.saveRingtoneUri("")
                        Toast.makeText(requireContext(), "已选择系统默认铃声", Toast.LENGTH_SHORT).show()
                    }
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

    override fun onDestroyView() {
        stopPreview()
        super.onDestroyView()
        _binding = null
    }
}