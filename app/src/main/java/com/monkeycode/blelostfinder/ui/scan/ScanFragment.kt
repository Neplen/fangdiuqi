package com.monkeycode.blelostfinder.ui.scan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.ble.ScanResultWrapper
import com.monkeycode.blelostfinder.databinding.FragmentScanBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScanViewModel by viewModels()

    private lateinit var deviceAdapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupObservers()
        setupClickListeners()
        
        // 处理系统返回键
        setupBackPressHandler()

        // 自动开始扫描
        viewModel.startScan()
    }
    
    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        findNavController().popBackStack()
                    } catch (e: Exception) {
                        activity?.finish()
                    }
                }
            }
        )
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { scanResult ->
            // 点击设备，开始连接
            viewModel.connectToDevice(scanResult)
            Snackbar.make(binding.root, "正在连接 ${scanResult.name}...", Snackbar.LENGTH_LONG).show()
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanResults.collect { devices ->
                        deviceAdapter.submitList(devices)
                        binding.tvStatus.text = "已发现 ${devices.size} 个设备"
                    }
                }

                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.progressScan.isIndeterminate = scanning
                        binding.btnScan.text = if (scanning) "扫描中..." else "重新扫描"
                    }
                }

                launch {
                    viewModel.connectionState.collect { state ->
                        state?.let {
                            when {
                                it.contains("成功") -> {
                                    Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                                    activity?.finish()
                                }
                                it.contains("失败") || it.contains("错误") -> {
                                    Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnScan.setOnClickListener {
            viewModel.startScan()
        }

        binding.btnBack.setOnClickListener {
            // 使用 Navigation 组件返回，而不是直接 finish
            try {
                findNavController().popBackStack()
            } catch (e: Exception) {
                // 如果 Navigation 失败，才使用 finish
                activity?.finish()
            }
        }
    }

    override fun onDestroyView() {
        try {
            super.onDestroyView()
            _binding = null
            viewModel.stopScan()
        } catch (e: Exception) {
            // 忽略清理错误
        }
    }
}

class DeviceAdapter(
    private val onItemClick: (ScanResultWrapper) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<ScanResultWrapper>()

    fun submitList(newDevices: List<ScanResultWrapper>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_device_name)
        private val tvMac: TextView = itemView.findViewById(R.id.tv_device_mac)
        private val tvRssi: TextView = itemView.findViewById(R.id.tv_device_rssi)

        fun bind(scanResult: ScanResultWrapper) {
            tvName.text = scanResult.name ?: "未知设备"
            tvMac.text = scanResult.macAddress
            tvRssi.text = "${scanResult.rssi} dBm"

            itemView.setOnClickListener {
                onItemClick(scanResult)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size
}
