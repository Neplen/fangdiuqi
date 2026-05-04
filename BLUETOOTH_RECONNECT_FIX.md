# 手动关闭蓝牙后不自动重连问题修复

## 问题描述

用户反馈：APP 成功构建后，发现以下问题：
- ✅ **远距离断连后回到范围内** → 会自动连接（此功能正常）
- ❌ **手动关闭蓝牙再开启，在主页** → 不会自动连接
- ✅ **切换到设置页再回主页** → 立即连接成功

## 问题根因

### 1. HomeViewModel 生命周期问题

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(...) : AndroidViewModel(application) {
    
    init {
        loadDevice()
        observeBleState()
        observeBleEvents()
        connectToDevice()  // 👈 只在第一次创建时执行
    }
}
```

**问题**：
- HomeViewModel 使用 `@HiltViewModel` 注解，作用域绑定到 HomeFragment
- 在主页关闭/开启蓝牙时，HomeFragment **没有被销毁**
- HomeViewModel 的 `init` 块只在**首次创建**时执行 `connectToDevice()`
- 蓝牙关闭后再开启，没有触发新的连接请求

### 2. MainActivity 蓝牙广播处理不完整

```kotlin
// MainActivity.kt - 修复前
private val bluetoothReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                startMonitorService()  // 👈 只启动服务，没有重连设备
            }
        }
    }
}
```

**问题**：
- MainActivity 监听蓝牙状态变化
- 蓝牙开启时只调用 `startMonitorService()`
- **没有调用** `bleManager.reconnectIfDisconnected()`

### 3. 为什么切换页面有效

```
主页 → 设置页 → 主页 的流程：

1. 切换到设置页：
   - HomeFragment 被销毁
   - HomeViewModel 被销毁

2. 切回主页：
   - 创建新的 HomeFragment
   - 创建新的 HomeViewModel
   - 执行 init 块
   - 调用 connectToDevice() ✅
   - 重新发起 BLE 连接
```

## 解决方案

### 修改文件
- `app/src/main/java/com/monkeycode/blelostfinder/ui/MainActivity.kt`

### 1. 添加 BleManager 注入

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    @Inject
    lateinit var bleManager: BleManager
    
    // ...
}
```

### 2. 蓝牙开启时调用重连方法

```kotlin
private val bluetoothReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                binding.root.postDelayed({
                    startMonitorService()
                    bleManager.reconnectIfDisconnected()  // 👈 新增：触发重连
                }, 1000)
            }
        }
    }
}
```

### 3. BleManager.reconnectIfDisconnected() 逻辑

```kotlin
// BleManager.kt - 已有方法
fun reconnectIfDisconnected() {
    if (bluetoothGatt == null && deviceMacToConnect != null) {
        Log.d(TAG, "检测到蓝牙已开启，开始重连设备：${deviceMacToConnect}")
        val currentAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (currentAdapter != null && currentAdapter.isEnabled) {
            bluetoothAdapter = currentAdapter
            val device = currentAdapter.getRemoteDevice(deviceMacToConnect!!)
            device.connectGatt(context, false, bleCallback)
            _connectionState.value = BleConnectionState.Connecting
        }
    }
}
```

**逻辑**：
- 检查 `bluetoothGatt == null`（当前没有活跃连接）
- 检查 `deviceMacToConnect != null`（之前保存过要连接的设备地址）
- 两个条件都满足时，重新发起 GATT 连接

## 工作流程

### 修复后的完整流程

