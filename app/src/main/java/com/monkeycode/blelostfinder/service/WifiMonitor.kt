package com.monkeycode.blelostfinder.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 状态监听器
 * 使用 ConnectivityManager.NetworkCallback 监听 WiFi 连接状态变化
 * 确保后台、锁屏状态下正常工作
 */
@Singleton
class WifiMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WifiMonitor"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    // 保存当前连接的 WiFi 信息
    private var currentWifiSsid: String? = null
    private var currentWifiBssid: String? = null
    
    // WiFi 断开事件
    private val _wifiDisconnectedEvent = MutableStateFlow<WifiInfo?>(null)
    val wifiDisconnectedEvent: StateFlow<WifiInfo?> = _wifiDisconnectedEvent.asStateFlow()
    
    private var isRegistered = false
    
    data class WifiInfo(
        val ssid: String,
        val bssid: String
    )
    
    /**
     * 获取当前连接的 WiFi 信息
     */
    fun getCurrentWifiInfo(): WifiInfo? {
        return if (currentWifiSsid != null && currentWifiBssid != null) {
            WifiInfo(currentWifiSsid!!, currentWifiBssid!!)
        } else {
            null
        }
    }
    
    /**
     * 注册 WiFi 监听
     */
    fun register() {
        if (isRegistered) {
            Log.d(TAG, "Already registered")
            return
        }
        
        try {
            // 初始获取当前 WiFi 信息
            updateCurrentWifiInfo()
            
            // 注册网络回调
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isRegistered = true
            Log.d(TAG, "WiFi monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register WiFi monitor", e)
        }
    }
    
    /**
     * 注销 WiFi 监听
     */
    fun unregister() {
        if (!isRegistered) {
            return
        }
        
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            currentWifiSsid = null
            currentWifiBssid = null
            Log.d(TAG, "WiFi monitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister WiFi monitor", e)
        }
    }
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 网络可用
        }
        
        override fun onLost(network: Network) {
            // 网络丢失，检查是否是 WiFi
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // WiFi 断开，记录断开前的 WiFi 信息
                val disconnectedSsid = currentWifiSsid
                val disconnectedBssid = currentWifiBssid
                
                // 更新当前状态为 null（已断开）
                currentWifiSsid = null
                currentWifiBssid = null
                
                if (disconnectedSsid != null && disconnectedBssid != null) {
                    Log.d(TAG, "WiFi disconnected: SSID=$disconnectedSsid, BSSID=$disconnectedBssid")
                    _wifiDisconnectedEvent.value = WifiInfo(disconnectedSsid, disconnectedBssid)
                }
            }
        }
        
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            // 网络能力变化，检查是否是 WiFi
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                updateCurrentWifiInfo()
            }
        }
        
        override fun onLosing(network: Network, maxMsToLive: Int) {
            // 网络即将断开（可选处理）
        }
    }
    
    /**
     * 更新当前 WiFi 信息
     */
    private fun updateCurrentWifiInfo() {
        try {
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid
            val bssid = wifiInfo?.bssid
            
            // 过滤无效连接
            if (ssid != null && ssid != "<unknown ssid>" && 
                bssid != null && bssid != "02:00:00:00:00:00") {
                val newSsid = normalizeSsid(ssid)
                val newBssid = normalizeBssid(bssid)
                
                if (newSsid != currentWifiSsid || newBssid != currentWifiBssid) {
                    Log.d(TAG, "WiFi connected/changed: SSID=$newSsid, BSSID=$newBssid")
                    currentWifiSsid = newSsid
                    currentWifiBssid = newBssid
                }
            } else {
                Log.d(TAG, "Not connected to WiFi or invalid info: ssid=$ssid, bssid=$bssid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi info", e)
        }
    }
    
    /**
     * 标准化 SSID（去除引号）
     */
    private fun normalizeSsid(ssid: String): String {
        return if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid.substring(1, ssid.length - 1)
        } else {
            ssid
        }
    }
    
    /**
     * 标准化 BSSID（转大写）
     */
    private fun normalizeBssid(bssid: String): String {
        return bssid.uppercase()
    }
    
    /**
     * 检查是否是家庭 WiFi
     */
    fun isHomeWifi(ssid: String?, bssid: String?, homeSsid: String, homeBssid: String): Boolean {
        if (ssid == null || bssid == null) return false
        return normalizeSsid(ssid) == normalizeSsid(homeSsid) && 
               normalizeBssid(bssid) == normalizeBssid(homeBssid)
    }
}
