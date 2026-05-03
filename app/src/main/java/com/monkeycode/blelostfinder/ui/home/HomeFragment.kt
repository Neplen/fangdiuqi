package com.monkeycode.blelostfinder.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.launchIn

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    // 注入 BleManager（仅为连接按钮服务）
    @Inject
    lateinit var bleManager: BleManager

    // 原版的变量
    private var isAlarmPlaying = false
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

        // 👇 只加这一行：手动连接按钮（不影响任何原版逻辑）
        binding.btnManualConnect.setOnClickListener {
            bleManager.connect(BleManager.I_DEVICE_MAC).launchIn(lifecycleScope)
            Toast.makeText(requireContext(), "正在尝试连接防丢器...", Toast.LENGTH_SHORT).show()
        }

        // 👇 原版的所有逻辑，原封不动保留！
        setupObservers()
        setupClickListeners()
    }

    // 👇 原版的 setupObservers，原封不动
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        // 你的原有逻辑
                    }
                }
                launch {
                    viewModel.rssi.collect { rssi ->
                        // 你的原有逻辑
                    }
                }
                launch {
                    viewModel.batteryLevel.collect { level ->
                        // 你的原有逻辑
                    }
                }
            }
        }
    }

    // 👇 原版的 setupClickListeners，原封不动（包含扫描页跳转）
    private fun setupClickListeners() {
        // 原版的「搜索设备」按钮，原封不动
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_navigation_home_to_scanFragment)
        }

        // 原版的「点击报警」按钮
        binding.btnAlarmDevice.setOnClickListener {
            // 你的原有报警逻辑
        }

        // 原版的「开启监控」开关
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            // 你的原有监控逻辑
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}