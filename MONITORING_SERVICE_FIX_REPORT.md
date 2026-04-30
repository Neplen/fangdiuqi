# 监控服务修复报告

本次修复解决了 Android BLE 蓝牙防丢 APP 的三个核心问题：

## 一、修复内容

### 1. 监控开关状态同步 ✅

**问题**：每次进入主页时开关显示默认关闭状态，而实际上后台服务可能在运行。

**解决方案**：

1. **BleMonitorService.kt** - 添加服务运行状态暴露：
   ```kotlin
   companion object {
       private val _isRunning = MutableStateFlow(false)
       val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
   }
   
   override fun onCreate() {
       _isRunning.value = true
   }
   
   override fun onDestroy() {
       _isRunning.value = false
   }
   ```

2. **HomeViewModel.kt** - 暴露服务状态给 UI：
   ```kotlin
   val isMonitoringRunning = isRunning
   
   fun toggleMonitor(isChecked: Boolean) {
       if (isChecked) startMonitoring() else stopMonitoring()
   }
   ```

3. **HomeFragment.kt** - 监听状态并更新开关：
   ```kotlin
   viewModel.isMonitoringRunning.collect { isRunning ->
       updateMonitorSwitch(isRunning)
   }
   
   private fun updateMonitorSwitch(isRunning: Boolean) {
       if (binding.switchMonitor.isChecked != isRunning) {
           binding.switchMonitor.isChecked = isRunning
       }
   }
   ```

**效果**：
- 进入主页时自动同步服务真实状态
- 服务启动/停止时实时更新开关 UI
- 防止"服务在跑但开关显示关闭"的情况

---

### 2. RSSI 断连报警逻辑 ✅

**问题**：手机在后台监控时，当蓝牙断连或 RSSI 低于阈值时不触发报警。

**解决方案**：

在 **BleMonitorService.kt** 中添加 RSSI 持续监控逻辑：

```kotlin
private fun startRssiMonitoring() {
    rssiMonitorJob = serviceScope.launch {
        while (isMonitoring) {
            delay(2000)
            
            val currentRssi = bleManager.rssi.value
            Log.d(TAG, "当前 RSSI: $currentRssi dBm, 阈值：$currentRssiThreshold dBm")
            
            if (currentRssi < currentRssiThreshold && currentRssi != -100) {
                // RSSI 低于阈值
                if (alarmTriggerTime == null) {
                    alarmTriggerTime = System.currentTimeMillis()
                    Log.d(TAG, "RSSI 低于阈值，开始计时")
                } else {
                    val elapsedSeconds = (System.currentTimeMillis() - alarmTriggerTime!!) / 1000
                    
                    if (elapsedSeconds >= currentAlarmDelay) {
                        // 超过延迟时间，触发报警
                        Log.d(TAG, "RSSI 低于阈值超过 ${currentAlarmDelay}秒，触发断连报警")
                        triggerPhoneAlarm("断连报警 (RSSI=$currentRssi)")
                    }
                }
            } else {
                // RSSI 恢复正常
                alarmTriggerTime = null
                if (isAlarmPlaying) {
                    stopAlarmIfPlaying()
                }
            }
        }
    }
}
```

**报警触发条件**：
1. RSSI < 设定阈值（默认 -90dBm）
2. 持续时间超过报警延迟（默认 60 秒）
3. WiFi 勿扰模式未开启

**效果**：
- 每 2 秒读取一次 RSSI
- 累计低于阈值的时间，超过设定延迟才触发
- RSSI 恢复正常后自动重置计时器

---

### 3. 设置实时生效 ✅

**问题**：RSSI 阈值和报警延迟修改后，后台服务无法实时读取新值。

**解决方案**：

在 **BleMonitorService.kt** 中添加设置监听：

```kotlin
// RSSI 和报警延迟监听（实时更新）
serviceScope.launch {
    kotlin.coroutines.coroutineScope {
        launch {
            deviceRepository.getDeviceByMacFlow(deviceMac)
                .onEach { device ->
                    device?.let {
                        currentRssiThreshold = it.rssiThreshold
                        currentAlarmDelay = it.alarmDelaySeconds
                        Log.d(TAG, "设置更新：RSSI 阈值=$currentRssiThreshold, 延迟=$currentAlarmDelay 秒")
                    }
                }
                .launchIn(this)
        }
        
        launch {
            settingsManager.isWifiDndEnabled.collect { enabled ->
                isWifiDndActive = enabled && isWifiConnected()
            }
        }
    }
}
```

