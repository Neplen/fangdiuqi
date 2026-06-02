package com.monkeycode.blelostfinder.ui.home

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.ble.BleConnectionState
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    // 报警状态标记
    private var isAlarmPlaying = false
    // 弹窗引用
    private var alarmDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()

        // 如果正在报警，显示弹窗（不自动停止）
        if (viewModel.phoneAlarmTriggered.value) {
            Log.d("HomeFragment", "onResume: 检测到报警状态，显示弹窗")
            showPhoneAlarmDialog()
        }

        // 如果当前是断开状态，自动尝试重连（仅当有已保存设备时）
        val currentState = viewModel.connectionState.value
        if (currentState is BleConnectionState.Disconnected) {
            Log.d("HomeFragment", "onResume: 检测到断开状态，尝试重连")
            connectToDevice()
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionState(state)
                        updateConnectButton(state)
                    }
                }

                launch {
                    viewModel.device.collect { device ->
                        updateDeviceState(device)
                    }
                }

                launch {
                    viewModel.rssi.collect { rssi ->
                        binding.tvRssi.text = "$rssi dBm"
                        updateDistance(rssi)
                    }
                }

                launch {
                    viewModel.batteryLevel.collect { level ->
                        if (level >= 0) {
                            binding.tvBattery.text = "$level%"
                        } else {
                            binding.tvBattery.text = "--%"
                        }
                    }
                }

                launch {
                    viewModel.isDeviceAlarmPlaying.collect { isPlaying ->
                        updateAlarmButton(isPlaying)
                    }
                }

                launch {
                    viewModel.phoneAlarmTriggered.collect { triggered ->
                        if (triggered) {
                            showPhoneAlarmDialog()
                        }
                    }
                }

                launch {
                    viewModel.isMonitoringRunning.collect { isRunning ->
                        updateMonitorSwitch(isRunning)
                    }
                }
            }
        }
    }

    private fun updateConnectButton(state: BleConnectionState) {
        when (state) {
            is BleConnectionState.Connected -> {
                binding.btnConnectDevice.text = "已连接"
                binding.btnConnectDevice.isEnabled = false
            }
            is BleConnectionState.Disconnected -> {
                binding.btnConnectDevice.text = "连接"
                binding.btnConnectDevice.isEnabled = true
            }
            is BleConnectionState.Connecting -> {
                binding.btnConnectDevice.text = "连接中..."
                binding.btnConnectDevice.isEnabled = false
            }
            is BleConnectionState.Error -> {
                binding.btnConnectDevice.text = "连接"
                binding.btnConnectDevice.isEnabled = true
            }
            else -> {}
        }
    }

    private fun updateMonitorSwitch(isRunning: Boolean) {
        // 只有当开关当前状态与服务状态不一致时才更新，避免触发 onCheckedChanged
        if (binding.switchMonitor.isChecked != isRunning) {
            binding.switchMonitor.isChecked = isRunning
        }
    }

    private fun setupClickListeners() {
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_scan)
        }

        binding.btnConnectDevice.setOnClickListener {
            // 核心修复：点击"连接"按钮时，强制重新连接
            // 解决"超过 40 秒回到范围，显示已断开，点击连接无效"的问题
            connectToDevice()
            Snackbar.make(binding.root, "正在连接设备...", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnAlarmDevice.setOnClickListener {
            toggleDeviceAlarm()
        }

        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.startMonitoring()
            } else {
                viewModel.stopMonitoring()
            }
        }
    }

    private fun connectToDevice() {
        // 核心修复：停止手机报警（如果正在报警）
        // 解决"回到范围后手机仍在报警，打开 APP 点击连接"的场景
        viewModel.stopPhoneAlarm()

        // 触发重连（仅当有已保存设备时才会真正发起连接）
        viewModel.connectToDevice()
    }

    private fun toggleDeviceAlarm() {
        if (isAlarmPlaying) {
            // 停止报警
            viewModel.stopDeviceAlarm()
            isAlarmPlaying = false
            updateAlarmButton(false)
            Snackbar.make(binding.root, "已停止报警", Snackbar.LENGTH_SHORT).show()
        } else {
            // 启动报警
            viewModel.startDeviceAlarm()
            isAlarmPlaying = true
            updateAlarmButton(true)
            Snackbar.make(binding.root, "正在让防丢器响铃...", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateAlarmButton(isPlaying: Boolean) {
        if (isPlaying) {
            binding.btnAlarmDevice.text = "停止报警"
            binding.btnAlarmDevice.setIconResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnAlarmDevice.text = "点击报警"
            binding.btnAlarmDevice.setIconResource(android.R.drawable.ic_dialog_alert)
        }
    }

    // 核心修复：弹窗消息文字改为 24sp，设备名称动态获取
    private fun showPhoneAlarmDialog() {
        alarmDialog?.dismiss()
        // 核心修复：动态获取设备名称，未绑定时显示"防丢器"
        val deviceName = viewModel.device.value?.name ?: "防丢器"

        val message = SpannableString("按下防丢器按钮两次可以停止报警")
        message.setSpan(
            AbsoluteSizeSpan(24, true),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        alarmDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("[$deviceName] 正在寻找您的手机")
            .setMessage(message)
            .setPositiveButton("好的") { _, _ ->
                viewModel.stopPhoneAlarm()
                dismissAlarmDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun dismissAlarmDialog() {
        alarmDialog?.dismiss()
        alarmDialog = null
    }

    private fun updateConnectionState(state: BleConnectionState) {
        when (state) {
            is BleConnectionState.Connected -> {
                binding.tvConnectionStatus.text = "已连接"
                binding.tvConnectionStatus.setTextColor(requireContext().getColor(R.color.connected))
            }
            is BleConnectionState.Disconnected -> {
                binding.tvConnectionStatus.text = "已断开"
                binding.tvConnectionStatus.setTextColor(requireContext().getColor(R.color.disconnected))
            }
            is BleConnectionState.Connecting -> {
                binding.tvConnectionStatus.text = "连接中..."
                binding.tvConnectionStatus.setTextColor(requireContext().getColor(R.color.warning))
            }
            is BleConnectionState.Error -> {
                binding.tvConnectionStatus.text = "错误"
                binding.tvConnectionStatus.setTextColor(requireContext().getColor(R.color.disconnected))
            }
            else -> {}
        }
    }

    // 核心修复：支持未绑定设备状态（device 为 null 时显示"未绑定设备"）
    private fun updateDeviceState(device: BleDevice?) {
        binding.tvDeviceName.text = device?.name ?: "未绑定设备"
    }

    private fun updateDistance(rssi: Int) {
        val distance = when {
            rssi > -70 -> "1 米内"
            rssi > -80 -> "1-3 米"
            rssi > -90 -> "3-10 米"
            else -> "> 10 米"
        }
        binding.tvDistance.text = distance
    }

    override fun onDestroyView() {
        dismissAlarmDialog()
        viewModel.stopPhoneAlarm()
        super.onDestroyView()
        _binding = null
    }
}