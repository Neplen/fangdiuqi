# 锁屏状态下无法自动重连问题修复

## 问题描述

用户反馈的真实使用场景：
- ❌ 手机锁屏放进包里，与防丢器断连后，回到范围内不能自动连接
- ❌ 手机在用其他软件时，防丢器断连后，回到范围内不能自动连接
- ❌ 需要打开 APP 才能真正恢复连接，非常麻烦

**核心痛点**：忘带东西时，手机一般都是锁屏状态，不会正在用防丢器 APP。

---

## 根因分析

### 当前架构的问题

```
正常工作时（APP 在前台）：
HomeFragment → HomeViewModel → BleManager.connect()
                    ↓
            监听连接状态变化
                    ↓
            断连时触发重连

锁屏或后台运行时：
HomeFragment 可能被销毁 ❌
HomeViewModel 可能被系统回收 ❌
BleMonitorService 在后台运行 ✅
    ↓
但没有触发重连的时机 ❌
```

### 问题详细分析

#### 1. 重连触发依赖 ViewModel

```kotlin
// MainActivity.kt - 蓝牙开启时调用
bleManager.reconnectIfDisconnected()

// HomeViewModel.kt - init 块中调用
init {
    connectToDevice()  // 间接调用 BleManager.connect()
}
```

**问题**：
- 锁屏时，HomeViewModel 可能被系统回收
- 后台运行时，HomeViewModel 不活跃
- 没有主动触发重连的入口

#### 2. BleMonitorService 没有重连逻辑

```kotlin
// BleMonitorService.kt - 修复前
private fun startRssiMonitoring() {
    while (isMonitoring) {
        val currentRssi = bleManager.rssi.value
        
        // 只处理 RSSI 高低，不检查连接状态
        // 即使 RSSI = -100（表示未连接），也不触发重连
    }
}
```

**问题**：
- Service 在后台持续运行 ✅
- 每秒读取 RSSI ✅
- 但检测到 RSSI = -100（未连接）时，不触发重连 ❌

---

## 解决方案

### 方案 1：增强 BleManager.reconnectIfDisconnected()

```kotlin
// BleManager.kt - 修复后
fun reconnectIfDisconnected() {
    val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) 
        as? BluetoothManager)?.adapter
    
    // 只要蓝牙适配器可用且有保存的设备地址，就尝试重连
    if (deviceMacToConnect != null && currentAdapter != null && currentAdapter.isEnabled) {
        // 不再检查 bluetoothGatt == null
        // 因为即使有旧的 GATT 对象，也可能已经失效
        
        val device = currentAdapter.getRemoteDevice(deviceMacToConnect!!)
        device.connectGatt(context, false, bleCallback)
        _connectionState.value = BleConnectionState.Connecting
        Log.d(TAG, "蓝牙重连 GATT 已发起：${deviceMacToConnect}")
    }
}
```

**改进点**：
- 移除 `bluetoothGatt == null` 检查
- 即使有旧 GATT 对象，也可以发起新连接
- 蓝牙回调会自动处理旧连接的清理

### 方案 2：BleMonitorService 定期触发重连

```kotlin
// BleMonitorService.kt - 修复后
private fun startRssiMonitoring() {
    var reconnectAttemptCount = 0
    
    while (isMonitoring) {
        kotlinx.coroutines.delay(1000)
        
        val connectionState = bleManager.connectionState.value
        val currentRssi = bleManager.rssi.value
        
        // 检测到断开状态
        if (connectionState is BleConnectionState.Disconnected && currentRssi == -100) {
            reconnectAttemptCount++
            
            // 每 5 秒尝试一次重连（避免过于频繁）
            if (reconnectAttemptCount % 5 == 0) {
                Log.d(TAG, "检测到设备断开且未连接，尝试重连...")
                bleManager.reconnectIfDisconnected()
            }
        } else {
            // 有连接，重置计数器
            reconnectAttemptCount = 0
            
            // 正常的 RSSI 监控逻辑...
        }
    }
}
```

**优点**：
- Service 在后台持续运行，不受锁屏影响
- 每秒检查连接状态
- 每 5 秒自动尝试重连一次
- 回到蓝牙范围后，最快 1 秒内检测到，5 秒内发起重连

### 方案 3：异常时也触发重连

```kotlin
while (isMonitoring) {
    try {
        // RSSI 监控逻辑...
    } catch (e: Exception) {
        Log.e(TAG, "RSSI 监控异常", e)
        // 异常时也尝试重连，提高鲁棒性
        bleManager.reconnectIfDisconnected()
    }
}
```

**优点**：
- 网络波动、蓝牙驱动异常等情况下，自动尝试恢复

---

## 锁屏保活机制

### Android 系统限制

锁屏后，系统可能会：
1. 限制后台服务的 CPU 使用
2. 暂停网络/WiFi
3. 进入 Doze 模式

### 我们的保活措施

#### 1. WakeLock（CPU 唤醒锁）

```kotlin
private fun acquireWakeLock() {
    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK, 
        "BleLostFinder::MonitorWakeLock"
    ).apply {
        acquire(10 * 60 * 1000L)  // 10 分钟超时
    }
}
```

**作用**：保持 CPU 运行，防止系统休眠

#### 2. WifiLock（WiFi 锁）

