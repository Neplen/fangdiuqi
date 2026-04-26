# BLE 防丢器 - 构建指南

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34
- Gradle 8.2.0

## 快速开始

### 1. 获取高德地图 API Key

1. 访问高德开放平台：https://console.amap.com/
2. 注册/登录账号
3. 创建应用，选择"Android SDK"
4. 获取 API Key（需要 SHA1 签名和包名）

SHA1 获取方式：
```bash
# Debug 模式
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# 或从 Android Studio 的 Gradle 面板
./gradlew androidDependencies
```

包名：`com.monkeycode.blelostfinder`

### 2. 配置项目

在项目根目录创建 `local.properties` 文件：

```properties
# 高德地图 API Key（必填）
AMAP_API_KEY=你的 API Key

# Android SDK 路径（通常由 Android Studio 自动配置）
# sdk.dir=/path/to/Android/sdk
```

### 3. 使用 Android Studio 打开

1. 启动 Android Studio
2. 选择 "Open" 并选择项目根目录
3. 等待 Gradle 同步完成
4. 运行到模拟器或真机

### 4. 命令行构建

```bash
# 清理项目
./gradlew clean

# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 运行测试
./gradlew test
```

## APK 输出位置

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

## 真机调试要求

1. 启用开发者选项
2. 开启 USB 调试
3. 蓝牙 4.0+（支持 BLE）
4. Android 8.0 或更高版本

## 注意事项

### WiFi 锁

如果使用 WiFi 锁功能，需要在 AndroidManifest.xml 添加：
```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

### 后台权限

不同品牌手机需要手动配置的权限：

- **小米**：安全中心 → 授权管理 → 自启动管理
- **华为**：手机管家 → 启动管理 → 手动管理
- **OPPO**：设置 → 应用管理 → 自启动管理
- **VIVO**：i 管家 → 应用管理 → 权限管理 → 自启动
- **三星**：设置 → 应用程序 → 选择应用 → 电池 → 不限制

### 高德地图注意事项

- 每个 API Key 必须绑定 SHA1 签名和包名
- 调试版本和发布版本的 SHA1 不同，需要分别配置
- 国内使用高德地图，海外项目可替换为 Google Maps

## 常见问题

### Q: 连接不上防丢器

A: 确保：
1. 防丢器有电
2. 蓝牙已开启
3. GPS 已开启（Android 6.0+ 需要 GPS 权限才能扫描 BLE）
4. 权限已全部授予

### Q: 后台服务被杀死

A: 按照"后台权限"一节手动配置各品牌自启动权限，并关闭电池优化。

### Q: 地图无法显示

A: 检查：
1. API Key 是否正确配置
2. API Key 是否绑定了正确的 SHA1 和包名
3. 网络连接是否正常

### Q: 构建失败 - SDK 未找到

A: 在 `local.properties` 中配置 SDK 路径：
```
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

## 下一步

- 阅读完整文档：见 `.monkeycode/specs/ble-lost-and-found/` 目录
- 查看项目计划：`specs/ble-lost-and-found/tasklist.md`
- 开发完成后移除所有 TODO 注释

祝您开发顺利！
