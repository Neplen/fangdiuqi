package com.monkeycode.blelostfinder.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.monkeycode.blelostfinder.R
import com.monkeycode.blelostfinder.databinding.ActivityMainBinding
import com.monkeycode.blelostfinder.service.BleMonitorService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BLELostFinder"
    }
    
    private lateinit var binding: ActivityMainBinding
    
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "用户同意开启蓝牙，等待 1 秒后启动服务")
                // 延迟启动，给系统时间初始化蓝牙适配器
                binding.root.postDelayed({
                    try {
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        if (adapter != null && adapter.isEnabled) {
                            Log.d(TAG, "蓝牙已开启，启动监控服务")
                            startMonitorService()
                        } else {
                            Log.e(TAG, "蓝牙开启检查失败")
                            showSnackbar("蓝牙开启失败，请重试")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "蓝牙状态检查失败", e)
                        showSnackbar("蓝牙操作失败：${e.message}")
                    }
                }, 1000)
            } else {
                Log.w(TAG, "用户拒绝开启蓝牙")
                showSnackbar("需要开启蓝牙才能使用此功能")
            }
        } catch (e: Exception) {
            Log.e(TAG, "蓝牙开启结果处理失败", e)
            showSnackbar("蓝牙操作失败：${e.message}")
        }
    }
    
    // 蓝牙状态变化广播接收器
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val action = intent?.action
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            Log.d(TAG, "蓝牙已开启，启动服务")
                            // 延迟启动，确保适配器完全初始化
                            binding.root.postDelayed({
                                try {
                                    startMonitorService()
                                } catch (e: Exception) {
                                    Log.e(TAG, "蓝牙开启后启动服务失败", e)
                                }
                            }, 1000)
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            Log.w(TAG, "蓝牙已关闭")
                            showSnackbar("蓝牙已关闭，部分功能可能无法使用")
                        }
                        BluetoothAdapter.STATE_TURNING_ON -> {
                            Log.d(TAG, "蓝牙正在开启...")
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            Log.d(TAG, "蓝牙正在关闭...")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "蓝牙状态变化处理失败", e)
            }
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        try {
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                checkBluetoothAndStart()
            } else {
                val deniedPermissions = permissions.filter { !it.value }.keys
                Log.w(TAG, "权限被拒绝：$deniedPermissions")
                showSnackbar("部分权限被拒绝，可能影响功能使用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "权限处理失败", e)
            showSnackbar("权限处理失败：${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            setupNavigation()
            
            // 注册蓝牙状态广播接收器
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(bluetoothReceiver, filter)
            Log.d(TAG, "注册蓝牙广播接收器")
            
            checkPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate 失败", e)
            showSnackbar("APP 启动失败：${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
            Log.d(TAG, "注销蓝牙广播接收器")
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
    }
    
    // 添加菜单支持
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_device -> {
                // 打开设备扫描页面
                val navController = findNavController(R.id.nav_host_fragment)
                navController.navigate(R.id.action_scan)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupNavigation() {
        val navController = findNavController(R.id.nav_host_fragment)
        binding.bottomNavigation.setupWithNavController(navController)
    }
    
    private fun checkPermissions() {
        try {
            // 第一步：先申请位置权限（BLE 扫描必需，Android 12- 也需要用于扫描）
            val locationPermissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            
            val needLocation = locationPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            if (needLocation.isNotEmpty()) {
                // 先申请位置权限
                permissionLauncher.launch(needLocation)
                return
            }
            
            // 位置权限已有，继续申请其他权限
            val allPermissions = mutableListOf<String>()
            
            // Android 12+ (API 31+) 需要新的蓝牙权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                allPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
                allPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // Android 11 及以下需要旧的蓝牙权限
                allPermissions.add(Manifest.permission.BLUETOOTH)
                allPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            
            // Android 13+ (API 33+) 需要媒体权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                allPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                // Android 12 及以下需要存储权限
                allPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                allPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            // 通知权限（Android 13+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                allPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            
            // 录音权限（所有版本）
            allPermissions.add(Manifest.permission.RECORD_AUDIO)
            
            // 过滤掉已授权的权限
            val needRequest = allPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            if (needRequest.isNotEmpty()) {
                permissionLauncher.launch(needRequest)
            } else {
                checkBluetoothAndStart()
            }
        } catch (e: Exception) {
            Log.e(TAG, "权限检查失败", e)
            showSnackbar("权限检查失败：${e.message}")
        }
    }
    
    private fun checkBluetoothAndStart() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                showSnackbar("设备不支持蓝牙")
                return
            }
            
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                bluetoothEnableLauncher.launch(enableBtIntent)
            } else {
                startMonitorService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "蓝牙检查失败", e)
            showSnackbar("蓝牙初始化失败：${e.message}")
        }
    }
    
    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(this, BleMonitorService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "启动监控服务失败", e)
            showSnackbar("启动监控服务失败：${e.message}")
        }
    }
    
    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
