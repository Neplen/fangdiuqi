package com.monkeycode.blelostfinder.ui.settings

import android.content.Context
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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

        // 关键：不让EditText获得焦点、不让弹出键盘
        binding.etRssiThreshold.isFocusable = false
        binding.etRssiThreshold.isClickable = true

        binding.etAlarmDelay.isFocusable = false
        binding.etAlarmDelay.isClickable = true

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
                        binding.etRssiThreshold.setText(threshold.toString())
                    }
                }

                launch {
                    viewModel.alarmDelay.collect { delay ->
                        binding.etAlarmDelay.setText(delay.toString())
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
        // RSSI：点击直接弹对话框，绝不弹键盘
        binding.etRssiThreshold.setOnClickListener {
            hideSoftInput()
            showRssiDialog(binding.etRssiThreshold.text.toString())
        }

        // 报警延迟：点击直接弹对话框，绝不弹键盘
        binding.etAlarmDelay.setOnClickListener {
            hideSoftInput()
            showDelayDialog(binding.etAlarmDelay.text.toString())
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

    // -------- RSSI 对话框 --------
    private fun showRssiDialog(current: String) {
        val edit = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current)
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置RSSI阈值")
            .setMessage("数值越小，信号越弱")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val value = edit.text.toString().toIntOrNull() ?: -60
                viewModel.updateRssiThreshold(value)
                binding.etRssiThreshold.setText(value.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // -------- 报警延迟 对话框 --------
    private fun showDelayDialog(current: String) {
        val edit = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current)
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置报警延迟")
            .setMessage("单位：秒")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val value = edit.text.toString().toIntOrNull() ?: 5
                viewModel.updateAlarmDelay(value)
                binding.etAlarmDelay.setText(value.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // -------- 屏蔽输入法 --------
    private fun hideSoftInput() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    // -------- 时间选择 --------
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

    // -------- 铃声选择、预览等（原样保留）--------
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
            currentMediaPlayer?.setOnCompletionListener { stopPreview() }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "预览失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        try {
            currentMediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview error", e)
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