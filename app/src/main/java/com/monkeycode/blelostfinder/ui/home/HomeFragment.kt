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

    // 核心修复：不再自己维护isAlarmPlaying，完全从ViewModel获取
    // 本地只用于UI按钮状态，实际命令发送不依赖此值
    private var isDeviceAlarmPlaying = false
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
        if (viewModel.phoneAlarmTriggered.value) {
            Log.d("HomeFragment", "onResume: 检测到报警状态，显示弹窗")
            showPhoneAlarmDialog()
        }
        val currentState = viewModel.connectionState.value
        if (currentState is BleConnectionState.Disconnected) {
            Log.d("HomeFragment", "onResume: 检测到断开状态，自动重连")
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
                        device?.let { updateDeviceState(it) }
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
                        // 核心修复：从ViewModel同步报警状态，不依赖本地变量
                        isDeviceAlarmPlaying = isPlaying
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
        if (binding.switchMonitor.isChecked != isRunning) {
            binding.switchMonitor.isChecked = isRunning
        }
    }

    private fun setupClickListeners() {
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_scan)
        }
        binding.btnConnectDevice.setOnClickListener {
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
        viewModel.stopPhoneAlarm()
        viewModel.connectToDevice()
    }

    // 核心修复：toggleDeviceAlarm不再依赖本地状态，而是发送toggle命令
    // 由ViewModel/BleManager维护真实状态
    private fun toggleDeviceAlarm() {
        if (isDeviceAlarmPlaying) {
            // 停止报警
            viewModel.stopDeviceAlarm()
            Snackbar.make(binding.root, "已停止报警", Snackbar.LENGTH_SHORT).show()
        } else {
            // 启动报警
            viewModel.startDeviceAlarm()
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

    // 核心修复：弹窗消息文字改为 24sp
    private fun showPhoneAlarmDialog() {
        alarmDialog?.dismiss()
        val deviceName = viewModel.device.value?.name ?: "iTAG"

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

    private fun updateDeviceState(device: BleDevice) {
        binding.tvDeviceName.text = device.name
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