```kotlin
wifiLock = wifiManager?.createWifiLock(
    WifiManager.WIFI_MODE_FULL,
    "BleLostFinder::WifiLock"
)?.apply {
    acquire()
}
```

**作用**：保持 WiFi 模块活跃（辅助定位）

#### 3. 前台服务（Foreground Service）

```kotlin
startForeground(NOTIFICATION_ID, createNotification())
```

**作用**：
- 通知栏显示"正在监控设备"
- 系统优先保证前台服务的资源
- 用户知道 APP 在后台运行

#### 4. 忽略电池优化

**需要用户手动授予**：
- 进入设置页 → 点击"电池优化"按钮
- 选择"所有应用" → 找到"Ble 防丢器"
- 选择"不优化"

**作用**：防止系统杀死后台服务

---

## 工作流程

### 修复后的完整流程

```
场景：手机锁屏放进包里，与防丢器断连

1. 断连时刻
   ├── BleManager.onConnectionStateChange() 检测到断开
   ├── 保存 deviceMacToConnect
   ├── 发送 BleEvent.Disconnected 事件（触发报警）
   └── 3 秒后尝试重连（可能失败，因为超出范围）

2. 锁屏状态
   ├── HomeFragment 可能被销毁 ❌
   ├── HomeViewModel 可能被回收 ❌
   └── BleMonitorService 继续运行 ✅

3. 后台监控循环（每秒执行）
   ├── 读取 currentRssi
   ├── 读取 connectionState
   ├── 检测到 Disconnected + RSSI = -100
   └── reconnectAttemptCount++

4. 每 5 秒触发一次重连
   ├── reconnectAttemptCount % 5 == 0
   ├── 调用 bleManager.reconnectIfDisconnected()
   └── 检查蓝牙适配器和设备地址

5. 回到蓝牙范围
   ├── RSSI 监控检测到信号（如 -85 dBm）
   ├── connectionState 变为 Connected
   ├── reconnectAttemptCount 重置为 0
   ├── 停止报警（如果正在报警）
   └── 更新 UI 状态

6. 用户解锁手机
   ├── 看到通知栏"正在监控设备"
   ├── 打开 APP，显示"已连接"
   └── 报警已自动停止
```

---

## 测试步骤

### 测试 1：锁屏断连重连

1. **启动 APP**，确保设备已连接
2. **开启监控服务**（打开开关）
3. **锁屏**，把手机放进包里
4. **离开蓝牙范围**（走到室外）
   - 预期：防丢器和手机都报警
5. **保持锁屏**，回到蓝牙范围
   - 预期：5 秒内自动重连
   - 预期：报警自动停止
6. **解锁手机**，打开 APP
   - 预期：显示"已连接"
   - 预期：没有报警弹窗

### 测试 2：后台运行断连重连

1. **启动 APP**，确保设备已连接
2. **开启监控服务**
3. **按 Home 键**，回到桌面
4. **打开其他 APP**（如微信、抖音）
5. **离开蓝牙范围**
   - 预期：防丢器报警，手机可能报警（如果后台服务正常）
6. **回到蓝牙范围**
   - 预期：5 秒内自动重连
   - 预期：报警停止
7. **回到防丢器 APP**
   - 预期：显示"已连接"

### 测试 3：电池优化影响

1. **关闭电池优化**（设置页 → 电池优化 → 不优化）
2. 重复测试 1 和测试 2
   - 预期：重连成功率提高

---

## 代码修改总结

### 修改文件

1. **BleManager.kt**
   - 重构 `reconnectIfDisconnected()` 方法
   - 改进日志和错误处理
   - 移除不必要的检查

2. **BleMonitorService.kt**
   - 在 `startRssiMonitoring()` 中添加重连检查
   - 每 5 秒检测一次连接状态
   - 异常时也触发重连

### 修改量

- 新增代码：76 行
- 删除代码：35 行
- 净增加：41 行

---

## 注意事项

### 用户必须手动设置

1. **忽略电池优化**（最重要）
   - 设置页 → 电池优化 → 关闭电池优化
   
2. **允许后台运行**
   - 手机设置 → 应用管理 → Ble 防丢器 → 电池 → 允许后台运行

3. **锁定后台任务**（部分机型）
   - 多任务界面 → 找到 Ble 防丢器 → 下拉锁定

### 系统限制

- **强制休眠**：部分手机（如小米、华为）有自己的省电策略，可能杀死后台服务
- **锁屏断网**：部分手机锁屏后会关闭 WiFi，影响重连速度
- **建议**：在 README 中添加各品牌的特殊设置指南

---

## 提交记录

- **分支**: main
- **提交**: `7f0f756 fix: 修复锁屏状态下无法自动重连的问题`
- **GitHub**: https://github.com/Neplen/fangdiuqi/commit/7f0f756

---

## 待办事项

### 可选优化
- [ ] 在锁屏通知中显示连接状态
- [ ] 添加重连尝试次数统计
- [ ] 重连失败时发送通知提醒用户
- [ ] 适配各品牌手机的特殊权限设置

### 品牌特殊设置指南
- [ ] 小米：自启动管理 + 锁屏显示
- [ ] 华为：电池优化 + 启动管理
- [ ] OPPO：权限隐私 + 自启动
- [ ] vivo：权限管理 + 后台高耗电
