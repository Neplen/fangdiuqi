package com.monkeycode.blelostfinder.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.ble.BleManager
import com.monkeycode.blelostfinder.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    // 👇 这里的导入和声明是关键
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

        // 手动连接按钮（用你原来最简单的版本，不搞状态变化，避免复杂报错）
        binding.btnManualConnect.setOnClickListener {
            bleManager.connect(BleManager.I_DEVICE_MAC).launchIn(lifecycleScope)
        }

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        // 你原来的代码
    }

    private fun setupClickListeners() {
        // 你原来的代码
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}