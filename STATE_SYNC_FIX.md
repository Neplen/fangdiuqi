# 状态同步和重连协调问题修复

## 问题现象汇总

### 测试结果分析

| 场景 | 断连后超过 40 秒回到范围 | 结果 | 根因 |
|------|----------------------|------|------|
| **APP 前台**断连 | 显示"已断开" → 无法重连 | ❌ | init 只执行一次 |
| **锁屏**断连 | 自动重连成功 | ✅ | Service 正常工作 |
| **其他 APP**断连 | 手机持续报警 → 无法重连 | ❌ | Flow 不被收集 |
| **其他 APP+ 锁屏** | 手机不报警 → 重连成功 | ⚠️ | 后台音频限制 |

---

## 根因分析

### 问题 1：init 只执行一次

```kotlin
// HomeViewModel.kt
class HomeViewModel @Inject constructor(...) : AndroidViewModel(application) {
    init {
        loadDevice()
        observeBleState()
        observeBleEvents()
        connectToDevice()  // ← 只在第一次创建时执行
    }
}
```

**问题流程**：
```
1. 首次打开 APP
   → HomeViewModel 创建
   → init 执行
   → connectToDevice()
   → GATT 连接成功 ✅

2. 用户走出范围，断连
   → connectionState = Disconnected
   → HomeViewModel 收到通知

3. 用户在 APP 前台等待
   → connectToDevice() 不会再次执行 ❌
   → 因为 init 只执行一次

4. 用户回到范围
   → 没有重连请求
   → 一直显示"已断开" ❌

5. 用户强杀 APP
   → HomeViewModel 被销毁
   → 重新打开 APP
   → init 再次执行
   → connectToDevice()
   → 连接成功 "连接中" → "已连接" ✅
```

### 问题 2：HomeViewModel 和 BleMonitorService 重复监听

```kotlin
// HomeViewModel 也在监听
private fun observeBleState() {
    viewModelScope.launch {
        bleManager.connectionState.collect { state ->
            _connectionState.value = state
            // 连接成功时立即停止报警
            if (state is BleConnectionState.Connected) {
                if (isPhoneAlarmPlaying) {
                    stopPhoneAlarm()  // ← 停止！
                }
            }
        }
    }
}

// BleMonitorService 也在监听
serviceScope.launch {
    bleManager.connectionState.collect { state ->
        handleConnectionState(state)
    }
}
```

**状态混乱**：
```
时间线：
1. 断连 → both 收到 Disconnected
2. BleMonitorService 调用 connectDirectly()
3. 3 秒后，GATT 重连成功
4. BleManager._connectionState.value = Connected
5. HomeViewModel 的 collect 收到 Connected → 立即停止报警
6. BleMonitorService 的 collect 也收到 Connected → 也停止报警

问题：
- UI 可能还显示"已断开"（因为 collect 被挂起）
- 但报警已经停止了
→ 用户看到"已断开"，但没有报警声
```

### 问题 3：后台 Flow 不被收集

```kotlin
// HomeViewModel 修复前
fun connectToDevice() {
    viewModelScope.launch {
        bleManager.connect(BleManager.I_DEVICE_MAC).collect { state ->
            // channelFlow 只有在被 collect 时才执行！
        }
    }
}

// 后台场景：
用户切换到其他 APP
    → HomeViewModel 的 lifecycleScope 可能被挂起
    → collect 停止
    → channelFlow 不执行
    → 连接逻辑被跳过 ❌
```

### 问题 4：UI 状态不同步

```
实际状态：GATT 已连接 ✅
UI 显示："已断开" ❌

原因：
- HomeViewModel 的 observeBleState() collect 被挂起
- 连接状态更新时，collect 没有执行
- UI 仍显示旧的 Disconnected 状态
```

---

## 修复方案

### 修复 1：connectToDevice() 改用 connectDirectly()

```kotlin
// HomeViewModel.kt - 修复后
fun connectToDevice() {
    try {
        // 使用直接连接方法，不依赖 channelFlow
        // 这样即使 ViewModel 被挂起，连接逻辑也能执行
        bleManager.connectDirectly(BleManager.I_DEVICE_MAC)
        Log.d("HomeViewModel", "已调用 connectDirectly()")
    } catch (e: Exception) {
        Log.e("HomeViewModel", "连接失败：${e.message}", e)
    }
}
```

