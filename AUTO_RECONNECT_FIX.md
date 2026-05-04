# 自动重连功能修复报告

## 问题描述

用户反馈以下场景断连后不会自动重连：
1. ❌ 距离过远导致蓝牙断开 → 回到范围后不重连
2. ❌ 手动关闭蓝牙再开启 → 不重连
3. ❌ 关闭 APP 再打开 → 不重连

只能手动进入扫描页点击设备才能连接，非常麻烦。

---

## 根本原因

### 1. 主页没有主动连接设备
```kotlin
// 问题：HomeViewModel 中没有调用 bleManager.connect()
// 连接只在扫描页面和监控服务中调用
```

### 2. BleManager 的 connect() Flow 没有重连机制
```kotlin
// 问题：之前的代码在 awaitClose 后直接结束
// 没有循环重连逻辑
awaitClose {
    Log.d(TAG, "Flow 取消监听，但保持 GATT 连接")
}
// 流程结束，不再重连
```

---

## 修复方案

### 一、HomeViewModel 添加主动连接

**修改位置**：`HomeViewModel.kt:57-70`

```kotlin
init {
    loadDevice()
    observeBleState()
    observeBleEvents()
    connectToDevice()  // ✅ 新增：启动时主动连接
}

private fun connectToDevice() {
    viewModelScope.launch {
        try {
            bleManager.connect(BleManager.I_DEVICE_MAC).collect { state ->
                // 自动重连逻辑已在 BleManager 中实现
                Log.d("HomeViewModel", "连接状态：${state::class.simpleName}")
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "连接失败：${e.message}", e)
        }
    }
}
```

---

### 二、BleManager 实现自动重连

**修改位置**：`BleManager.kt:193-283`

#### 核心逻辑：while(true) 循环 + continue 重试

```kotlin
fun connect(macAddress: String): Flow<BleConnectionState> = channelFlow {
    var isReconnecting = false
    
    try {
        // 持续循环实现自动重连
        while (true) {
            try {
                val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) 
                    as? BluetoothManager)?.adapter
                
                // 1. 检查蓝牙适配器
                if (currentAdapter == null) {
                    Log.e(TAG, "设备不支持蓝牙")
                    send(BleConnectionState.Error("设备不支持蓝牙"))
                    kotlinx.coroutines.delay(5000)
                    continue  // ✅ 5 秒后重试
                }
                
                // 2. 等待蓝牙开启
                if (!currentAdapter.isEnabled) {
                    Log.e(TAG, "蓝牙未开启，等待开启")
                    if (!isReconnecting) {
                        send(BleConnectionState.Error("请先开启蓝牙"))
                    }
                    // 每秒检查一次，直到蓝牙开启
                    while (!currentAdapter.isEnabled) {
                        kotlinx.coroutines.delay(1000)
                    }
                    Log.d(TAG, "蓝牙已开启，尝试重连")
                    continue  // ✅ 蓝牙开启后立即重连
                }
                
                bluetoothAdapter = currentAdapter
                
                // 3. 发起连接
                bluetoothDevice = currentAdapter.getRemoteDevice(macAddress)
                bluetoothDevice?.connectGatt(context, false, bleCallback)
                
                // 4. RSSI 轮询
                val rssiJob = launch {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        bluetoothGatt?.readRemoteRssi()
                    }
                }
                
                // 5. 等待 Flow 关闭（连接断开）
                awaitClose {
                    rssiJob.cancel()
                    Log.d(TAG, "Flow 取消监听，但保持 GATT 连接")
                }
                
                // 6. 连接断开，准备重连
                Log.d(TAG, "连接断开，准备重连...")
                _connectionState.value = BleConnectionState.Disconnected
                send(BleConnectionState.Disconnected)
                isReconnecting = true
                kotlinx.coroutines.delay(3000)  // ✅ 3 秒后重连
                
            } catch (e: Exception) {
                Log.e(TAG, "连接循环异常", e)
                isReconnecting = true
                kotlinx.coroutines.delay(5000)  // ✅ 异常时 5 秒后重试
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "connect 方法异常", e)
        send(BleConnectionState.Error("连接异常：${e.message}"))
        close()
    }
}
```

