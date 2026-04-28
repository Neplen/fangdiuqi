package com.monkeycode.blelostfinder.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
    
    private lateinit var binding: ActivityMainBinding
    
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                startMonitorService()
            } else {
                showSnackbar("需要开启蓝牙才能使用此功能")
            }
        } catch (e: Exception) {
            Log.e(TAG, "蓝牙开启结果处理失败", e)
            showSnackbar("蓝牙操作失败：${e.message}")
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            checkBluetoothAndStart()
        } else {
            showSnackbar("需要授予所有权限才能正常使用")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNavigation()
        checkPermissions()
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
