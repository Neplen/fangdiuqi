# 自动重连功能最终修复报告

## 问题分析

之前的修复方案失败原因：
- ❌ 在 `connect()` Flow 中使用 `while(true)` 循环
- ❌ `awaitClose` 会阻塞 Flow，循环永远不会执行第二次
- ❌ 断连后无法触发重连逻辑

## 正确的解决方案

### 核心思路
**在 GATT 回调中检测断连 → 延迟 3 秒 → 自动调用 `connectGatt` 重连**

---

## 修改内容

### 一、BleManager.kt

#### 1. 添加设备地址缓存
```kotlin
// 保存要连接的设备地址，用于断连后重连
private var deviceMacToConnect: String? = null
```

#### 2. 修改 `connect()` 方法
```kotlin
fun connect(macAddress: String): Flow<BleConnectionState> = channelFlow {
    try {
        // 保存设备地址用于重连
        deviceMacToConnect = macAddress
        
        // ... 初始化蓝牙适配器 ...
        
        // 发起连接
        bluetoothDevice?.connectGatt(context, false, bleCallback)
        
        // RSSI 轮询
        launch {
            while (true) {
                delay(1000)
                bluetoothGatt?.readRemoteRssi()
            }
        }
        
        awaitClose {
            Log.d(TAG, "Flow 取消监听")
        }
    } catch (e: Exception) {
        // ... 错误处理 ...
    }
}
```

#### 3. 在 `onConnectionStateChange` 中实现自动重连
```kotlin
private val bleCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                _connectionState.value = BleConnectionState.Connected
                bluetoothGatt = gatt
                gatt.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.d(TAG, "设备已断开，准备自动重连...")
                _connectionState.value = BleConnectionState.Disconnected
                bluetoothGatt = null
                
                // 自动重连逻辑：3 秒后尝试重连
                deviceMacToConnect?.let { mac ->
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            kotlinx.coroutines.delay(3000)
                            Log.d(TAG, "开始自动重连设备：$mac")
                            
                            val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) 
                                as? BluetoothManager)?.adapter
                            
                            if (currentAdapter != null && currentAdapter.isEnabled) {
                                bluetoothAdapter = currentAdapter
                                val device = currentAdapter.getRemoteDevice(mac)
                                device.connectGatt(context, false, bleCallback)
                                Log.d(TAG, "自动重连 GATT 已发起")
                            } else {
                                Log.e(TAG, "蓝牙未开启，等待开启后重连")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "自动重连失败", e)
                        }
                    }
                }
            }
        }
    }
}
```

#### 4. 添加手动重连方法
```kotlin
/**
 * 蓝牙关闭后重新开启时，自动重连设备
 */
fun reconnectIfDisconnected() {
    if (bluetoothGatt == null && deviceMacToConnect != null) {
        Log.d(TAG, "检测到蓝牙已开启，开始重连设备：${deviceMacToConnect}")
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) 
            as? BluetoothManager)?.adapter
        if (currentAdapter != null && currentAdapter.isEnabled) {
            try {
                bluetoothAdapter = currentAdapter
                val device = currentAdapter.getRemoteDevice(deviceMacToConnect!!)
                device.connectGatt(context, false, bleCallback)
                _connectionState.value = BleConnectionState.Connecting
                Log.d(TAG, "蓝牙重连 GATT 已发起")
            } catch (e: Exception) {
                Log.e(TAG, "蓝牙重连失败", e)
            }
        }
    }
}
```

---

### 二、MainActivity.kt

#### 1. 注入 BleManager
```kotlin
@Inject
lateinit var bleManager: BleManager
```

#### 2. 蓝牙开启时调用重连
```kotlin
private val bluetoothReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "蓝牙已开启，启动服务并尝试重连")
                    binding.root.postDelayed({
                        startMonitorService()
                        // 蓝牙开启后尝试重连设备
                        bleManager.reconnectIfDisconnected()
                    }, 1000)
                }
                // ... 其他状态处理 ...
            }
        }
    }
}
```

---

### 三、HomeViewModel.kt

保持不变，已有连接逻辑：
```kotlin
init {
    loadDevice()
    observeBleState()
    observeBleEvents()
    connectToDevice()  // 启动时连接
}
```

---

