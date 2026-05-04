# 递归类型推断修复报告

## 问题描述
Kotlin 编译器报错：「Type checking has Run into a recursive problem」

## 根本原因
在 GATT 回调和协程混用时，Kotlin 的类型推断系统陷入循环：
- `bleCallback` 是匿名对象
- 在回调中使用 `GlobalScope.launch`
- `launch` 块的返回类型依赖上下文
- 上下文又依赖 `bleCallback` 的类型

## 修复方案

### 1. 替换 GlobalScope.launch 为 Thread
**位置**：`BleManager.kt:90-112`

**修改前**：
```kotlin
deviceMacToConnect?.let { mac ->
    kotlinx.coroutines.GlobalScope.launch {
        try {
            kotlinx.coroutines.delay(3000)
            // ... 重连逻辑
        } catch (e: Exception) {
            Log.e(TAG, "自动重连失败", e)
        }
    }
}
```

**修改后**：
```kotlin
val macToReconnect = deviceMacToConnect
if (macToReconnect != null) {
    try {
        Thread {
            try {
                Thread.sleep(3000)
                // ... 重连逻辑
            } catch (e: Exception) {
                Log.e(TAG, "自动重连失败", e)
            }
        }.start()
    } catch (e: Exception) {
        Log.e(TAG, "自动重连线程启动失败", e)
    }
}
```

### 2. 替换双击检测的 GlobalScope.launch 为 Handler
**位置**：`BleManager.kt:163-183`

**修改前**：
```kotlin
kotlinx.coroutines.GlobalScope.launch {
    _bleEvents.emit(BleEvent.DoubleButtonPressed)
}
```

**修改后**：
```kotlin
android.os.Handler(android.os.Looper.getMainLooper()).post {
    try {
        _bleEvents.tryEmit(BleEvent.DoubleButtonPressed)
    } catch (e: Exception) {
        Log.e(TAG, "发送双击事件失败", e)
    }
}
```

### 3. 添加 Job 引用以便清理
**位置**：`BleManager.kt:287-309`

**修改前**：
```kotlin
launch {
    // ... RSSI 轮询
}

awaitClose {
    Log.d(TAG, "Flow 取消监听")
}
```

**修改后**：
```kotlin
val rssiPollJob = launch {
    // ... RSSI 轮询
}

awaitClose {
    rssiPollJob.cancel()
    Log.d(TAG, "Flow 取消监听，已停止 RSSI 轮询")
}
```

---

## 修复原理

### 为什么用 Thread 替换协程？
1. **避免类型推断循环**：`Thread` 是 Java 类，不涉及 Kotlin 协程类型推断
2. **后台延迟执行**：`Thread.sleep()` 不阻塞主线程
3. **简单可靠**：不需要考虑协程作用域和取消逻辑

### 为什么用 Handler 替换 GlobalScope？
1. **主线程安全**：`Handler(Looper.getMainLooper())` 确保在主线程执行
2. **避免协程开销**：不需要启动协程
3. **tryEmit 更安全**：`MutableSharedFlow.tryEmit()` 不会挂起

### 为什么保存 Job 引用？
1. **显式取消**：`awaitClose` 中可以取消协程
2. **防止内存泄漏**：Flow 取消时清理资源
3. **类型明确**：`Job` 类型明确，不会推断错误

---

## 修改文件清单

**`/app/src/main/java/com/monkeycode/blelostfinder/ble/BleManager.kt`**
- ✅ 自动重连：`GlobalScope.launch` → `Thread`
- ✅ 双击检测：`GlobalScope.launch` → `Handler.post`
- ✅ RSSI 轮询：添加 `Job` 引用并取消
- ✅ 使用 `tryEmit` 替代 `emit`

---

## 测试建议

- [ ] 构建项目，确认无编译错误
- [ ] 运行 APP，测试双击检测功能
- [ ] 测试断连后自动重连（3 秒延迟）
- [ ] 测试 RSSI 每秒刷新
- [ ] 开关蓝牙测试重连功能

---

## 注意事项

### 1. Thread vs 协程
- Thread 适合简单的延迟任务
- 协程适合复杂的异步流程
- 在回调中优先使用 Thread/Handler

### 2. tryEmit vs emit
- `tryEmit`: 立即发送，不挂起，可能失败
- `emit`: 挂起等待消费者，安全但需要协程作用域
- 在回调中使用 `tryEmit`

### 3. Handler 线程
- `Handler(Looper.getMainLooper())`: 主线程
- `Handler()`: 当前线程
- 蓝牙回调在主线程，使用 `Handler` 更简单
