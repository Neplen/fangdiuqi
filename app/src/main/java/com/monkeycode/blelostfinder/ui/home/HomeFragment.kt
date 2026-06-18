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

    // 出门提醒弹窗引用
    private var goOutReminderDialog: androidx.appcompat.app.AlertDialog? = null

    // 广播接收器
    private val goOutReminderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.monkeycode.blelostfinder.SHOW_GO_OUT_REMINDER") {
                Log.d("HomeFragment", "收到出门提醒广播，显示弹窗")
                showGoOutReminderDialog()
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

        // 注册广播接收器
        val filter = IntentFilter("com.monkeycode.blelostfinder.SHOW_GO_OUT_REMINDER")
        requireContext().registerReceiver(goOutReminderReceiver, filter)
        Log.d("HomeFragment", "Go out reminder receiver registered")
    }

    override fun onResume() {
        super.onResume()

        // 如果正在报警，显示弹窗（不自动停止）
        if (viewModel.phoneAlarmTriggered.value) {
            Log.d("HomeFragment", "onResume: 检测到报警状态，显示弹窗")
            showPhoneAlarmDialog()
        }

        // 如果正在出门提醒，显示弹窗
        if (viewModel.goOutReminderTriggered.value) {
            Log.d("HomeFragment", "onResume: 检测出门提醒状态，显示弹窗")
            showGoOutReminderDialog()
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
                            showPhoneAlarmDialog()
                        }
                    }
                }

                // 新增：监听出门提醒触发状态
                launch {
                    viewModel.goOutReminderTriggered.collect { triggered ->
                        if (triggered) {
                            showGoOutReminderDialog()
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
                // ==================== 核心修复：点击连接按钮时强制重置BLE状态 ====================
                // 问题：蓝牙断开后底层GATT可能未完全释放，导致 connectGatt() 僵死
                // 表现：点击连接无反应，搜索页显示0设备，需强杀APP
                // 修复：每次手动点击连接时，先强制清理所有BLE资源，再发起新连接
                Log.d("HomeFragment", "点击连接按钮，强制重置BLE状态")
                viewModel.forceResetBleAndConnect()
                // =============================================================================
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

    private fun showPhoneAlarmDialog() {
        alarmDialog?.dismiss()
        val deviceName = viewModel.connectedDeviceName.value
            ?: viewModel.device.value?.name
            ?: "防丢器"

        val message = SpannableString("按下防丢器按钮两次可以停止报警")
        message.setSpan(
            AbsoluteSizeSpan(24, true),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 修复：报警弹窗文字红色加粗
        message.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        message.setSpan(
            android.text.style.ForegroundColorSpan(requireContext().getColor(android.R.color.holo_red_dark)),
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

    // 显示出门提醒弹窗
    private fun showGoOutReminderDialog() {
        // 如果报警弹窗正在显示，不显示出门提醒弹窗（报警优先级更高）
        if (alarmDialog?.isShowing == true) {
            Log.d("HomeFragment", "报警弹窗正在显示，跳过出门提醒弹窗")
            return
        }

        goOutReminderDialog?.dismiss()

        val message = SpannableString("主人，主人，检查一下，你的钥匙带没带？")
        message.setSpan(
            AbsoluteSizeSpan(24, true),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 修复：提醒弹窗文字蓝色加粗
        message.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        message.setSpan(
            android.text.style.ForegroundColorSpan(requireContext().getColor(android.R.color.holo_blue_dark)),
            0,
            message.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        goOutReminderDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("出门提醒")
            .setMessage(message)
            .setPositiveButton("好的") { _, _ ->
                viewModel.stopGoOutReminder()
                dismissGoOutReminderDialog()
            }
            .setCancelable(false)
            .show()

        Log.d("HomeFragment", "出门提醒弹窗已显示")
    }

    private fun dismissGoOutReminderDialog() {
        goOutReminderDialog?.dismiss()
        goOutReminderDialog = null
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
        dismissGoOutReminderDialog()
        viewModel.stopPhoneAlarm()
        viewModel.stopGoOutReminder()

        try {
            requireContext().unregisterReceiver(goOutReminderReceiver)
            Log.d("HomeFragment", "Go out reminder receiver unregistered")
        } catch (e: Exception) {
            Log.e("HomeFragment", "Unregister receiver failed", e)
        }

        super.onDestroyView()
        _binding = null
    }
}