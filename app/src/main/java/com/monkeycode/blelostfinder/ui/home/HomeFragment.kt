package com.monkeycode.blelostfinder.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
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

        // 1. 手动连接按钮（核心功能，必须保留）
        binding.btnManualConnect.setOnClickListener {
            bleManager.connect(BleManager.I_DEVICE_MAC).launchIn(lifecycleScope)
            Toast.makeText(requireContext(), "正在尝试连接设备...", Toast.LENGTH_SHORT).show()
        }

        // 2. 搜索设备按钮（暂时不跳转，先保留提示，避免报错）
        binding.btnSearchDevice.setOnClickListener {
            Toast.makeText(requireContext(), "请先在扫描页配对设备", Toast.LENGTH_SHORT).show()
        }

        // 3. 其他原有逻辑（保留框架，不报错）
        setupClickListeners()
        setupObservers()
    }

    private fun setupObservers() {
        // 你的原有代码（原样保留即可）
    }

    private fun setupClickListeners() {
        // 点击报警按钮（你的原有逻辑）
        binding.btnAlarmDevice.setOnClickListener {
            // 你的原有报警逻辑
        }

        // 开启监控开关（你的原有逻辑）
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            // 你的原有监控逻辑
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}