package com.monkeycode.blelostfinder.ui.settings

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
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
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    // 防止重复点击对话框的标志位
    private var isDialogShowing = false

    // 铃声选择的ActivityResult
    private val ringtoneLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uri?.let {
                viewModel.saveRingtoneUri(it.toString())
                Toast.makeText(requireContext(), "铃声设置成功", Toast.LENGTH_SHORT).show()
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
        setupClickListeners()
        observeViewModel()
    }

    // 1. 绑定所有按钮点击事件（解决"点击无反应"问题）
    private fun setupClickListeners() {
        // RSSI阈值设置
        binding.tvRssiThreshold.setOnClickListener {
            if (!isDialogShowing) showRssiThresholdDialog()
        }

        // 报警延迟设置
        binding.tvAlarmDelay.setOnClickListener {
            if (!isDialogShowing) showAlarmDelayDialog()
        }

        // 免打扰- WiFi开关
        binding.switchWifiDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateWifiDndEnabled(isChecked)
        }

        // 免打扰- 定时开关
        binding.switchScheduleDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateScheduleDndEnabled(isChecked)
        }

        // 免打扰- 开始时间
        binding.tvDndStartTime.setOnClickListener {
            if (!isDialogShowing) showTimePickerDialog(true)
        }

        // 免打扰- 结束时间
        binding.tvDndEndTime.setOnClickListener {
            if (!isDialogShowing) showTimePickerDialog(false)
        }

        // 选择报警铃声
        binding.btnSelectRingtone.setOnClickListener {
            openRingtonePicker()
        }

        // 跳转到电池优化设置
        binding.btnBatteryOptimization.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        // 跳转到自启动设置
        binding.btnAutoStart.setOnClickListener {
            openAutoStartSettings()
        }
    }

    // 2. 观察ViewModel状态，更新UI
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 设备名称（显示用）
                launch {
                    viewModel.deviceName.collect {
                        binding.tvDeviceName.text = it
                    }
                }

                // RSSI阈值
                launch {
                    viewModel.rssiThreshold.collect {
                        binding.tvRssiThreshold.text = it.toString()
                    }
                }

                // 报警延迟
                launch {
                    viewModel.alarmDelay.collect {
                        binding.tvAlarmDelay.text = it.toString()
                    }
                }

                // WiFi免打扰开关
                launch {
                    viewModel.isWifiDndEnabled.collect {
                        binding.switchWifiDnd.isChecked = it
                    }
                }

                // 定时免打扰开关
                launch {
                    viewModel.isScheduleDndEnabled.collect {
                        binding.switchScheduleDnd.isChecked = it
                    }
                }

                // 免打扰开始时间
                launch {
                    viewModel.dndStartTime.collect {
                        binding.tvDndStartTime.text = it
                    }
                }

                // 免打扰结束时间
                launch {
                    viewModel.dndEndTime.collect {
                        binding.tvDndEndTime.text = it
                    }
                }
            }
        }
    }

    // 3. RSSI阈值对话框（解决"多次点击才弹出、输入法错乱"问题）
    private fun showRssiThresholdDialog() {
        isDialogShowing = true
        val currentValue = binding.tvRssiThreshold.text.toString().toInt()
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER // 强制数字键盘
            setText(currentValue.toString())
            setSelection(text.length) // 光标移到末尾
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("设置RSSI阈值")
            .setMessage("请输入RSSI阈值（数值越小，信号越弱）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newValue = editText.text.toString().toIntOrNull() ?: currentValue
                viewModel.updateRssiThreshold(newValue)
                Toast.makeText(requireContext(), "已更新RSSI阈值", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                isDialogShowing = false // 对话框关闭后重置标志位
            }
            .create()

        // 对话框显示后强制弹出输入法（解决"第一次只弹键盘"问题）
        dialog.setOnShowListener {
            editText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }

    // 4. 报警延迟对话框（和RSSI逻辑一致，解决输入法问题）
    private fun showAlarmDelayDialog() {
        isDialogShowing = true
        val currentValue = binding.tvAlarmDelay.text.toString().toInt()
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("设置报警延迟")
            .setMessage("请输入报警延迟（单位：秒）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newValue = editText.text.toString().toIntOrNull() ?: currentValue
                viewModel.updateAlarmDelay(newValue)
                Toast.makeText(requireContext(), "已更新报警延迟", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                isDialogShowing = false
            }
            .create()

        dialog.setOnShowListener {
            editText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }

    // 5. 时间选择器（解决"开始/结束时间按钮无反应"问题）
    private fun showTimePickerDialog(isStartTime: Boolean) {
        isDialogShowing = true
        val currentTime = if (isStartTime) binding.tvDndStartTime.text.toString() else binding.tvDndEndTime.text.toString()
        val (hour, minute) = parseTime(currentTime)

        val dialog = TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
            val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
            if (isStartTime) {
                viewModel.updateDndTime(formattedTime, binding.tvDndEndTime.text.toString())
                binding.tvDndStartTime.text = formattedTime
            } else {
                viewModel.updateDndTime(binding.tvDndStartTime.text.toString(), formattedTime)
                binding.tvDndEndTime.text = formattedTime
            }
            Toast.makeText(requireContext(), "时间设置成功", Toast.LENGTH_SHORT).show()
        }, hour, minute, true)

        dialog.setOnDismissListener {
            isDialogShowing = false
        }
        dialog.show()
    }

    // 解析时间字符串（HH:mm）为小时和分钟
    private fun parseTime(time: String): Pair<Int, Int> {
        val parts = time.split(":")
        return if (parts.size == 2) {
            parts[0].toIntOrNull() ?: 0 to parts[1].toIntOrNull() ?: 0
        } else {
            0 to 0
        }
    }

    // 6. 打开铃声选择器
    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        }
        ringtoneLauncher.launch(intent)
    }

    // 7. 跳转到电池优化设置页
    private fun openBatteryOptimizationSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${requireContext().packageName}")
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
        }
    }

    // 8. 跳转到自启动设置页（适配不同安卓版本）
    private fun openAutoStartSettings() {
        val intent = Intent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.parse("package:${requireContext().packageName}")
        } else {
            intent.action = "android.settings.APPLICATION_SETTINGS"
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "无法打开自启动设置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}