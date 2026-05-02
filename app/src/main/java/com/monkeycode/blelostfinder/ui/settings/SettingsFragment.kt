package com.monkeycode.blelostfinder.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.databinding.FragmentSettingsBinding
import com.monkeycode.blelostfinder.ui.settings.AlarmSoundManager.Companion.REQUEST_CODE_RINGTONE
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    // 铃声选择回调
    private val ringtoneLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let {
                viewModel.saveRingtoneUri(it.toString())
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
                    viewModel.rssiThreshold.collect {
                        binding.etRssiThreshold.setText(it.toString())
                    }
                }
                launch {
                    viewModel.alarmDelay.collect {
                        binding.etAlarmDelay.setText(it.toString())
                    }
                }
                launch {
                    viewModel.isWifiDndEnabled.collect {
                        binding.switchWifiDnd.isChecked = it
                    }
                }
                launch {
                    viewModel.isScheduleDndEnabled.collect {
                        binding.switchScheduleDnd.isChecked = it
                    }
                }
                launch {
                    viewModel.dndStartTime.collect {
                        binding.tvStartTime.text = it
                    }
                }
                launch {
                    viewModel.dndEndTime.collect {
                        binding.tvEndTime.text = it
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        // 1. RSSI 阈值弹窗（修复：点击一次直接弹对话框）
        binding.etRssiThreshold.setOnClickListener {
            val currentValue = binding.etRssiThreshold.text.toString().toIntOrNull() ?: -90
            val editText = EditText(requireContext())
            editText.inputType = android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            editText.setText(currentValue.toString())

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("设置 RSSI 阈值")
                .setMessage("信号低于此值持续一段时间后触发报警\n（数值越小，距离越远才会报警）")
                .setView(editText)
                .setPositiveButton("确定") { _, _ ->
                    val input = editText.text.toString()
                    val newValue = input.toIntOrNull() ?: -90
                    val validValue = newValue.coerceIn(-100, -30)
                    viewModel.updateRssiThreshold(validValue)
                    binding.etRssiThreshold.setText(validValue.toString())
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 2. 报警延迟弹窗（修复：点击一次直接弹对话框）
        binding.etAlarmDelay.setOnClickListener {
            val currentValue = binding.etAlarmDelay.text.toString().toIntOrNull() ?: 60
            val editText = EditText(requireContext())
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            editText.setText(currentValue.toString())

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("设置 报警延迟")
                .setMessage("信号低于阈值持续此时间后触发报警（单位：秒）")
                .setView(editText)
                .setPositiveButton("确定") { _, _ ->
                    val input = editText.text.toString()
                    val newValue = input.toIntOrNull() ?: 60
                    val validValue = newValue.coerceIn(1, 300)
                    viewModel.updateAlarmDelay(validValue)
                    binding.etAlarmDelay.setText(validValue.toString())
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 3. WiFi 勿扰开关（恢复原逻辑）
        binding.switchWifiDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateWifiDndEnabled(isChecked)
        }

        // 4. 定时勿扰开关（恢复原逻辑）
        binding.switchScheduleDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateScheduleDndEnabled(isChecked)
        }

        // 5. 设置开始时间（恢复原逻辑）
        binding.btnSetStartTime.setOnClickListener {
            showTimePickerDialog(true)
        }

        // 6. 设置结束时间（恢复原逻辑）
        binding.btnSetEndTime.setOnClickListener {
            showTimePickerDialog(false)
        }

        // 7. 选择报警铃声（恢复原逻辑）
        binding.btnSelectRingtone.setOnClickListener {
            openRingtonePicker()
        }

        // 8. 电池优化设置（恢复原逻辑）
        binding.btnBatteryOptimization.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        // 9. 自启动设置（恢复原逻辑）
        binding.btnAutoStart.setOnClickListener {
            openAutoStartSettings()
        }
    }

    // --- 以下是原文件的辅助方法，我帮你完整恢复了 ---
    private fun showTimePickerDialog(isStartTime: Boolean) {
        val currentTime = if (isStartTime) binding.tvStartTime.text.toString() else binding.tvEndTime.text.toString()
        val (hour, minute) = currentTime.split(":").map { it.toInt() }

        val timePickerDialog = android.app.TimePickerDialog(
            requireContext(),
            { _, selectedHour, selectedMinute ->
                val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                if (isStartTime) {
                    viewModel.updateDndTime(formattedTime, binding.tvEndTime.text.toString())
                    binding.tvStartTime.text = formattedTime
                } else {
                    viewModel.updateDndTime(binding.tvStartTime.text.toString(), formattedTime)
                    binding.tvEndTime.text = formattedTime
                }
            },
            hour, minute, true
        )
        timePickerDialog.show()
    }

    private fun openRingtonePicker() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, null as Uri?)
        }
        ringtoneLauncher.launch(intent)
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            val generalSettings = Intent(Settings.ACTION_SETTINGS)
            startActivity(generalSettings)
        }
    }

    private fun openAutoStartSettings() {
        val intent = Intent().apply {
            component = ComponentName("com.android.settings", "com.android.settings.Settings\$PowerManagerSettingsActivity")
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            val generalSettings = Intent(Settings.ACTION_SETTINGS)
            startActivity(generalSettings)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}