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
import com.google.android.material.textfield.TextInputEditText
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
    private var goOutReminderMediaPlayer: MediaPlayer? = null

    companion object {
        private const val TAG = "SettingsFragment"
    }

    // 本地音频文件选择器（报警铃声）
    private val alarmFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.saveRingtoneUri(uri.toString())
                previewRingtone(uri)
                Toast.makeText(requireContext(), "已选择报警铃声", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 本地音频文件选择器（出门提醒铃声）
    private val goOutFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.saveGoOutRingtonePath(uri.toString())
                previewGoOutRingtone(uri)
                Toast.makeText(requireContext(), "已选择出门提醒铃声", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 系统铃声选择器
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

                // 显示已保存的WiFi信息（只显示前几位+星号，保护隐私）
                launch {
                    viewModel.homeWifiSsid.collect { ssid ->
                        binding.tvHomeWifiSsidValue.text = maskString(ssid)
                    }
                }

                launch {
                    viewModel.homeWifiBssid.collect { bssid ->
                        binding.tvHomeWifiBssidValue.text = maskString(bssid)
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

        // 点击显示WiFi信息区域，弹出对话框编辑
        binding.layoutHomeWifiSsid.setOnClickListener {
            showWifiInputDialog(
                title = "家庭 WiFi 名称 (SSID)",
                currentValue = viewModel.homeWifiSsid.value,
                onSave = { value ->
                    viewModel.saveHomeWifi(value, viewModel.homeWifiBssid.value)
                }
            )
        }

        binding.layoutHomeWifiBssid.setOnClickListener {
            showWifiInputDialog(
                title = "家庭路由器 MAC 地址 (BSSID)",
                currentValue = viewModel.homeWifiBssid.value,
                onSave = { value ->
                    viewModel.saveHomeWifi(viewModel.homeWifiSsid.value, value)
                }
            )
        }
    }

    /**
     * 显示WiFi信息输入对话框（带确定按钮，密码样式输入）
     */
    private fun showWifiInputDialog(title: String, currentValue: String, onSave: (String) -> Unit) {
        val context = requireContext()

        // 创建输入框
        val input = TextInputEditText(context).apply {
            setText(currentValue)
            hint = title
            // 密码样式：默认显示为星号
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            // 设置字体等
            textSize = 16f
        }

        // 创建TextInputLayout包装（可选，这里直接用AlertDialog的内置样式）
        val container = android.widget.FrameLayout(context).apply {
            setPadding(
                (24 * context.resources.displayMetrics.density).toInt(),
                (16 * context.resources.displayMetrics.density).toInt(),
                (24 * context.resources.displayMetrics.density).toInt(),
                (8 * context.resources.displayMetrics.density).toInt()
            )
            addView(input)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString().trim()
                onSave(value)
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("显示") { _, _ ->
                // 切换显示/隐藏
                val currentType = input.inputType
                val isPassword = (currentType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                if (isPassword) {
                    input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                // 光标移到最后
                input.setSelection(input.text?.length ?: 0)
            }
            .show()

        // 确保对话框打开时自动弹出键盘
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 字符串脱敏显示（保留前3位，其余用*代替）
     */
    private fun maskString(input: String): String {
        if (input.length <= 3) return input
        return input.take(3) + "*".repeat(input.length - 3)
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
                    1 -> openAlarmFilePicker()
                    2 -> openSystemRingtonePicker()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAlarmFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mp3", "audio/wav", "audio/mpeg", "audio/ogg"))
        }
        alarmFilePickerLauncher.launch(intent)
    }

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

    private fun showGoOutRingtonePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mp3", "audio/wav", "audio/mpeg", "audio/ogg"))
        }
        goOutFilePickerLauncher.launch(intent)
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