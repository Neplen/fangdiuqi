# BLE 防丢器 - Windows 编译指南

本文档详细说明如何在 Windows 系统上编译 BLE 防丢器 Android 应用。

## 环境要求

- Windows 10/11 (64 位)
- 至少 8GB 内存（推荐 16GB）
- 至少 20GB 可用磁盘空间
- 稳定的网络连接（下载依赖）

## 方法一：使用 Android Studio（推荐，最简单）

### 步骤 1：下载 Android Studio

1. 访问 Android Studio 官网：
   https://developer.android.com/studio

2. 点击 "Download Android Studio" 按钮下载

3. 运行下载的安装文件（android-studio-*.exe）

### 步骤 2：安装 Android Studio

1. 双击安装文件，按照向导完成安装
2. 建议保持默认安装路径：`C:\Program Files\Android\Android Studio`
3. 安装完成后启动 Android Studio

### 步骤 3：首次配置

1. 启动 Android Studio 后，选择 "Do not import settings"
2. 点击 OK
3. 在 "Setup Wizard" 中选择 "Standard" 安装类型
4. 选择 UI 主题（推荐保持 Default）
5. 点击 Finish，Android Studio 会自动下载 Android SDK 和必要组件

**注意**：首次下载可能需要 10-30 分钟，请耐心等待

### 步骤 4：打开项目

1. 启动 Android Studio
2. 点击 "Open"（或 File → Open）
3. 导航到项目所在文件夹，选择项目根目录（包含 build.gradle.kts 的目录）
4. 点击 "Open"

### 步骤 5：等待 Gradle 同步

1. Android Studio 会自动检测需要下载的依赖
2. 底部状态栏会显示 "Gradle Sync" 进度
3. 等待同步完成（首次可能需 5-10 分钟）

### 步骤 6：编译 APK

#### 方式 A：使用菜单编译

1. 点击顶部菜单：`Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
2. 等待编译完成（首次编译可能需 5-10 分钟）
3. 编译成功后会弹出通知："APK(s) generated successfully"

#### 方式 B：使用快捷键编译

1. 按 `Ctrl + Shift + A`
2. 输入 "Build APK"
3. 选择 "Build APK" 选项

### 步骤 7：找到生成的 APK

编译成功后，APK 文件位于：

```
项目文件夹\app\build\outputs\apk\debug\app-debug.apk
```

完整路径示例：
```
C:\Users\你的用户名\Documents\BleLostFinder\app\build\outputs\apk\debug\app-debug.apk
```

### 步骤 8：安装到手机

#### 方式 A：直接拷贝安装

1. 使用 USB 数据线连接手机和电脑
2. 将 `app-debug.apk` 复制到手机存储
3. 在手机上打开文件管理器，找到 APK 文件
4. 点击安装

#### 方式 B：使用 ADB 安装

1. 在手机上启用"开发者选项"：
   - 设置 → 关于手机 → 连续点击"版本号"7 次

2. 启用"USB 调试"：
   - 设置 → 系统 → 开发者选项 → USB 调试

3. 连接手机到电脑

4. 在 Android Studio 中打开 Terminal，执行：
   ```bash
   adb devices
   ```
   确认手机已连接

5. 安装 APK：
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## 常见问题

### Q1: Gradle 同步失败

**错误信息**：Could not resolve all dependencies

**解决方法**：
1. 检查网络连接
2. 点击 Tools → SDK Manager，确保 Android SDK 已安装
3. 重试：File → Invalidate Caches / Restart

### Q2: 编译失败 - SDK 未找到

**错误信息**：SDK location not found

**解决方法**：
1. 在项目根目录创建 `local.properties` 文件
2. 添加以下内容（根据实际安装路径修改）：
   ```properties
   sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
   ```

### Q3: 内存不足

**错误信息**：GC overhead limit exceeded / Out of memory

**解决方法**：
1. 关闭其他占用内存的程序
2. 编辑 `gradle.properties`，增加内存：
   ```properties
   org.gradle.jvmargs=-Xmx2048m -XX:MaxPermSize=512m
   ```

### Q4: 编译速度慢

**解决方法**：
1. 开启 Gradle 守护进程：
   在 `gradle.properties` 添加：
   ```properties
   org.gradle.daemon=true
   org.gradle.parallel=true
   ```

### Q5: 签名问题（仅 Release 编译）

编译 Debug 版本不需要签名。如需编译 Release 版本：

1. 创建密钥库：
   ```bash
   keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 25000 -alias my-alias
   ```

2. 在 `app/build.gradle.kts` 中添加签名配置

## 下一步

编译成功后，请参考 README.md 中的"后台保活策略"章节，在手机上配置权限设置。

享受使用 BLE 防丢器！