**优点**：
- ✅ 不依赖 Flow 收集
- ✅ 立即执行
- ✅ 后台场景也能工作

### 修复 2：移除 HomeViewModel 自动停止报警

```kotlin
// HomeViewModel.kt - 修复后
private fun observeBleState() {
    viewModelScope.launch {
        bleManager.connectionState.collect { state: BleConnectionState ->
            _connectionState.value = state
            // 注意：不再在连接成功时自动停止报警
            // 报警由用户手动停止，或通过双击防丢器停止
            Log.d("HomeViewModel", "连接状态更新：${state::class.simpleName}")
        }
    }
}
```

**为什么移除**：
- 避免状态不同步导致用户困惑
- 让 BleMonitorService 统一管理报警逻辑
- 用户手动停止或通过双击停止（更可控）

### 修复 3：HomeFragment.onResume() 检查重连

```kotlin
// HomeFragment.kt - 新增
override fun onResume() {
    super.onResume()
    // 核心修复：每次返回页面时检查连接状态
    // 如果断开，立即触发重连
    val currentState = viewModel.connectionState.value
    if (currentState is BleConnectionState.Disconnected) {
        Log.d("HomeFragment", "onResume: 检测到断连，触发重连")
        viewModel.connectToDevice()
    }
}
```

**工作流程**：
```
用户打开 APP（从后台返回）
    → HomeFragment.onResume()
    → 检查当前连接状态
    → 如果是 Disconnected
    → 调用 viewModel.connectToDevice()
    → connectDirectly() 立即执行
    → GATT 重连 ✅

UI 更新：
    → "已断开" → "连接中" → "已连接"
    → 用户看到正确的状态同步
```

### 修复 4：BleMonitorService 延迟 1 秒停止报警

```kotlin
// BleMonitorService.kt - 修复后
is BleConnectionState.Connected -> {
    Log.d(TAG, "Connected to device，连接成功")
    alarmTriggerTime = null
    deviceAlarmRetriggerTime = null
    
    // 核心修复：连接成功时，只有 RSSI 正常才停止报警
    // 避免刚连接就停止，导致用户认为没连接
    kotlinx.coroutines.delay(1000) // 等待 1 秒确认连接稳定
    val currentRssi = bleManager.rssi.value
    if (currentRssi > -100) {  // RSSI 有效
        if (isAlarmPlaying) {
            stopAlarmIfPlaying()
            Log.d(TAG, "设备已重连且 RSSI 正常 ($currentRssi dBm)，停止所有报警")
        }
    } else {
        Log.d(TAG, "设备已连接但 RSSI 无效 ($currentRssi dBm)，继续报警")
    }
    
    currentDevice?.let { device ->
        deviceRepository.updateDevice(device.copy(
            lastConnectedTime = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        ))
    }
}
```

**为什么延迟**：
- 确保 GATT 连接稳定
- 等待 RSSI 读取成功
- 避免"连接瞬间就停止"导致用户困惑

---

## 修复后的完整流程

### 场景 1：APP 前台断连

```
1. 用户在 APP 界面，断连
   ├── connectionState = Disconnected
   ├── BleMonitorService 收到 → 触发报警 + connectDirectly()
   ├── HomeViewModel 收到 → 更新 UI 为"已断开"
   └── HomeFragment onCreateView → setupObservers

2. 用户保持在前台，等待
   ├── RSSI 监控每 3 秒重连
   └── 持续报警

3. 回到蓝牙范围
   ├── 重连成功，connectionState = Connected
   ├── BleMonitorService 收到 → 延迟 1 秒，检查 RSSI → 停止报警
   ├── HomeViewModel 收到 → 更新 UI 为"已连接"
   └── HomeFragment.collect → 更新按钮状态

4. 用户看到：
   ✅ "已断开" → "连接中"（短暂） → "已连接"
   ✅ 报警自动停止
```

### 场景 2：后台断连

