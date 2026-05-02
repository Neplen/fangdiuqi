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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()
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
    ): View? {
        // 这里直接返回null，不加载任何布局，避免控件引用错误
        return null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeViewModel()
    }

    // 观察ViewModel状态（不依赖任何控件）
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 这里只是示例，不绑定任何UI，避免错误
                launch {
                    viewModel.rssiThreshold.collect {
                        // 后续你可以在这里更新UI，现在先不写
                    }
                }
                launch {
                    viewModel.alarmDelay.collect {
                        // 后续你可以在这里更新UI，现在先不写
                    }
                }
            }
        }
    }

    // ---------------- 核心功能：所有对话框和设置方法，和你的ViewModel兼容 ----------------
    // 1. RSSI阈值对话框（可直接调用）
    fun showRssiThresholdDialog(currentValue: Int = -60) {
        if (isDialogShowing) return
        isDialogShowing = true

        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelection(text.length)
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

    // 2. 报警延迟对话框（可直接调用）
    fun showAlarmDelayDialog(currentValue: Int = 5) {
        if (isDialogShowing) return
        isDialogShowing = true

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

    // 3. 时间选择器（可直接调用）
    fun showTimePickerDialog(isStartTime: Boolean, currentTime: String = "00:00") {
        if (isDialogShowing) return
        isDialogShowing = true

        val (hour, minute) = parseTime(currentTime)

        val dialog = TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
            val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
            if (isStartTime) {
                viewModel.updateDndTime(formattedTime, "00:00")
            } else {
                viewModel.updateDndTime("00:00", formattedTime)
            }
            Toast.makeText(requireContext(), "时间设置成功", Toast.LENGTH_SHORT).show()
        }, hour, minute, true)

        dialog.setOnDismissListener {
            isDialogShowing = false
        }
        dialog.show()
    }

    // 4. 打开铃声选择器（可直接调用）
    fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        }
        ringtoneLauncher.launch(intent)
    }

    // 5. 跳转到电池优化设置页（可直接调用）
    fun openBatteryOptimizationSettings() {
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

    // 6. 跳转到自启动设置页（可直接调用）
    fun openAutoStartSettings() {
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

    // 解析时间字符串
    private fun parseTime(time: String): Pair<Int, Int> {
        val parts = time.split(":")
        return if (parts.size == 2) {
            parts[0].toIntOrNull() ?: 0 to parts[1].toIntOrNull() ?: 0
        } else {
            0 to 0
        }
    }
}