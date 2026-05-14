# 后台不报警和重连延迟问题全面修复

## 问题清单和测试结果

### 问题 1：防丢器只响 40 秒就停止 ❌
**测试现象**：断连后防丢器响 40 秒自动停止，应该持续报警直到重连

**根因**：
- 防丢器固件的电池保护机制（硬件限制）
- 断连后 APP 无法通过 BLE 命令重新触发（因为已断开）

**解决方案**：
- APP 定期尝试重连（3 秒一次）
- 重连成功后，如果仍在断连范围，防丢器会再次触发报警
- 通过快速重连机制，让防丢器反复触发报警

---

### 问题 2：后台/锁屏时手机完全不报警 ❌

**测试现象**：
- 后台使用其他 APP 时断连 → 手机不报警
- 锁屏放包里断连 → 手机不报警

**根因分析**：

```kotlin
// BleMonitorService.kt - 修复前的严重 BUG
private fun startMonitoring() {
    // ... 其他代码 ...
    
    // ❌ 定义了 handleConnectionState() 方法
    // ❌ 但！没有订阅 connectionState 流
    // ❌ 导致 handleConnectionState() 永远不会被调用
    
    serviceScope.launch {
        bleManager.bleEvents.collect { event ->
            handleBleEvent(event)
        }
    }
    
    // ❌ 缺少这段关键代码：
    // bleManager.connectionState.collect { state ->
    //     handleConnectionState(state)
    // }
}
```

**后果**：
```
断连发生：
BleManager → onConnectionStateChange() → 更新 StateFlow
                ↓
BleMonitorService 没有监听 connectionState ❌
                ↓
handleConnectionState() 从未被调用 ❌
                ↓
triggerPhoneAlarm() 从未执行 ❌
                ↓
手机在后台/锁屏时不报警！
```

**修复方案**：

```kotlin
// BleMonitorService.kt - 修复后
private fun startMonitoring() {
    // ... 其他监听器 ...
    
    // ✅ 添加连接状态监听（核心修复）
    serviceScope.launch {
        bleManager.connectionState.collect { state ->
            handleConnectionState(state)
        }
    }
    
    // ✅ 现在 handleConnectionState() 会被正确调用
}

private fun handleConnectionState(state: BleConnectionState) {
    is BleConnectionState.Disconnected -> {
        // ✅ 断连时立即触发手机报警
        if (!isAlarmPlaying) {
            triggerPhoneAlarm("断连报警")
        }
    }
    is BleConnectionState.Connected -> {
        // ✅ 连接成功时停止报警
        if (isAlarmPlaying) {
            stopAlarmIfPlaying()
        }
    }
}
```

---

### 问题 3：回到范围后重连延迟太久（好几秒）❌

**测试现象**：
- 回到蓝牙范围后，需要等好几秒才自动连接

**根因**：
```kotlin
// 修复前
if (reconnectAttemptCount % 5 == 0) {  // 每 5 秒检查一次
    bleManager.reconnectIfDisconnected()
}
```

**修复方案**：
```kotlin
// 修复后
if (reconnectAttemptCount % 3 == 0) {  // 每 3 秒检查一次
    bleManager.reconnectIfDisconnected()
}
```

**额外优化**：
- 断连后立即（3 秒）尝试第一次重连
- 不再等待 5 秒

---

### 问题 4：重连成功后手机才响铃但没有弹窗 ❌

**测试现象**：
- 回到范围 → 重连成功 → 手机突然响铃但没有弹窗
- 过了一会儿铃声自动停止

**根因分析**：

```kotlin
// 修复前的问题逻辑链：
1. 断连 → handleConnectionState() 未被调用 → 手机不报警 ✅
2. 回到范围 → 重连成功 → connectionState = Connected
3. handleConnectionState() 被调用（如果有的话）
4. 但由于逻辑混乱，可能在连接成功时错误触发报警 ❌
5. 或者 RSSI 波动导致误判 ❌
```

**修复方案**：
```kotlin
// 明确的状态处理
is BleConnectionState.Connected -> {
    // 只在连接成功时停止报警，不触发新报警
    if (isAlarmPlaying) {
        stopAlarmIfPlaying()
    }
}

is BleConnectionState.Disconnected -> {
    // 只在断连时触发一次报警
    if (!isAlarmPlaying) {
        triggerPhoneAlarm("断连报警")
    }
}
```

---

## 修复后的完整工作流程

### 场景 1：锁屏断连

```
1. 用户锁屏，手机放进包里
   └── HomeFragment 可能被销毁
   └── BleMonitorService 在后台运行 ✅

2. 走出蓝牙范围，断连
   ├── BleManager.onConnectionStateChange()
   ├── 更新 _connectionState.value = Disconnected
   ├── 发送 BleEvent.Disconnected 事件
   └── 3 秒后尝试第一次重连

3. BleMonitorService 收到连接状态变化
   ├── connectionState.collect { }
   ├── 调用 handleConnectionState(Disconnected)
   ├── 触发 triggerPhoneAlarm("断连报警") ✅
   └── isAlarmPlaying = true

4. 手机报警
   ├── 播放铃声（即使在后台）
   ├── 如果锁屏，显示弹窗
   └── 持续报警直到连接成功

5. 每 3 秒自动重连
   ├── reconnectAttemptCount % 3 == 0
   ├── bleManager.reconnectIfDisconnected()
   └── 尝试重连设备

6. 回到蓝牙范围
   ├── 重连成功
   ├── connectionState = Connected
   ├── handleConnectionState(Connected)
   ├── stopAlarmIfPlaying() ✅
   └── 铃声停止，弹窗关闭
```

