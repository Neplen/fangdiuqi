package com.monkeycode.blelostfinder.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    // ==================== 修复：增加当前报警类型记录 ====================
    // 用于区分断连报警("disconnect")和出门提醒("go_out")，决定弹窗颜色和内容
    private var currentAlarmType: String? = null

    // 广播接收器（出门提醒和报警共用）
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.monkeycode.blelostfinder.SHOW_PHONE_ALARM" -> {
                    // ==================== 修复：读取广播中的 alarm_type 区分类型 ====================
                    val alarmType = intent.getStringExtra("alarm_type") ?: "disconnect"
                    currentAlarmType = alarmType
                    val isGoOut = alarmType == "go_out"
                    Log.d("HomeFragment", "收到广播，alarmType=$alarmType, isGoOut=$isGoOut")
                    showAlarmDialog(isGoOutReminder = isGoOut)
                }
            }
        }
    }

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

        // 注册广播接收器（同时监听报警和出门提醒）
        val filter = IntentFilter().apply {
            addAction("com.monkeycode.blelostfinder.SHOW_PHONE_ALARM")
        }
        requireContext().registerReceiver(alarmReceiver, filter)
        Log.d("HomeFragment", "Alarm receiver registered")
    }

    override fun onResume() {
        super.onResume()

        // 如果正在报警，显示弹窗（不自动停止）
        if (viewModel.phoneAlarmTriggered.value) {
            Log.d("HomeFragment", "onResume: 检测到报警状态，显示弹窗")
            val isGoOut = currentAlarmType == "go_out"
            showAlarmDialog(isGoOutReminder = isGoOut)
        }

        // 只有已绑定设备且当前断开时才自动重连
        if (viewModel.isDeviceBound.value) {
            val currentState = viewModel.connectionState.value
            if (currentState is BleConnectionState.Disconnected) {
                Log.d("HomeFragment", "onResume: 检测到断开状态，自动重连")
                connectToDevice()
            }
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
                    viewModel.connectedDeviceName.collect { name ->
                        binding.tvDeviceName.text = name ?: "未连接"
                    }
                }

                launch {
                    viewModel.device.collect { device ->
                        device?.let {
                            // 数据库中的设备信息作为备用显示
                            if (viewModel.connectedDeviceName.value == null) {
                                binding.tvDeviceName.text = it.name
                            }
                        }
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
                            // ==================== 修复：根据 currentAlarmType 显示对应弹窗 ====================
                            val isGoOut = currentAlarmType == "go_out"
                            showAlarmDialog(isGoOutReminder = isGoOut)
                        }
                    }
                }

                launch {
                    viewModel.isMonitoringRunning.collect { isRunning ->
                        updateMonitorSwitch(isRunning)
                    }
                }

                launch {
                    viewModel.isDeviceBound.collect { isBound ->
                        if (!isBound) {
                            binding.tvConnectionStatus.text = "未绑定设备"
                            binding.tvConnectionStatus.setTextColor(requireContext().getColor(R.color.disconnected))
                            binding.btnConnectDevice.text = "去扫描绑定"
                            binding.btnConnectDevice.isEnabled = true
                        }
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
                if (viewModel.isDeviceBound.value) {
                    binding.btnConnectDevice.text = "连接"
                } else {
                    binding.btnConnectDevice.text = "去扫描绑定"
                }
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
            if (!viewModel.isDeviceBound.value) {
                findNavController().navigate(R.id.action_scan)
                Snackbar.make(binding.root, "请先扫描并绑定防丢器", Snackbar.LENGTH_SHORT).show()
            } else {
                connectToDevice()
                Snackbar.make(binding.root, "正在连接设备...", Snackbar.LENGTH_SHORT).show()
            }
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

    private fun toggleDeviceAlarm() {
        if (isAlarmPlaying) {
            viewModel.stopDeviceAlarm()
            isAlarmPlaying = false
            updateAlarmButton(false)
            Snackbar.make(binding.root, "已停止报警", Snackbar.LENGTH_SHORT).show()
        } else {
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

    // 统一的报警/提醒弹窗
    private fun showAlarmDialog(isGoOutReminder: Boolean = false) {
        alarmDialog?.dismiss()

        val title: String
        val messageText: String
        val textColor: Int

        if (isGoOutReminder) {
            title = "出门提醒"
            messageText = "主人，主人，检查一下，你的钥匙带没带？"
            textColor = requireContext().getColor(android.R.color.holo_blue_dark) // 蓝色
        } else {
            val deviceName = viewModel.connectedDeviceName.value
                ?: viewModel.device.value?.name
                ?: "防丢器"
            title = "[$deviceName] 正在寻找您的手机"
            messageText = "按下防丢器按钮两次可以停止报警"
            textColor = requireContext().getColor(android.R.color.holo_red_dark) // 红色
        }

        val message = SpannableString(messageText)
        message.setSpan(
            AbsoluteSizeSpan(24, true),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 设置文字颜色
        message.setSpan(
            android.text.style.ForegroundColorSpan(textColor),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        alarmDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("好的") { _, _ ->
                if (isGoOutReminder) {
                    viewModel.stopGoOutReminder()
                } else {
                    viewModel.stopPhoneAlarm()
                }
                dismissAlarmDialog()
            }
            .setCancelable(false)
            .show()

        Log.d("HomeFragment", "弹窗已显示: ${if (isGoOutReminder) "出门提醒(蓝)" else "断连报警(红)"}")
    }

    // 兼容旧调用
    private fun showPhoneAlarmDialog() {
        showAlarmDialog(isGoOutReminder = false)
    }

    private fun dismissAlarmDialog() {
        alarmDialog?.dismiss()
        alarmDialog = null
        // 核心修复：清空报警类型，避免状态残留影响下次弹窗
        currentAlarmType = null
        // 核心修复：确保ViewModel中的弹窗状态被清除，避免重复触发
        viewModel.clearPhoneAlertDialog()
    }

    private fun updateConnectionState(state: BleConnectionState) {
        when (state) {
            is BleConnectionState.Connected -> {
                binding.tvConnectionStatus.text = "已连接"
                binding.tvConnectionStatus.setTextColor(requireContext().getColor(R.color.connected))
            }
            is BleConnectionState.Disconnected -> {
                if (viewModel.isDeviceBound.value) {
                    binding.tvConnectionStatus.text = "已断开"
                } else {
                    binding.tvConnectionStatus.text = "未绑定设备"
                }
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

        try {
            requireContext().unregisterReceiver(alarmReceiver)
            Log.d("HomeFragment", "Alarm receiver unregistered")
        } catch (e: Exception) {
            Log.e("HomeFragment", "Unregister receiver failed", e)
        }

        super.onDestroyView()
        _binding = null
    }
}