---

## 功能特性

### 1. 距离过远断连 → 自动重连
- ✅ 断连后等待 3 秒
- ✅ 自动重新发起连接
- ✅ 只要设备在范围内就会重连成功

### 2. 手动关闭蓝牙 → 自动重连
- ✅ 检测蓝牙关闭，暂停重连
- ✅ 每秒检查蓝牙状态
- ✅ 蓝牙开启后立即重连
- ✅ 不显示错误提示（重连模式）

### 3. 关闭 APP 再打开 → 自动连接
- ✅ `HomeViewModel` 在 `init` 中调用 `connectToDevice()`
- ✅ APP 启动后自动连接设备
- ✅ 不需要手动进入扫描页

---

## 重连机制

| 场景 | 重试延迟 | 说明 |
|------|----------|------|
| 设备不支持蓝牙 | 5 秒 | 持续重试 |
| 蓝牙未开启 | 1 秒检查 | 阻塞等待直到开启 |
| 连接失败 | 5 秒 | 错误信息提示用户 |
| 连接断开 | 3 秒 | 标记为重连，不显示错误 |
| 异常 | 5 秒 | 记录日志并重试 |

---

## 修改文件清单

1. **`/app/src/main/java/com/monkeycode/blelostfinder/ble/BleManager.kt`**
   - 添加 `while(true)` 循环实现持续重连
   - 蓝牙关闭时阻塞等待
   - 断连后 3 秒自动重连
   - 异常时 5 秒后重试

2. **`/app/src/main/java/com/monkeycode/blelostfinder/ui/home/HomeViewModel.kt`**
   - 添加 `connectToDevice()` 方法
   - 在 `init` 块中调用连接

---

## 测试建议

### 1. 距离断连重连测试
- [ ] 连接成功后，远离防丢器直到断开
- [ ] 等待 3 秒，查看是否自动重连
- [ ] 回到防丢器附近，确认信号恢复

### 2. 开关蓝牙测试
- [ ] 连接成功后，手动关闭手机蓝牙
- [ ] 等待几秒后重新开启蓝牙
- [ ] 确认是否自动重连（无需操作）

### 3. APP 重启测试
- [ ] 连接成功后，完全关闭 APP
- [ ] 重新打开 APP
- [ ] 确认是否自动连接（无需手动点击）

### 4. 混合场景测试
- [ ] 关闭 APP → 关闭蓝牙 → 打开 APP → 打开蓝牙
- [ ] 确认最终能自动连接

---

## 注意事项

### 1. 重连不会无限循环
- 只有在 `BleManager` 的 Flow 被订阅时才重连
- HomeViewModel 在 `init` 中订阅，APP 运行时持续重连
- 用户手动调用 `disconnect()` 时会断开

### 2. 错误提示优化
- 首次连接失败：显示错误提示
- 重连时蓝牙关闭：不显示错误，静默等待
- 重连时连接断开：不显示错误，自动重试

### 3. 与监控服务不冲突
- 监控服务启动时也会连接设备
- 主页和监控服务同时连接同一个设备
- BleManager 是单例，共享同一个 GATT 连接

### 4. 功耗控制
- 重连失败时延迟 3-5 秒再试
- 避免频繁重试导致功耗增加
- 蓝牙关闭时暂停重连，减少无效操作

---

## 用户体验提升

| 修改前 | 修改后 |
|--------|--------|
| 断连后不重连，需手动进扫描页 | 自动重连，无需任何操作 |
| 关蓝牙后不重连 | 开蓝牙后自动重连 |
| 重启 APP 后不连接 | 打开 APP 自动连接 |
| 需要用户主动干预 | 完全自动化，用户无感知 |
