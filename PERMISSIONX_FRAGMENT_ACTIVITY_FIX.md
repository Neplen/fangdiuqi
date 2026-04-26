# PermissionX FragmentActivity 修复报告

## 问题描述

GitHub Actions 编译时出现 PermissionX 类型错误：

```
PermissionHelper.kt:42:21 None of the following functions can be called with the arguments supplied:
public open fun init(p0: Fragment): PermissionMediator!
public open fun init(p0: FragmentActivity): PermissionMediator!
```

## 根本原因

PermissionX 1.7+ 版本的 `init()` 方法只接受以下两种类型：
- `FragmentActivity`
- `Fragment`

而原代码使用的是 `Activity` 类型，导致编译失败。

### 类型继承关系

```
Activity
  └── FragmentActivity (AppCompatActivity 的父类)
      └── AppCompatActivity (MainActivity 继承的类)
```

## 修复方案

### 修改的文件：`PermissionHelper.kt`

#### 1. 更新导入语句

```kotlin
// 修复前
import android.app.Activity

// 修复后
import androidx.fragment.app.FragmentActivity
```

#### 2. 修改 `requestPermissions()` 方法

```kotlin
// 修复前
fun requestPermissions(activity: Activity, callback: (Boolean) -> Unit) {
    PermissionX.init(activity)
        ...
}

// 修复后
fun requestPermissions(activity: FragmentActivity, callback: (Boolean) -> Unit) {
    PermissionX.init(activity)
        ...
}
```

#### 3. 修改 `requestIgnoreBatteryOptimizations()` 方法

```kotlin
// 修复前
fun requestIgnoreBatteryOptimizations(activity: Activity)

// 修复后
fun requestIgnoreBatteryOptimizations(activity: FragmentActivity)
```

#### 4. 修改 `requestDrawOverlays()` 方法

```kotlin
// 修复前
fun requestDrawOverlays(activity: Activity)

// 修复后
fun requestDrawOverlays(activity: FragmentActivity)
```

## 兼容性说明

### 为什么这个修复是安全的？

1. **MainActivity 已经是 FragmentActivity 的子类**
   - `MainActivity` 继承自 `AppCompatActivity`
   - `AppCompatActivity` 继承自 `FragmentActivity`
   - 所以可以直接传递 `this`（MainActivity 实例）

2. **调用方无需修改**
   - `MainActivity.kt` 中的调用代码保持不变：
     ```kotlin
     PermissionHelper.requestPermissions(this) { allGranted ->
         ...
     }
     ```
   - Kotlin 的类型系统会自动将 `AppCompatActivity` 识别为 `FragmentActivity` 的子类

3. **向后兼容**
   - 如果将来需要在普通 `Activity` 中使用，可以创建重载方法
   - 或者将普通 `Activity` 转换为 `FragmentActivity`（如果实际类型兼容）

## 验证清单

- [x] 导入语句从 `android.app.Activity` 改为 `androidx.fragment.app.FragmentActivity`
- [x] `requestPermissions()` 参数类型更新
- [x] `requestIgnoreBatteryOptimizations()` 参数类型更新
- [x] `requestDrawOverlays()` 参数类型更新
- [x] MainActivity 调用方无需修改（类型兼容）
- [x] 重新打包 ZIP 文件

## 修改的文件列表

1. `app/src/main/java/com/monkeycode/blelostfinder/util/PermissionHelper.kt`
   - 第 13 行：导入语句更新
   - 第 41 行：`requestPermissions()` 参数类型
   - 第 54 行：`requestIgnoreBatteryOptimizations()` 参数类型
   - 第 92 行：`requestDrawOverlays()` 参数类型

## 下一步操作

1. 下载最新的 `BLELostFinder.zip` 文件
2. 解压并推送到 GitHub 仓库
3. 等待 GitHub Actions 编译完成
4. 下载生成的 APK 进行测试

这次修复应该能解决 PermissionX 的所有类型错误！
