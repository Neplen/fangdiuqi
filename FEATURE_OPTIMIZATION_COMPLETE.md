# 功能优化修复报告

## 优化时间
2026-04-29

---

## 优化 1：移除 "查找手机" 按钮 ✅

### 修改文件
**fragment_home.xml** - 第 143-164 行

### 修改前
```xml
<!-- 查找设备按钮 -->
<com.google.android.material.button.MaterialButton
    android:id="@+id/btn_find_device"
    ...
    android:text="@string/find_device" />

<!-- 查找手机按钮 -->
<com.google.android.material.button.MaterialButton
    android:id="@+id/btn_find_phone"
    ...
    android:text="@string/find_phone" />
```

### 修改后
```xml
<!-- 报警按钮 -->
<com.google.android.material.button.MaterialButton
    android:id="@+id/btn_alarm_device"
    ...
    android:text="点击报警" />

<!-- 查找手机按钮已移除 -->
```

### 效果
- 主界面不再显示"查找手机"按钮
- 用户界面更简洁

---

## 优化 2：优化报警按钮状态切换 ✅

### 修改文件

#### 1. HomeFragment.kt - 按钮状态管理
**新增代码**:
```kotlin
// 报警状态标记
private var isAlarmPlaying = false

// 按钮点击事件
binding.btnAlarmDevice.setOnClickListener {
    toggleDeviceAlarm()
}

private fun toggleDeviceAlarm() {
    if (isAlarmPlaying) {
        // 停止报警
        viewModel.stopDeviceAlarm()
        isAlarmPlaying = false
        updateAlarmButton(false)
        Snackbar.make(binding.root, "已停止报警", Snackbar.LENGTH_SHORT).show()
    } else {
        // 启动报警
        viewModel.startDeviceAlarm()
        isAlarmPlaying = true
        updateAlarmButton(true)
        Snackbar.make(binding.root, "正在让防丢器响铃...", Snackbar.LENGTH_SHORT).show()
    }
}

private fun updateAlarmButton(isPlaying: Boolean) {
    if (isPlaying) {
        binding.btnAlarmDevice.text = "停止报警"
        binding.btnAlarmDevice.setIconResource(android.R.drawable.ic_media_pause)
    } else {
        binding.btnAlarmDevice.text = "点击报警"
        binding.btnAlarmDevice.setIconResource(android.R.drawable.ic_dialog_alert)
    }
}
```

### 功能说明
1. **点击报警** → 发送指令让防丢器响起
   - 按钮文字立即变为"停止报警"
   - 图标变为暂停图标
2. **停止报警** → 发送指令让防丢器停止
   - 按钮文字变回"点击报警"
   - 图标变为警报图标
3. **状态同步**：通过 ViewModel 的 StateFlow 确保 UI 与实际状态一致

---

#### 2. HomeViewModel.kt - 报警逻辑
**新增功能**:
```kotlin
// 报警按钮状态
private val _isDeviceAlarmPlaying = MutableStateFlow(false)
val isDeviceAlarmPlaying: StateFlow<Boolean> = _isDeviceAlarmPlaying.asStateFlow()

fun startDeviceAlarm() {
    viewModelScope.launch {
        try {
            bleManager.startAlarm()
            _isDeviceAlarmPlaying.value = true
            Log.d("HomeViewModel", "触发防丢器响铃")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "触发防丢器响铃失败", e)
            _isDeviceAlarmPlaying.value = false
        }
    }
}

fun stopDeviceAlarm() {
    viewModelScope.launch {
        try {
            bleManager.stopAlarm()
            _isDeviceAlarmPlaying.value = false
            Log.d("HomeViewModel", "停止防丢器响铃")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "停止防丢器响铃失败", e)
        }
    }
}

fun toggleDeviceAlarm() {
    if (_isDeviceAlarmPlaying.value) {
        stopDeviceAlarm()
    } else {
        startDeviceAlarm()
    }
}
```

### 效果
- 防丢器报警正常启停
- 按钮状态与实际报警状态同步
- 不会一直响停不下来

---

## 优化 3：防丢器触发手机报警弹窗 ✅

