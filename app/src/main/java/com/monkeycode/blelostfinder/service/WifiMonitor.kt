package com.monkeycode.blelostfinder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 状态监听器
 * 结合 NetworkCallback + WiFi 广播 + 定时轮询，确保可靠检测 WiFi 断开
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
    private var lastKnownWifiConnected = false

    // WiFi 断开事件——使用 SharedFlow 确保每次都能触发
    private val _wifiDisconnectedEvent = MutableSharedFlow<HomeWifiInfo>(extraBufferCapacity = 1)
    val wifiDisconnectedEvent: SharedFlow<HomeWifiInfo> = _wifiDisconnectedEvent.asSharedFlow()

    private var isRegistered = false
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private var pollHandler: Handler? = null
    private var pollRunnable: Runnable? = null

    data class HomeWifiInfo(
        val ssid: String
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

            // 方案1：注册默认网络回调
            registerDefaultNetworkCallback()

            // 方案2：注册WiFi网络专用回调
            registerWifiNetworkCallback()

            // 方案3：注册 WiFi 状态广播
            registerWifiStateReceiver()

            // 方案4：启动定时轮询（每5秒检查一次WiFi状态）
            startPolling()

            isRegistered = true
            Log.d(TAG, "WiFi monitor registered, current WiFi: ssid=$lastKnownSsid, connected=$lastKnownWifiConnected")
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
            defaultNetworkCallback?.let {
                try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) { Log.e(TAG, "unregister default callback failed", e) }
                defaultNetworkCallback = null
            }

            wifiNetworkCallback?.let {
                try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) { Log.e(TAG, "unregister wifi callback failed", e) }
                wifiNetworkCallback = null
            }

            wifiStateReceiver?.let {
                try { context.unregisterReceiver(it) } catch (e: Exception) { Log.e(TAG, "unregister receiver failed", e) }
                wifiStateReceiver = null
            }

            pollRunnable?.let {
                pollHandler?.removeCallbacks(it)
                pollRunnable = null
            }
            pollHandler = null

            isRegistered = false
            lastKnownSsid = null
            lastKnownWifiConnected = false
            Log.d(TAG, "WiFi monitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister WiFi monitor", e)
        }
    }

    /**
     * 注册默认网络回调
     */
    private fun registerDefaultNetworkCallback() {
        defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "DefaultNetworkCallback: onAvailable")
                // 延迟检查，等待WiFi信息更新
                Handler(Looper.getMainLooper()).postDelayed({
                    updateCurrentWifiInfo()
                }, 1000)
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
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.d(TAG, "DefaultNetworkCallback: WiFi capabilities changed")
                    updateCurrentWifiInfo()
                }
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback!!)
            Log.d(TAG, "Default network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register default network callback", e)
        }
    }

    /**
     * 注册WiFi网络专用回调
     * 使用NetworkRequest专门监听WiFi网络，不依赖默认网络
     */
    private fun registerWifiNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "WifiNetworkCallback: onAvailable")
                // WiFi网络可用，更新信息
                Handler(Looper.getMainLooper()).postDelayed({
                    updateCurrentWifiInfo()
                }, 1000)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "WifiNetworkCallback: onLost")
                // WiFi网络丢失，强制触发断开
                handleWifiLost("WifiNetworkCallback")
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.d(TAG, "WifiNetworkCallback: onUnavailable")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, wifiNetworkCallback!!)
            Log.d(TAG, "WiFi network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register WiFi network callback", e)
        }
    }

    /**
     * 注册 WiFi 状态广播接收器
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
                                Handler(Looper.getMainLooper()).postDelayed({
                                    updateCurrentWifiInfo()
                                }, 2000)
                            }
                        }
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        Log.d(TAG, "Network state changed: ${networkInfo?.state}, connected=${networkInfo?.isConnected}")
                        if (networkInfo?.isConnected == true) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                updateCurrentWifiInfo()
                            }, 1000)
                        } else if (networkInfo?.state == android.net.NetworkInfo.State.DISCONNECTED) {
                            handleWifiLost("NetworkStateBroadcast")
                        }
                    }
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val supplicantState = intent.getParcelableExtra<android.net.wifi.SupplicantState>(WifiManager.EXTRA_NEW_STATE)
                        val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                        Log.d(TAG, "Supplicant state: $supplicantState, error=$error")
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
     * 启动定时轮询
     * 每5秒检查一次WiFi状态，确保状态正确
     */
    private fun startPolling() {
        pollHandler = Handler(Looper.getMainLooper())
        pollRunnable = object : Runnable {
            override fun run() {
                try {
                    val previousState = lastKnownWifiConnected
                    val previousSsid = lastKnownSsid

                    updateCurrentWifiInfo()

                    // 如果之前连接但现在断开，触发事件
                    if (previousState && !lastKnownWifiConnected) {
                        if (previousSsid != null) {
                            Log.d(TAG, "Poll detected WiFi disconnect: SSID=$previousSsid")
                            _wifiDisconnectedEvent.tryEmit(HomeWifiInfo(previousSsid))
                        }
                    }

                    // 如果之前断开但现在连接，更新状态
                    if (!previousState && lastKnownWifiConnected) {
                        Log.d(TAG, "Poll detected WiFi connect: SSID=$lastKnownSsid")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }

                pollHandler?.postDelayed(this, 5000)
            }
        }
        pollHandler?.postDelayed(pollRunnable!!, 5000)
        Log.d(TAG, "WiFi polling started")
    }

    /**
     * 处理 WiFi 断开
     * 修复：即使lastKnownWifiConnected为false，如果lastKnownSsid不为null，也emit事件
     */
    private fun handleWifiLost(source: String) {
        val disconnectedSsid = lastKnownSsid
        val wasWifiConnected = lastKnownWifiConnected

        // 重置状态
        lastKnownWifiConnected = false
        lastKnownSsid = null

        // 修复：只要有SSID，就emit事件，不强制要求wasWifiConnected
        if (disconnectedSsid != null) {
            Log.d(TAG, "WiFi disconnected from $source: SSID=$disconnectedSsid, wasConnected=$wasWifiConnected")
            _wifiDisconnectedEvent.tryEmit(HomeWifiInfo(disconnectedSsid))
        } else {
            Log.d(TAG, "WiFi lost from $source but no valid SSID, ignoring")
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

                lastKnownSsid = newSsid
                lastKnownWifiConnected = true

                Log.d(TAG, "WiFi info updated: SSID=$newSsid, BSSID=$bssid")
            } else {
                Log.d(TAG, "Not connected to valid WiFi: ssid=$ssid, bssid=$bssid")
                // 如果之前连接但现在无效，可能是断开了
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
     * 检查是否是家庭 WiFi（仅匹配 SSID）
     */
    fun isHomeWifi(ssid: String?, homeSsid: String): Boolean {
        if (ssid == null) return false
        return normalizeSsid(ssid) == normalizeSsid(homeSsid)
    }

    /**
     * 获取当前 WiFi 连接状态
     */
    fun isCurrentlyConnectedToWifi(): Boolean {
        return lastKnownWifiConnected
    }
}