## 完整重连流程

### 场景 1：距离过远断连
```
1. 用户远离防丢器 → RSSI 降低
2. 超出蓝牙范围 → GATT 断开
3. onConnectionStateChange 收到 STATE_DISCONNECTED
4. 记录日志"设备已断开，准备自动重连..."
5. 延迟 3 秒
6. 检查蓝牙已开启
7. 调用 device.connectGatt(context, false, bleCallback)
8. 回到范围内 → 连接成功 ✅
```

### 场景 2：手动关闭蓝牙再开启
```
1. 用户关闭蓝牙 → STATE_OFF
2. 显示 Toast"蓝牙已关闭，部分功能可能无法使用"
3. 用户重新开启蓝牙 → STATE_ON
4. BroadcastReceiver 收到 STATE_ON
5. 延迟 1 秒确保适配器初始化
6. 调用 bleManager.reconnectIfDisconnected()
7. 检查 bluetoothGatt == null 且 deviceMacToConnect != null
8. 调用 device.connectGatt(context, false, bleCallback)
9. 连接成功 ✅
```

### 场景 3：重启 APP
```
1. 关闭 APP → GATT 断开
2. 重新打开 APP
3. HomeViewModel 的 init 块执行
4. 调用 connectToDevice()
5. bleManager.connect(I_DEVICE_MAC)
6. 由于 deviceMacToConnect 已保存，直接连接
7. 连接成功 ✅
```

---

## 重连保证机制

| 机制 | 说明 | 状态 |
|------|------|------|
| GATT 回调重连 | 断连后 3 秒自动调用 `connectGatt` | ✅ 已实现 |
| 蓝牙开启重连 | 蓝牙开启广播中调用 `reconnectIfDisconnected()` | ✅ 已实现 |
| APP 启动重连 | HomeViewModel 的 `init` 中调用 `connectToDevice()` | ✅ 已实现 |
| 设备地址缓存 | `deviceMacToConnect` 保存设备 MAC | ✅ 已实现 |
| 蓝牙状态检查 | 重连前检查蓝牙适配器状态 | ✅ 已实现 |
| RSSI 持续轮询 | 每秒读取一次 RSSI 监控连接状态 | ✅ 已实现 |

---

## 修改文件清单

1. **`/app/src/main/java/com/monkeycode/blelostfinder/ble/BleManager.kt`**
   - 添加 `deviceMacToConnect` 变量
   - 修改 `onConnectionStateChange` 添加断连重连逻辑
   - 添加 `reconnectIfDisconnected()` 方法
   - 简化 `connect()` 方法，去掉无效的循环

2. **`/app/src/main/java/com/monkeycode/blelostfinder/ui/MainActivity.kt`**
   - 添加 `@Inject lateinit var bleManager: BleManager`
   - 在蓝牙开启广播接收器中调用 `bleManager.reconnectIfDisconnected()`

---

## 测试建议

### 1. 距离断连重连测试（必测）
- [ ] 连接成功后，远离防丢器直到断开
- [ ] 等待 5 秒
- [ ] 回到防丢器附近
- [ ] ✅ 应该自动连接，无需手动操作

### 2. 开关蓝牙测试（必测）
- [ ] 连接成功后，手动关闭手机蓝牙
- [ ] 等待提示"蓝牙已关闭"
- [ ] 重新开启手机蓝牙
- [ ] 等待 3-5 秒
- [ ] ✅ 应该自动连接，无需手动操作

### 3. APP 重启测试（必测）
- [ ] 连接成功后，完全关闭 APP（从后台划掉）
- [ ] 重新打开 APP
- [ ] ✅ 应该自动连接，无需进入扫描页

### 4. 混合场景测试
- [ ] 关闭 APP → 关闭蓝牙 → 打开 APP → 打开蓝牙
- [ ] ✅ 最终能自动连接

---

## 预期结果

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| 距离过远断连 | ❌ 需手动进扫描页连接 | ✅ 3 秒后自动重连 |
| 关蓝牙再开 | ❌ 需手动进扫描页连接 | ✅ 开启后自动重连 |
| 重启 APP | ❌ 需手动进扫描页连接 | ✅ 启动时自动连接 |

**用户体验提升：完全自动化，用户无需感知连接逻辑**
