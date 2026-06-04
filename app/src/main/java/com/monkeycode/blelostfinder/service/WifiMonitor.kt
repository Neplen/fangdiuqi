package com.monkeycode.blelostfinder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 状态监听器
 * 结合 NetworkCallback + WiFi 广播，确保可靠检测 WiFi 断开
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
    private var lastKnownSsid: String? = null
    private var lastKnownBssid: String? = null
    private var lastKnownWifiConnected = false

    // WiFi 断开事件——使用 SharedFlow 确保每次都能触发
    private val _wifiDisconnectedEvent = MutableSharedFlow<HomeWifiInfo>(extraBufferCapacity = 1)
    val wifiDisconnectedEvent: SharedFlow<HomeWifiInfo> = _wifiDisconnectedEvent.asSharedFlow()

    private var isRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null

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

            // 方案1：注册默认网络回调（更可靠地检测网络切换）
            registerDefaultNetworkCallback()

            // 方案2：注册 WiFi 状态广播（作为后备，确保物理WiFi断开被检测到）
            registerWifiStateReceiver()

            isRegistered = true
            Log.d(TAG, "WiFi monitor registered, current WiFi: ssid=$lastKnownSsid, bssid=$lastKnownBssid")
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
            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister network callback", e)
                }
                networkCallback = null
            }

            wifiStateReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister WiFi receiver", e)
                }
                wifiStateReceiver = null
            }

            isRegistered = false
            lastKnownSsid = null
            lastKnownBssid = null
            lastKnownWifiConnected = false
            Log.d(TAG, "WiFi monitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister WiFi monitor", e)
        }
    }

    /**
     * 注册默认网络回调
     * 使用 registerDefaultNetworkCallback 而不是 registerNetworkCallback
     * 因为当WiFi断开切换到移动数据时，默认网络回调会收到 onLost
     */
    private fun registerDefaultNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "DefaultNetworkCallback: onAvailable")
                // 延迟检查，等待WiFi信息更新
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    updateCurrentWifiInfo()
                }, 500)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "DefaultNetworkCallback: onLost")
                handleWifiLost("DefaultNetworkCallback")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                // 检查是否是WiFi网络
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.d(TAG, "DefaultNetworkCallback: WiFi capabilities changed")
                    updateCurrentWifiInfo()
                }
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
            Log.d(TAG, "Default network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register default network callback", e)
        }
    }

    /**
     * 注册 WiFi 状态广播接收器
     * 作为后备方案，当 NetworkCallback 不可靠时使用
     */
    private fun registerWifiStateReceiver() {
        wifiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                        Log.d(TAG, "WiFi state changed: $wifiState")
                        when (wifiState) {
                            WifiManager.WIFI_STATE_DISABLED -> {
                                Log.d(TAG, "WiFi disabled")
                                handleWifiLost("WiFiStateBroadcast")
                            }
                            WifiManager.WIFI_STATE_ENABLED -> {
                                Log.d(TAG, "WiFi enabled")
                                // 延迟更新，等待连接建立
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    updateCurrentWifiInfo()
                                }, 1000)
                            }
                        }
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        Log.d(TAG, "Network state changed: ${networkInfo?.state}, connected=${networkInfo?.isConnected}")
                        if (networkInfo?.isConnected == true) {
                            // 已连接，更新信息
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                updateCurrentWifiInfo()
                            }, 500)
                        } else if (networkInfo?.state == android.net.NetworkInfo.State.DISCONNECTED) {
                            // 已断开
                            handleWifiLost("NetworkStateBroadcast")
                        }
                    }
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        val supplicantState = intent.getParcelableExtra<android.net.wifi.SupplicantState>(WifiManager.EXTRA_NEW_STATE)
                        val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                        Log.d(TAG, "Supplicant state: $supplicantState, error=$error")
                        if (error == WifiManager.ERROR_AUTHENTICATING) {
                            Log.d(TAG, "WiFi authentication error")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }

        try {
            context.registerReceiver(wifiStateReceiver, filter)
            Log.d(TAG, "WiFi state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register WiFi state receiver", e)
        }
    }

    /**
     * 处理 WiFi 断开
     */
    private fun handleWifiLost(source: String) {
        val disconnectedSsid = lastKnownSsid
        val disconnectedBssid = lastKnownBssid
        val wasWifiConnected = lastKnownWifiConnected

        // 重置状态
        lastKnownWifiConnected = false
        lastKnownSsid = null
        lastKnownBssid = null

        if (wasWifiConnected && disconnectedSsid != null && disconnectedBssid != null) {
            Log.d(TAG, "WiFi disconnected from $source: SSID=$disconnectedSsid, BSSID=$disconnectedBssid")
            _wifiDisconnectedEvent.tryEmit(HomeWifiInfo(disconnectedSsid, disconnectedBssid))
        } else {
            Log.d(TAG, "WiFi lost from $source but no valid last known info, ignoring")
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
                // 如果之前有连接记录但现在无效了，可能是断开了
                if (lastKnownWifiConnected) {
                    handleWifiLost("UpdateCurrentWifiInfo")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi info", e)
            if (lastKnownWifiConnected) {
                handleWifiLost("UpdateCurrentWifiInfoException")
            }
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