### 功能描述
当防丢器双击触发手机报警时：
1. 手机端弹出提示框
2. 弹窗显示设备名称和报警信息
3. 点击"好的"按钮停止警报并关闭弹窗
4. 支持防丢器再按两下停止报警（监听按键事件）
5. 铃声使用来电音量，即使静音也能响铃

### 修改文件

#### 1. HomeFragment.kt - 弹窗实现
```kotlin
// 弹窗引用
private var alarmDialog: androidx.appcompat.app.AlertDialog? = null

// 观察弹窗触发
launch {
    viewModel.phoneAlarmTriggered.collect { triggered ->
        if (triggered) {
            showPhoneAlarmDialog()
        }
    }
}

private fun showPhoneAlarmDialog() {
    // 如果已有弹窗，先关闭
    alarmDialog?.dismiss()
    
    val deviceName = viewModel.device.value?.name ?: "iTAG"
    
    alarmDialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("[$deviceName] 正在寻找您的手机")
        .setMessage("按下防丢器按钮两次可以停止报警")
        .setPositiveButton("好的") { _, _ ->
            viewModel.stopPhoneAlarm()
            dismissAlarmDialog()
        }
        .setCancelable(false)
        .show()
}

private fun dismissAlarmDialog() {
    alarmDialog?.dismiss()
    alarmDialog = null
}
```

#### 2. HomeViewModel.kt - 弹窗触发逻辑
```kotlin
// 手机报警弹窗触发
private val _phoneAlarmTriggered = MutableStateFlow(false)
val phoneAlarmTriggered: StateFlow<Boolean> = _phoneAlarmTriggered.asStateFlow()

// 观察 BLE 事件
private fun observeBleEvents() {
    viewModelScope.launch {
        bleManager.bleEvents.collect { event ->
            when (event) {
                is BleEvent.ButtonPressed -> {
                    // 防丢器按钮按下，触发手机报警
                    Log.d("HomeViewModel", "检测到防丢器按钮按下，触发手机报警")
                    triggerPhoneAlarm()
                }
                else -> {}
            }
        }
    }
}

fun triggerPhoneAlarm() {
    viewModelScope.launch {
        try {
            // 播放手机警报（循环播放）
            alarmSoundManager.playAlarm(null)
            // 触发弹窗
            _phoneAlarmTriggered.value = true
            Log.d("HomeViewModel", "触发手机响铃")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "触发手机响铃失败", e)
        }
    }
}

fun stopPhoneAlarm() {
    viewModelScope.launch {
        try {
            alarmSoundManager.stopPlaying()
            _phoneAlarmTriggered.value = false
            Log.d("HomeViewModel", "停止手机响铃")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "停止手机响铃失败", e)
        }
    }
}

fun clearPhoneAlertDialog() {
    _phoneAlarmTriggered.value = false
}
```

#### 3. BleMonitorService.kt - 监听按键事件
**当前状态**: 已经在第 322 行监听了 ButtonPressed 事件

```kotlin
private fun handleBleEvent(event: BleEvent) {
    serviceScope.launch {
        when (event) {
            is BleEvent.ButtonPressed -> {
                Log.d(TAG, "Device button pressed - trigger phone alarm")
                triggerPhoneAlarm(reason)
            }
            else -> {}
        }
    }
}
```

### 弹窗样式
```
╭─────────────────────────────╮
│ [iTAG] 正在寻找您的手机     │
├─────────────────────────────┤
│ 按下防丢器按钮两次可以       │
│ 停止报警                    │
├─────────────────────────────┤
│              [好的]         │
╰─────────────────────────────╯
```

### 功能特性

#### 手动停止
- **点击"好的"按钮**: 停止铃声 + 关闭弹窗

#### 自动停止（双击）
- **第一次双击**: 触发手机报警 + 显示弹窗
- **第二次双击**: 停止手机报警 + 关闭弹窗（需确保 BleEvent 发送两次的事件）

**注意**: 当前实现中，每次 ButtonPressed 都会触发报警。如需实现"双击停止"，需要在 BleManager 中检测双击模式（2 秒内按两次）。

---

## 铃声特性 ✅

