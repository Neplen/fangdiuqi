# BLE 防丢器 - Android 应用

一款专业级的 Android BLE 蓝牙防丢 APP，完美适配 iTAG 设备，具有强大的后台保活能力和智能防丢功能。

**⚠️ 注意：本项目需要 Android Studio 进行编译打包**

## 功能特性

### 核心功能
- ✅ **BLE 连接管理**：自动连接 iTAG 设备，支持后台持续监控
- ✅ **双向查找**：一键让防丢器响铃，防丢器按键找手机
- ✅ **RSSI 距离监控**：基于信号强度智能判断距离，超距自动报警
- ✅ **电量监测**：实时显示防丢器剩余电量
- ✅ **地图定位**：预留接口（可选功能）

### 智能防丢
- ✅ **WIFI 勿扰模式**：连接家中 WiFi 时自动暂停监控（最重要功能）
- ✅ **定时勿扰**：自定义时间段免打扰（默认 21:00-08:00）
- ✅ **延迟报警**：超距持续 1 分钟才报警，避免误报
- ✅ **自定义阈值**：RSSI 阈值和延迟时间支持自定义

### 报警系统
- ✅ **多种铃声**：可选系统铃声或本地音频
- ✅ **录音功能**：录制自定义报警铃声
- ✅ **强制响铃**：无视静音/勿扰模式

### 后台保活
- ✅ **前台服务**：常驻通知栏，确保不被杀死
- ✅ **WakeLock 锁**：防止休眠
- ✅ **开机自启**：支持开机自动启动
- ✅ **电池优化白名单**：引导用户设置
- ✅ **全品牌适配**：包括 vivo、小米、华为、OPPO 等

### 用户体验
- ✅ **无广告**：纯净无广告体验
- ✅ **无登录**：纯本地运行，无需账号
- ✅ **永久保存**：所有设置重启不恢复默认
- ✅ **零配置**：无需任何 API Key 即可运行

## 技术架构

- **语言**：Kotlin 1.9+
- **架构模式**：MVVM + Clean Architecture
- **依赖注入**：Hilt 2.48
- **数据库**：Room 2.6
- **本地存储**：DataStore Preferences
- **最低版本**：Android 8.0 (API 26)
- **目标版本**：Android 14 (API 34)

## 快速开始

### 方法一：使用 Android Studio（推荐）

1. **下载并安装 Android Studio**
   - 访问：https://developer.android.com/studio
   - 下载并安装最新版 Android Studio

2. **打开项目**
   ```
   启动 Android Studio
   File → Open → 选择本项目根目录
   ```

3. **等待 Gradle 同步**
   - Android Studio 会自动下载依赖并配置项目
   - 首次同步可能需要几分钟

4. **编译 APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```

5. **安装到手机**
   - APK 位置：`app/build/outputs/apk/debug/app-debug.apk`
   - 直接拷贝到手机安装，或使用 `adb install` 命令

### 方法二：命令行编译（需要配置环境）

**环境要求：**
- JDK 17+
- Android SDK 34
- Gradle 8.2.0+
- ANDROID_HOME 环境变量

```bash
# 1. 配置环境变量
export ANDROID_HOME=/path/to/Android/sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools

# 2. 授予 gradlew 执行权限
chmod +x gradlew

# 3. 编译 Debug APK
./gradlew assembleDebug

# 4. 编译 Release APK
./gradlew assembleRelease

# APK 输出位置
# Debug: app/build/outputs/apk/debug/app-debug.apk
# Release: app/build/outputs/apk/release/app-release-unsigned.apk
```

## 核心 API 说明

### BLE UUID 配置

```kotlin
// 设备名和 MAC 地址
val I_DEVICE_NAME = "iTAG"
val I_DEVICE_MAC = "FF:FF:11:8C:4E:3B"

// 响铃控制服务
val ALERT_SERVICE_UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
val ALERT_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")
// 写入 0x01 响铃，0x00 停止

// 电量服务
val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

// 按键找手机服务
val CUSTOM_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val CUSTOM_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
```

### 距离判断逻辑

```kotlin
RSSI > -70 dBm  → 1 米内
RSSI > -80 dBm  → 1-3 米
RSSI > -90 dBm  → 3-10 米
RSSI <= -90 dBm → 超出距离（触发报警条件）
```

## 权限清单

应用需要以下权限：

- 蓝牙相关：`BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- 位置相关：`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
- 前台服务：`FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`
- 其他：`WAKE_LOCK`, `RECORD_AUDIO`, `RECEIVE_BOOT_COMPLETED`

## 后台保活策略

### 1. 前台服务
- 创建持久性通知，保持在通知栏
- 使用 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`

### 2. WakeLock
- 获取 PARTIAL_WAKE_LOCK 防止 CPU 休眠
- 获取 WiFi Lock 保持网络连接

### 3. 厂商适配
- 引导用户开启自启动权限
- 引导用户关闭电池优化
- 引导用户开启背景弹出权限

## 不同品牌手机后台配置指南

### 小米 (MIUI)
1. 设置 → 应用设置 → 应用管理 → BLE 防丢器
2. 权限管理 → 开启自启动
3. 省电策略 → 设置为"无限制"

### 华为 (EMUI/HarmonyOS)
1. 设置 → 应用和服务 → 应用启动管理
2. 关闭"全部自动管理"
3. 手动开启"允许自启动"、"允许关联启动"、"允许后台活动"

### OPPO (ColorOS)
1. 设置 → 应用管理 → 应用列表 → BLE 防丢器
2. 耗电管理 → 允许完全后台行为
3. 自启动管理 → 允许自启动

### VIVO (Funtouch OS)
1. i 管家 → 应用管理 → 权限管理 → 自启动 → 允许 BLE 防丢器
2. 设置 → 电池 → 后台耗电管理 → 允许后台高耗电

### 三星 (One UI)
1. 设置 → 应用程序 → BLE 防丢器 → 电池
2. 选择"不受限制"

## 开发计划

- [x] 核心 BLE 连接功能
- [x] RSSI 防丢监控
- [x] WIFI 勿扰模式
- [x] 定时勿扰模式
- [x] 后台保活服务
- [x] 双向响铃功能
- [x] 自定义设置
- [ ] 地图定位（可选功能，后续添加）
- [ ] 多设备支持
- [ ] 历史轨迹记录

## 注意事项

1. **BLE 兼容性**：需要蓝牙 4.0+ 的 Android 设备
2. **系统版本**：Android 8.0 或更高版本
3. **权限配置**：请务必按照上述指南配置后台权限，否则可能被系统杀死
4. **地图功能**：当前版本地图功能已禁用，不影响核心功能使用

## 技术文档

完整的需求文档、设计文档和任务列表位于：
- 需求文档：`.monkeycode/specs/ble-lost-and-found/requirements.md`
- 设计文档：`.monkeycode/specs/ble-lost-and-found/design.md`
- 任务列表：`.monkeycode/specs/ble-lost-and-found/tasklist.md`
- 开发状态：`DEVELOPMENT_STATUS.md`

## 开源协议

MIT License

## 技术支持

如有问题或建议，欢迎提交 Issue。