```
1. 用户在其他 APP，断连
   ├── connectionState = Disconnected
   ├── BleMonitorService 收到 → 触发报警 + connectDirectly() ✅
   └── HomeViewModel 可能被挂起 ❌

2. 用户回到范围
   ├── 重连成功
   ├── BleMonitorService 收到 → 停止报警
   └── HomeViewModel collect 可能没收到 ❌

3. 用户返回 APP
   ├── HomeFragment.onResume()
   ├── 检查 currentState
   ├── 如果是 Connected → 不操作 ✅
   └── 如果还是 Disconnected（状态不同步）→ 调用 connectToDevice() ✅

4. UI 更新：
   ✅ 显示"已连接"
   ✅ 如果还在报警，用户手动停止
```

### 场景 3：后台断连 + 超过 40 秒

```
1. 后台断连 → 超过 40 秒 → 防丢器停止
   ├── BleMonitorService 每 3 秒重连
   └── 手机持续报警

2. 用户返回 APP（仍在范围外）
   ├── HomeFragment.onResume()
   ├── 检测到 Disconnected
   ├── 调用 connectToDevice()
   └── 重连请求发出

3. 用户回到范围
   ├── 重连成功
   ├── 停止报警
   └── UI 显示"已连接"
```

---

## 代码修改详情

### 修改 1：HomeViewModel.kt

```kotlin
// 修改前
fun connectToDevice() {
    viewModelScope.launch {
        bleManager.connect(BleManager.I_DEVICE_MAC).collect { state -> }
    }
}

private fun observeBleState() {
    bleManager.connectionState.collect { state ->
        _connectionState.value = state
        if (state is BleConnectionState.Connected) {
            stopPhoneAlarm()  // ❌ 自动停止
        }
    }
}

// 修改后
fun connectToDevice() {
    // ✅ 直接调用，不依赖 Flow
    bleManager.connectDirectly(BleManager.I_DEVICE_MAC)
}

private fun observeBleState() {
    bleManager.connectionState.collect { state ->
        _connectionState.value = state
        // ✅ 移除自动停止逻辑
        Log.d("HomeViewModel", "连接状态更新：${state::class.simpleName}")
    }
}
```

### 修改 2：HomeFragment.kt

```kotlin
// 新增
override fun onResume() {
    super.onResume()
    val currentState = viewModel.connectionState.value
    if (currentState is BleConnectionState.Disconnected) {
        Log.d("HomeFragment", "onResume: 检测到断连，触发重连")
        viewModel.connectToDevice()
    }
}
```

### 修改 3：BleMonitorService.kt

```kotlin
is BleConnectionState.Connected -> {
    // 延迟 1 秒，等待 RSSI
    kotlinx.coroutines.delay(1000)
    val currentRssi = bleManager.rssi.value
    if (currentRssi > -100) {
        if (isAlarmPlaying) {
            stopAlarmIfPlaying()
        }
    }
}
```

---

## 测试步骤

### 测试 1：APP 前台断连重连

1. **在 APP 界面**，走出范围
   - 预期：防丢器和手机都报警
   
2. **等待超过 40 秒**
   - 预期：防丢器停止（固件限制）
   - 预期：手机持续报警
   
3. **回到范围**
   - 预期：3 秒内自动重连
   - 预期：延迟 1 秒后停止报警
   - 预期：UI 显示"已连接"

### 测试 2：后台断连重连（核心场景）

1. **打开其他 APP**，防丢器在后台
   
2. **走出范围，等待 40 秒以上**
   - 预期：手机持续报警
   
3. **回到范围**
   - 预期：自动重连
   - 预期：报警自动停止
   
4. **返回防丢器 APP**
   - 预期：显示"已连接"

### 测试 3：后台断连 + 用户手动返回

1. **后台断连**，保持 40 秒以上
   
2. **回到范围**
   - 预期：自动重连
   
3. **立即返回 APP**
   - 预期：显示"已连接"或"连接中"
   - 预期：如果是"已断开"，会自动触发重连

---

## 提交记录

- **分支**: main
- **提交**: `93fddf1 fix: 修复状态同步和重连协调问题`
- **GitHub**: https://github.com/Neplen/fangdiuqi/commit/93fddf1

---

## 待验证功能

- [ ] APP 前台断连 → 回到范围 → 自动重连 ✅
- [ ] 后台断连 → 回到范围 → 自动重连 ✅
- [ ] 锁屏断连 → 回到范围 → 自动重连 ✅
- [ ] 连接成功时报警是否正确停止（延迟 1 秒） ✅
- [ ] onResume 是否正确触发重连 ✅
