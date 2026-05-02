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
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners(view)
        observeViewModel(view)
    }

    // 绑定按钮点击事件（把xxx_*改成你布局里的实际ID）
    private fun setupClickListeners(view: View) {
        // RSSI阈值设置
        view.findViewById<View>(R.id.xxx_rssi_threshold).setOnClickListener {
            if (!isDialogShowing) showRssiThresholdDialog()
        }

        // 报警延迟设置
        view.findViewById<View>(R.id.xxx_alarm_delay).setOnClickListener {
            if (!isDialogShowing) showAlarmDelayDialog()
        }

        // WiFi免打扰开关
        view.findViewById<android.widget.Switch>(R.id.xxx_switch_wifi_dnd).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateWifiDndEnabled(isChecked)
        }

        // 定时免打扰开关
        view.findViewById<android.widget.Switch>(R.id.xxx_switch_schedule_dnd).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateScheduleDndEnabled(isChecked)
        }

        // 免打扰开始时间
        view.findViewById<View>(R.id.xxx_dnd_start_time).setOnClickListener {
            if (!isDialogShowing) showTimePickerDialog(true)
        }

        // 免打扰结束时间
        view.findViewById<View>(R.id.xxx_dnd_end_time).setOnClickListener {
            if (!isDialogShowing) showTimePickerDialog(false)
        }

        // 选择报警铃声
        view.findViewById<View>(R.id.xxx_btn_select_ringtone).setOnClickListener {
            openRingtonePicker()
        }

        // 电池优化设置
        view.findViewById<View>(R.id.xxx_btn_battery_optimization).setOnClickListener {
            openBatteryOptimizationSettings()
        }

        // 自启动设置
        view.findViewById<View>(R.id.xxx_btn_auto_start).setOnClickListener {
            openAutoStartSettings()
        }
    }

    // 观察ViewModel状态，更新UI（把xxx_*改成你布局里的实际ID）
    private fun observeViewModel(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rssiThreshold.collect {
                        view.findViewById<android.widget.TextView>(R.id.xxx_rssi_threshold).text = it.toString()
                    }
                }
                launch {
                    viewModel.alarmDelay.collect {
                        view.findViewById<android.widget.TextView>(R.id.xxx_alarm_delay).text = it.toString()
                    }
                }
                launch {
                    viewModel.isWifiDndEnabled.collect {
                        view.findViewById<android.widget.Switch>(R.id.xxx_switch_wifi_dnd).isChecked = it
                    }
                }
                launch {
                    viewModel.isScheduleDndEnabled.collect {
                        view.findViewById<android.widget.Switch>(R.id.xxx_switch_schedule_dnd).isChecked = it
                    }
                }
                launch {
                    viewModel.dndStartTime.collect {
                        view.findViewById<android.widget.TextView>(R.id.xxx_dnd_start_time).text = it
                    }
                }
                launch {
                    viewModel.dndEndTime.collect {
                        view.findViewById<android.widget.TextView>(R.id.xxx_dnd_end_time).text = it
                    }
                }
            }
        }
    }

    // RSSI阈值对话框（解决输入法错乱问题）
    private fun showRssiThresholdDialog() {
        isDialogShowing = true
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("设置RSSI阈值")
            .setMessage("请输入RSSI阈值（数值越小，信号越弱）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newValue = editText.text.toString().toIntOrNull() ?: 0
                viewModel.updateRssiThreshold(newValue)
                Toast.makeText(requireContext(), "已更新RSSI阈值", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { isDialogShowing = false }
            .create()

        dialog.setOnShowListener {
            editText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    // 报警延迟对话框
    private fun showAlarmDelayDialog() {
        isDialogShowing = true
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("设置报警延迟")
            .setMessage("请输入报警延迟（单位：秒）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newValue = editText.text.toString().toIntOrNull() ?: 0
                viewModel.updateAlarmDelay(newValue)
                Toast.makeText(requireContext(), "已更新报警延迟", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { isDialogShowing = false }
            .create()

        dialog.setOnShowListener {
            editText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    // 时间选择器
    private fun showTimePickerDialog(isStartTime: Boolean) {
        isDialogShowing = true
        val (hour, minute) = parseTime("00:00")

        val dialog = TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
            val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
            if (isStartTime) {
                viewModel.updateDndTime(formattedTime, "00:00")
            } else {
                viewModel.updateDndTime("00:00", formattedTime)
            }
            Toast.makeText(requireContext(), "时间设置成功", Toast.LENGTH_SHORT).show()
        }, hour, minute, true)

        dialog.setOnDismissListener { isDialogShowing = false }
        dialog.show()
    }

    private fun parseTime(time: String): Pair<Int, Int> {
        val parts = time.split(":")
        return if (parts.size == 2) {
            parts[0].toIntOrNull() ?: 0 to parts[1].toIntOrNull() ?: 0
        } else {
            0 to 0
        }
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        }
        ringtoneLauncher.launch(intent)
    }

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
}