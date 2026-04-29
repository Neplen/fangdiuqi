package com.monkeycode.blelostfinder.ui.home

import android.os.Bundle
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
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionState(state)
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
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_scan)
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
    
    private fun showPhoneAlarmDialog() {
        // 如果已有弹窗，先关闭
        alarmDialog?.dismiss()
        
        val deviceName = viewModel.device.value?.name ?: "iTAG"
        
        alarmDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("[$deviceName] 正在寻找您的手机")
            .setMessage("按下防丢器按钮两次可以停止报警")
            .setPositiveButton("好的") { _, _ ->
                // 强制停止所有铃声
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
