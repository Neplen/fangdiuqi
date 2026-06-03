package com.monkeycode.blelostfinder.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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

    // 保存当前连接的 WiFi 信息（断开前最后已知）
    private var lastKnownSsid: String? = null
    private var lastKnownBssid: String? = null
    private var lastKnownWifiConnected = false

    // WiFi 断开事件（携带断开前的 WiFi 信息）
    private val _wifiDisconnectedEvent = MutableStateFlow<HomeWifiInfo?>(null)
    val wifiDisconnectedEvent: StateFlow<HomeWifiInfo?> = _wifiDisconnectedEvent.asStateFlow()

    private var isRegistered = false

    data class HomeWifiInfo(
        val ssid: String,
        val bssid: String
    )

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

            // 使用 NetworkRequest 专门监听 WiFi 网络
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback)
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
            lastKnownSsid = null
            lastKnownBssid = null
            lastKnownWifiConnected = false
            Log.d(TAG, "WiFi monitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister WiFi monitor", e)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // WiFi 网络可用，更新当前信息
            Log.d(TAG, "WiFi network available")
            updateCurrentWifiInfo()
        }

        override fun onLost(network: Network) {
            // WiFi 网络丢失
            Log.d(TAG, "WiFi network lost")

            // 使用最后已知的 WiFi 信息（在 onLost 前通过 onCapabilitiesChanged 或 onAvailable 更新的）
            val disconnectedSsid = lastKnownSsid
            val disconnectedBssid = lastKnownBssid
            val wasWifiConnected = lastKnownWifiConnected

            // 重置当前状态
            lastKnownWifiConnected = false
            lastKnownSsid = null
            lastKnownBssid = null

            if (wasWifiConnected && disconnectedSsid != null && disconnectedBssid != null) {
                Log.d(TAG, "WiFi disconnected: SSID=$disconnectedSsid, BSSID=$disconnectedBssid")
                _wifiDisconnectedEvent.value = HomeWifiInfo(disconnectedSsid, disconnectedBssid)
            } else {
                Log.d(TAG, "WiFi lost but no valid last known info, ignoring")
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            // 网络能力变化，更新 WiFi 信息
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                updateCurrentWifiInfo()
            }
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

                lastKnownSsid = newSsid
                lastKnownBssid = newBssid
                lastKnownWifiConnected = true

                Log.d(TAG, "WiFi info updated: SSID=$newSsid, BSSID=$newBssid")
            } else {
                Log.d(TAG, "Not connected to valid WiFi: ssid=$ssid, bssid=$bssid")
                lastKnownWifiConnected = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi info", e)
            lastKnownWifiConnected = false
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
     * 标准化 BSSID（转大写，去除分隔符差异）
     */
    private fun normalizeBssid(bssid: String): String {
        return bssid.uppercase().replace(":", "")
    }

    /**
     * 检查是否是家庭 WiFi（使用标准化后的值比较）
     */
    fun isHomeWifi(ssid: String?, bssid: String?, homeSsid: String, homeBssid: String): Boolean {
        if (ssid == null || bssid == null) return false
        return normalizeSsid(ssid) == normalizeSsid(homeSsid) &&
               normalizeBssid(bssid) == normalizeBssid(homeBssid)
    }

    /**
     * 获取当前 WiFi 连接状态
     */
    fun isCurrentlyConnectedToWifi(): Boolean {
        return lastKnownWifiConnected
    }
}