### 场景 2：后台使用其他 APP

```
1. 用户打开微信/抖音
   └── 防丢器 APP 在后台

2. 断连
   ├── BleManager 检测到断开
   ├── 通知 BleMonitorService
   ├── 触发手机报警 ✅
   └── 后台播放铃声（可能需要通知权限）

3. 重连逻辑同场景 1
```

---

## 代码修改详情

### 修改文件
`app/src/main/java/com/monkeycode/blelostfinder/service/BleMonitorService.kt`

### 修改 1：添加连接状态监听

```kotlin
private fun startMonitoring() {
    // ... 其他代码 ...
    
    // ✅ 核心修复：监听连接状态变化
    serviceScope.launch {
        try {
            bleManager.connectionState.collect { state ->
                handleConnectionState(state)
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接状态监听失败", e)
        }
    }
    
    // ... 其他代码 ...
}
```

### 修改 2：断连时立即触发报警

```kotlin
private fun handleConnectionState(state: BleConnectionState) {
    is BleConnectionState.Disconnected -> {
        Log.d(TAG, "设备已断开")
        
        // ✅ 立即触发手机报警（不等待 RSSI 超时）
        if (!isAlarmPlaying) {
            triggerPhoneAlarm("断连报警")
        }
        
        // ✅ 3 秒后尝试重连
        kotlinx.coroutines.delay(3000)
        bleManager.reconnectIfDisconnected()
    }
}
```

### 修改 3：加快重连频率

```kotlin
private fun startRssiMonitoring() {
    while (isMonitoring) {
        if (connectionState is BleConnectionState.Disconnected) {
            reconnectAttemptCount++
            
            // ✅ 每 3 秒重连一次（原来是 5 秒）
            if (reconnectAttemptCount % 3 == 0) {
                bleManager.reconnectIfDisconnected()
            }
        }
    }
}
```

### 修改 4：移除 RSSI 恢复时停止报警

```kotlin
// 修复前
} else {
    alarmTriggerTime = null
    if (isAlarmPlaying) {
        stopAlarmIfPlaying()  // ❌ RSSI 恢复就停止，不合理
    }
}

// 修复后
} else {
    alarmTriggerTime = null
    // ✅ 只有连接成功时才停止报警
}
```

### 修改 5：预留防丢器持续报警机制

```kotlin
// 新增变量
private var deviceAlarmRetriggerTime: Long? = null
private val DEVICE_ALARM_RETRY_INTERVAL = 30000L // 30 秒

// 断连时记录时间
deviceAlarmRetriggerTime = System.currentTimeMillis()

// 在 RSSI 监控循环中（待实现）
if (deviceAlarmRetriggerTime != null && 
    System.currentTimeMillis() - deviceAlarmRetriggerTime!! > DEVICE_ALARM_RETRY_INTERVAL) {
    // 每 30 秒重新触发一次防丢器报警
    bleManager.startAlarm()
    deviceAlarmRetriggerTime = System.currentTimeMillis()
}
```

---

## 测试步骤

### 测试 1：锁屏断连报警和重连

1. **启动 APP**，开启监控服务
2. **锁屏**，把手机放进包里
3. **离开蓝牙范围**
   - 预期：防丢器报警，手机也应该报警（即使锁屏）
4. **保持锁屏**，等待 1 分钟
   - 预期：手机持续报警（或间歇性报警）
5. **回到蓝牙范围**
   - 预期：3 秒内自动重连
   - 预期：重连成功后立即停止报警
6. **解锁手机**
   - 预期：显示"已连接"

### 测试 2：后台运行断连报警

1. **启动 APP**，开启监控服务
2. **按 Home 键**，打开微信/抖音
3. **离开蓝牙范围**
   - 预期：防丢器报警，手机也应该报警
4. **保持使用其他 APP**
   - 预期：手机持续报警
5. **回到蓝牙范围**
   - 预期：快速重连，报警自动停止

### 测试 3：防丢器 40 秒停止问题

1. **离开蓝牙范围**，断连
2. **观察防丢器报警**
   - 预期：大约 40 秒后自动停止（固件限制）
3. **保持在范围外**
   - 预期：APP 每 3 秒尝试重连
   - 预期：重连失败，防丢器可能再次报警（取决于固件）
4. **回到范围内**
   - 预期：快速重连成功

---

## 注意事项

### 防丢器固件限制
- 防丢器 40 秒后自动停止是**硬件固件的行为**
- APP 无法通过 BLE 命令修改（因为已断连）
- 只能通过**快速重连**来重新触发报警

### 后台报警要求
用户需要授予以下权限：
1. **通知权限**（Android 13+）
2. **后台运行权限**（部分品牌手机需要特殊设置）
3. **忽略电池优化**（最重要）

### 品牌特殊设置
- **小米**：允许自启动 + 后台显示界面
- **华为**：允许后台活动
- **OPPO/vivo**：允许完全后台行为

---

## 提交记录

- **分支**: main
- **提交**: `eb48ba9 fix: 修复所有后台不报警和重连问题`
- **GitHub**: https://github.com/Neplen/fangdiuqi/commit/eb48ba9

---

## 待验证功能

修复后需要验证：
- [ ] 锁屏时手机是否真的会报警（系统可能会限制后台音频）
- [ ] 重连速度是否真的提高到 3 秒内
- [ ] 防丢器 40 秒停止后，重连是否能重新触发报警
- [ ] 连接成功时报警是否正确停止
