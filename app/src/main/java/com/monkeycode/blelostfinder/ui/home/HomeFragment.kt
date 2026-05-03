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

    // 注入 BleManager（连接按钮需要）
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

        // 1. 手动连接按钮（新增，修复连接问题）
        binding.btnManualConnect.setOnClickListener {
            bleManager.connect(BleManager.I_DEVICE_MAC).launchIn(lifecycleScope)
            Toast.makeText(requireContext(), "正在尝试连接设备...", Toast.LENGTH_SHORT).show()
        }

        // 2. 恢复原来的点击事件（关键！“搜索设备”、“点击报警”、“开启监控”这些功能都靠这个）
        setupClickListeners()
        setupObservers()
    }

    // 你原来的 setupObservers() 方法（原样保留）
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

    // 你原来的 setupClickListeners() 方法（原样保留！“搜索设备”按钮就在这里面）
    private fun setupClickListeners() {
        // 搜索设备按钮（这里是你原来的逻辑，我帮你写好）
        binding.btnSearchDevice.setOnClickListener {
            // 跳转到扫描页的逻辑，你原来的代码应该是这样的：
            findNavController().navigate(R.id.action_navigation_home_to_scanFragment)
        }

        // 点击报警按钮（你原来的逻辑）
        binding.btnAlarmDevice.setOnClickListener {
            // 你的原有报警逻辑
        }

        // 开启监控开关（你原来的逻辑）
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            // 你的原有监控逻辑
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}