```
1. 用户在主页手动关闭蓝牙
   ├── BleMonitorService 收到 STATE_OFF 广播
   └── 显示"蓝牙已关闭"提示

2. 设备断开连接
   ├── BleManager.onConnectionStateChange() 收到 STATE_DISCONNECTED
   ├── 保存 deviceMacToConnect = "FF:FF:11:8C:4E:3B"
   └── 发送 BleEvent.Disconnected 事件（触发手机报警）

3. 用户重新开启蓝牙
   ├── MainActivity 收到 STATE_ON 广播
   ├── 延迟 1 秒等待蓝牙适配器初始化
   ├── 调用 startMonitorService()
   └── 调用 bleManager.reconnectIfDisconnected() ✅ 新增

4. BleManager 重连设备
   ├── 检查 bluetoothGatt == null ✅
   ├── 检查 deviceMacToConnect != null ✅
   ├── 获取蓝牙适配器
   ├── 获取远程设备
   └── 调用 device.connectGatt(context, false, bleCallback)

5. 连接成功
   ├── onConnectionStateChange() 收到 STATE_CONNECTED
   ├── 停止报警
   └── 更新 UI 为"已连接"
```

## 测试验证

### 测试步骤

1. **启动 APP**，确保在主页，设备已连接
2. **手动关闭蓝牙**（从系统设置或快捷开关）
   - 预期：显示"蓝牙已关闭"提示
   - 预期：设备断开，触发双向报警
3. **重新开启蓝牙**
   - 预期：1 秒后自动开始重连
   - 预期：连接成功后停止报警
   - 预期：UI 显示"已连接"
4. **验证切换页面场景**（之前的有效场景）
   - 切换到设置页 → 切回主页 → 应该也能连接成功

### 日志关键点

```
# 蓝牙关闭
D/BLELostFinder: 蓝牙已关闭

# BleManager 检测到断开
D/BleManager: 设备已断开，准备自动重连...
D/BleManager: 发送断连事件

# 蓝牙开启
D/BLELostFinder: 蓝牙已开启，启动服务并尝试重连设备
D/BLELostFinder: 已调用蓝牙重连方法

# BleManager 重连
D/BleManager: 检测到蓝牙已开启，开始重连设备：FF:FF:11:8C:4E:3B
D/BleManager: 自动重连 GATT 已发起

# 连接成功
D/BleManager: Connection state changed: 0, newState: 2
D/HomeViewModel: 设备已连接，重置报警状态
```

## 补充说明

### 为什么不修改 HomeViewModel 来监听蓝牙状态？

**方案对比**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| **在 MainActivity 中处理**（已采用） | ✅ 简单直接<br>✅ 不需要改动多个文件<br>✅ BleManager 已有重连方法 | - 需要在 Activity 中注入 BleManager |
| HomeViewModel 监听广播 | - ViewModel 不应该持有 BroadcastReceiver<br>- 需要额外的生命周期管理 | ❌ 增加复杂度 |
| BleManager 自己监听广播 | - BleManager 职责单一 | ❌ 需要重构 BleManager<br>❌ 可能需要 Application 上下文 |

**选择方案 1**：最小改动，直接有效。

### 为什么远距离断连后能自动重连？

```kotlin
// BleManager.kt - onConnectionStateChange()
BluetoothProfile.STATE_DISCONNECTED -> {
    // 设备断开（无论是远距离还是手动关闭蓝牙）
    // 都会执行自动重连逻辑（3 秒后尝试）
    deviceMacToConnect?.let { mac ->
        mainHandler.postDelayed({
            scheduleReconnect(mac)  // 直接调用重连
        }, 3000)
    }
}
```

**关键区别**：
- **远距离断连**：设备物理断开，但蓝牙适配器仍开启 → `scheduleReconnect()` 直接执行
- **手动关闭蓝牙**：适配器关闭 → `scheduleReconnect()` 失败（适配器不可用）→ 需要等适配器重新开启后才能重连

## 提交记录

- **分支**: `260504-feat-disconnect-alarm-fix`
- **提交**: `fix: 修复手动关闭蓝牙再开启后不自动重连的问题`
- **修改文件**: `MainActivity.kt`

## 相关文档

- [断连双向报警功能实现](./DISCONNECT_ALARM_FIX.md) - 上一个功能
- [自动重连最终修复](./AUTO_RECONNECT_FINAL_FIX.md) - 之前的重连优化
