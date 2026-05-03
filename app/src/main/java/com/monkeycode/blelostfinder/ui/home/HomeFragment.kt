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

    @Inject
    lateinit var bleManager: BleManager

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

        // 手动连接按钮（你要的功能）
        binding.btnManualConnect.setOnClickListener {
            bleManager.connect(BleManager.I_DEVICE_MAC).launchIn(lifecycleScope)
            Toast.makeText(context, "正在连接...", Toast.LENGTH_SHORT).show()
        }

        // 下面完全保留你原版的代码！！！
        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        // 这里是空的，保持你原版原样，不添加任何代码
    }

    private fun setupClickListeners() {
        // 搜索设备 → 绝对原版跳转，能进扫描页！
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_navigation_home_to_scanFragment)
        }

        // 报警按钮
        binding.btnAlarmDevice.setOnClickListener {
            // 原版原样
        }

        // 监控开关
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            // 原版原样
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}