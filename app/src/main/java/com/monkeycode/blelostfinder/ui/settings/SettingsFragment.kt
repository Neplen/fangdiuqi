package com.monkeycode.blelostfinder.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

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

    // 🔥 严格匹配你的ViewModel变量名 + StateFlow监听
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 完全使用你ViewModel的变量名：rssiThreshold / alarmDelay / isWifiDndEnabled
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
            }
        }
    }

    private fun setupClickListeners() {
        // RSSI阈值弹窗（功能完整保留）
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

        // 报警延迟弹窗（功能完整保留）
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

        // WiFi勿扰开关（严格匹配ViewModel方法）
        binding.switchWifiDnd.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateWifiDndEnabled(isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}