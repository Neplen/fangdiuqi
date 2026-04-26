package com.monkeycode.blelostfinder.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.monkeycode.blelostfinder.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startMonitorService()
        } else {
            showSnackbar("需要开启蓝牙才能使用此功能")
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
    
    private fun setupNavigation() {
        val navController = findNavController(R.id.nav_host_fragment)
        binding.bottomNavigation.setupWithNavController(navController)
    }
    
    private fun checkPermissions() {
        if (!PermissionHelper.checkAllPermissions(this)) {
            PermissionHelper.requestPermissions(this) { allGranted ->
                if (!allGranted) {
                    showSnackbar("部分权限未授予，某些功能可能无法使用")
                }
            }
        } else {
            checkBluetoothAndStart()
        }
    }
    
    private fun checkBluetoothAndStart() {
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
    }
    
    private fun startMonitorService() {
        val serviceIntent = Intent(this, BleMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
    
    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
