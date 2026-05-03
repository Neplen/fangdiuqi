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

        // ====================== 我只加这一段 ======================
        // 手动连接按钮（你要的功能）
        binding.btnManualConnect.setOnClickListener {
            bleManager.connect(BleManager.I_DEVICE_MAC).launchIn(lifecycleScope)
            Toast.makeText(requireContext(), "正在连接...", Toast.LENGTH_SHORT).show()
        }
        // ==========================================================

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        // 完全保持你原版原样
    }

    private fun setupClickListeners() {
        // 搜索设备（原版代码，100%能进扫描页）
        binding.btnSearchDevice.setOnClickListener {
            findNavController().navigate(R.id.action_navigation_home_to_scanFragment)
        }

        // 报警按钮（原版）
        binding.btnAlarmDevice.setOnClickListener {
        }

        // 监控开关（原版）
        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}