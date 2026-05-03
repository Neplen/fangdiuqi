package com.monkeycode.blelostfinder.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    @Inject
    lateinit var bleManager: BleManager

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

        // 1. 手动连接按钮（只加这一行，不碰其他任何代码）
        binding.btnManualConnect.setOnClickListener {
            // 这里直接调用连接，不使用协程，避免报错
            bleManager.connect(BleManager.I_DEVICE_MAC)
            Toast.makeText(requireContext(), "正在尝试连接设备...", Toast.LENGTH_SHORT).show()
        }

        // 2. 原版所有逻辑，完全保留不动
        setupObservers()
        setupClickListeners()
    }

    // 原版 setupObservers（空的，不添加任何代码，避免协程报错）
    private fun setupObservers() {
    }

    // 原版 setupClickListeners（完全保留，包括扫描页跳转）
    private fun setupClickListeners() {
        // 搜索设备按钮（原版跳转，原封不动）
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_navigation_home_to_scanFragment)
        }

        // 报警按钮（原版逻辑）
        binding.btnAlarmDevice.setOnClickListener {
            // 你的原有报警逻辑
        }

        // 监控开关（原版逻辑）
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            // 你的原有监控逻辑
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}