**效果**：
- RSSI 阈值修改后立即生效
- 报警延迟修改后立即生效
- WiFi 勿扰开关立即生效
- 无需重启服务

---

### 4. 后台服务保活机制 ✅

**新增功能**：

- **电池优化检查**：在 MainActivity 中检查并提示用户关闭电池优化
  ```kotlin
  private fun checkBatteryOptimization() {
      val powerManager = getSystemService(POWER_SERVICE) as PowerManager
      val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
      
      if (!isIgnoring) {
          showSnackbar("建议关闭电池优化以确保后台监控稳定运行")
      }
  }
  ```

- **前台服务通知**：已配置 `foregroundServiceType="connectedDevice"`

- **WakeLock 和 WifiLock**：服务启动时获取锁，防止 CPU 休眠

---

## 二、修改文件清单

### 核心功能文件
- `/app/src/main/java/com/monkeycode/blelostfinder/service/BleMonitorService.kt`
  - 添加服务运行状态 Flow
  - 实现 `startRssiMonitoring()` 方法
  - 添加设备设置监听逻辑
  - 修复导入语句顺序

- `/app/src/main/java/com/monkeycode/blelostfinder/ui/home/HomeViewModel.kt`
  - 添加 `isMonitoringRunning` 状态暴露
  - 导入 `isRunning` Flow

- `/app/src/main/java/com/monkeycode/blelostfinder/ui/home/HomeFragment.kt`
  - 添加 `updateMonitorSwitch()` 方法
  - 监听服务状态并更新开关 UI

- `/app/src/main/java/com/monkeycode/blelostfinder/ui/MainActivity.kt`
  - 添加 `checkBatteryOptimization()` 方法
  - 在权限检查后提示关闭电池优化

---

## 三、测试建议

### 1. 开关状态同步测试
- [ ] 启动服务后，进入主页检查开关是否显示为开启状态
- [ ] 在设置页关闭开关，回到主页检查是否显示为关闭
- [ ] 杀死 APP 后重启，检查开关状态是否与服务同步

### 2. RSSI 断连报警测试
- [ ] 设置 RSSI 阈值为 -70dBm，延迟为 5 秒
- [ ] 拿着手机远离防丢器，观察 RSSI 值变化
- [ ] 当 RSSI < -70 持续 5 秒后，检查是否触发手机报警
- [ ] 回到防丢器旁边，检查报警是否停止

### 3. 设置实时生效测试
- [ ] 运行服务时，修改 RSSI 阈值
- [ ] 观察日志输出是否显示"设置更新"
- [ ] 验证新阈值是否立即生效

### 4. WiFi 勿扰模式测试
- [ ] 连接 WiFi（任意 SSID）
- [ ] 开启 WiFi 勿扰开关
- [ ] 触发断连条件，检查是否不触发报警
- [ ] 关闭 WiFi 或关闭勿扰开关，检查报警是否恢复正常

---

## 四、已知限制

1. **防丢器双击报警**：
   - 防丢器固件逻辑导致断连后防丢器会立即响铃，不受延迟设置控制
   - APP 可以在断连后发送停止响铃指令，但需要防丢器支持

2. **后台服务被系统杀死**：
   - 即使有前台服务通知，部分厂商仍可能杀死后台服务
   - 建议用户手动将 APP 加入系统白名单/受保护应用列表

3. **电池优化**：
   - 目前仅提示用户关闭电池优化，未自动申请
   - 用户需要手动在系统设置中关闭

---

## 五、后续优化建议

1. **添加通知快捷操作**：在通知栏添加"停止报警"按钮
2. **自动申请电池优化豁免**：引导用户授权
3. **增加报警强度**：使用媒体音量而非媒体播放
4. **添加报警历史记录**：记录每次断连的时间和原因
5. **优化重连策略**：断连后指数退避重连，避免频繁重试