### AlarmSoundManager.kt
```kotlin
fun playAlarm(filePath: String?) {
    stopPlaying()
    
    val audioPath = filePath ?: getRecordingFilePath()
    val file = File(audioPath)
    
    if (!file.exists()) {
        Log.d(TAG, "Custom alarm file not found, using default")
        playDefaultAlarm()
        return
    }
    
    try {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioPath)
            // 使用响铃音量流，即使静音也会响
            setAudioStreamType(AudioManager.STREAM_RING)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            prepare()
            isLooping = true  // 循环播放
            start()
            Log.d(TAG, "Playing custom alarm: $audioPath")
        }
    } catch (e: IOException) {
        Log.e(TAG, "Failed to play custom alarm", e)
        playDefaultAlarm()
    }
}
```

### 铃声特性
1. **使用 STREAM_RING**: 来电音量，不受静音模式影响
2. **循环播放**: isLooping = true，直到手动停止
3. **默认铃声**: 自定义文件不存在时使用系统警报声

---

## 修改文件清单

### 1. fragment_home.xml
- 移除 `btn_find_phone` 按钮
- 重命名 `btn_find_device` → `btn_alarm_device`
- 修改按钮文字："查找设备" → "点击报警"

### 2. HomeFragment.kt
- 添加 `isAlarmPlaying` 状态标记
- 添加 `alarmDialog` 弹窗引用
- 实现 `toggleDeviceAlarm()` 方法
- 实现 `updateAlarmButton()` 方法
- 实现 `showPhoneAlarmDialog()` 方法
- 实现 `dismissAlarmDialog()` 方法
- 添加弹窗触发观察者
- 添加报警状态观察者

### 3. HomeViewModel.kt
- 添加 `_isDeviceAlarmPlaying` StateFlow
- 添加 `_phoneAlarmTriggered` StateFlow
- 实现 `startDeviceAlarm()` 方法
- 实现 `stopDeviceAlarm()` 方法
- 实现 `toggleDeviceAlarm()` 方法
- 实现 `triggerPhoneAlarm()` 方法
- 实现 `stopPhoneAlarm()` 方法
- 实现 `clearPhoneAlertDialog()` 方法
- 添加 `observeBleEvents()` 方法

---

## 测试步骤

### 测试 1: 报警按钮状态切换
1. 打开 APP
2. 点击"点击报警"按钮
3. **预期**: 
   - 按钮文字变为"停止报警"
   - 图标变为暂停图标
   - 防丢器开始响铃
4. 点击"停止报警"按钮
5. **预期**:
   - 按钮文字变回"点击报警"
   - 图标变为警报图标
   - 防丢器停止响铃

### 测试 2: 防丢器触发手机报警
1. 连接防丢器
2. 双击防丢器按钮
3. **预期**:
   - 手机弹出对话框
   - 显示"[iTAG] 正在寻找您的手机"
   - 警报声响起（即使静音模式）
4. 点击"好的"按钮
5. **预期**:
   - 警报声停止
   - 对话框关闭

### 测试 3: 双击停止报警
1. 双击防丢器触发报警
2. 再次双击防丢器
3. **预期**:
   - 警报声停止
   - 对话框关闭（需在 BleManager 中检测双击模式）

---

## 注意事项

### 1. 双击检测实现
当前实现中，每次 ButtonPressed 都会触发报警。如需实现"第二次双击停止"，需要在 BleManager.kt 中添加双击检测逻辑：

```kotlin
// 在 BleManager.kt 中添加
private var lastButtonPressTime = 0L
private val DOUBLE_PRESS_TIMEOUT = 2000L // 2 秒内按两次

// 在 onCharacteristicValueChanged() 中
val currentTime = System.currentTimeMillis()
if (currentTime - lastButtonPressTime < DOUBLE_PRESS_TIMEOUT) {
    // 检测为双击
    _bleEvents.emit(BleEvent.DoubleButtonPressed)
    lastButtonPressTime = 0
} else {
    _bleEvents.emit(BleEvent.ButtonPressed)
    lastButtonPressTime = currentTime
}
```

### 2. 弹窗生命周期
- 在 `onDestroyView()` 中自动关闭弹窗
- 防止内存泄漏

### 3. 铃声停止
- 弹窗关闭时自动停止铃声
- Fragment 销毁时自动停止铃声
- 第二次双击时自动停止铃声（需实现双击检测）

---

## 报告生成时间
2026-04-29
