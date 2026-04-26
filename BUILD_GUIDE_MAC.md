# BLE 防丢器 - macOS 编译指南

本文档详细说明如何在 macOS 系统上编译 BLE 防丢器 Android 应用。

## 环境要求

- macOS 10.14 (Mojave) 或更高版本
- 至少 8GB 内存（推荐 16GB）
- 至少 20GB 可用磁盘空间
- 稳定的网络连接（下载依赖）

## 方法一：使用 Android Studio（推荐，最简单）

### 步骤 1：下载 Android Studio

1. 访问 Android Studio 官网：
   https://developer.android.com/studio

2. 点击 "Download Android Studio" 按钮下载

3. 下载完成后，打开 DMG 文件

### 步骤 2：安装 Android Studio

1. 将 Android Studio 图标拖拽到 Applications 文件夹
2. 打开 Applications 文件夹，启动 Android Studio

### 步骤 3：首次配置

1. 首次启动时，选择 "Do not import settings"
2. 点击 OK
3. 在 "Setup Wizard" 中选择 "Standard" 安装类型
4. 选择 UI 主题
5. 点击完成，等待 Android SDK 下载完成

### 步骤 4：打开项目

1. 启动 Android Studio
2. 点击 "Open"（或 File → Open）
3. 导航到项目所在文件夹
4. 选中项目根目录（包含 build.gradle.kts 的目录）
5. 点击 "Open"

### 步骤 5：等待 Gradle 同步

1. Android Studio 会自动检测依赖
2. 底部状态栏显示 Gradle 同步进度
3. 等待同步完成（可能需要 5-10 分钟）

### 步骤 6：编译 APK

1. 点击菜单：`Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
2. 等待编译完成
3. 编译成功后会弹出通知

### 步骤 7：找到 APK 文件

APK 位于：
```
项目文件夹/app/build/outputs/apk/debug/app-debug.apk
```

### 步骤 8：安装到手机

#### 使用 USB 安装

1. 在手机设置中启用开发者选项：
   - 设置 → 关于手机 → 连续点击"版本号"7 次
   
2. 启用 USB 调试：
   - 设置 → 系统 → 开发者选项 → USB 调试
   
3. 连接手机到 Mac
   
4. 在 Android Studio Terminal 执行：
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

#### 通过邮件或云盘传输

1. 在 Finder 中找到 APK 文件
2. 通过以下方式发送到手机：
   - 邮件附件
   - iCloud Drive、Google Drive
   - QQ/微信文件传输

## 方法二：使用命令行（适合高级用户）

### 前提条件

已安装：
- Java JDK 17+
- Android SDK
- Android Command Line Tools

### 步骤 1：安装 Homebrew（如未安装）

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 步骤 2：安装 OpenJDK

```bash
brew install openjdk@17
```

### 步骤 3：安装 Android SDK

```bash
brew install --cask android-commandline-tools
```

### 步骤 4：配置环境变量

在 `~/.zshrc` 或 `~/.bash_profile` 中添加：

```bash
# Java
export PATH="/usr/local/opt/openjdk@17/bin:$PATH"

# Android
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/tools
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/emulator
```

保存后重启 Terminal 或执行：

```bash
source ~/.zshrc
```

### 步骤 5：安装必要的 SDK 组件

```bash
# 接受许可协议
yes | sdkmanager --licenses

# 安装构建工具
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### 步骤 6：创建 Gradle Wrapper

在项目根目录执行：

```bash
# 如果已有 gradlew 文件，跳过此步骤
```

### 步骤 7：编译 APK

```bash
cd /path/to/project
./gradlew assembleDebug
```

### 步骤 8：使用 Gradle Daemon 加速编译

创建 `~/.gradle/gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxPermSize=512m
org.gradle.daemon=true
org.gradle.parallel=true
```

## Apple Silicon (M1/M2) Mac 用户注意事项

### Java 版本

某些版本的 OpenJDK 可能在 Apple Silicon 上有兼容性问题。

**推荐解决方案**：
1. 使用 Apple 优化的 Java 版本：
   ```bash
   brew install --cask zulu17
   ```

2. 或在安装前设置 Rosetta 转译：
   ```bash
   arch -x86_64 ./gradlew assembleDebug
   ```

### Android Emulator

如需使用模拟器：

```bash
sdkmanager "emulator" "system-images;android-34;google_apis;arm64-v8a"
```

## 常见问题

### Q: Gradle 下载速度慢

**解决方案**：使用国内镜像

在 `settings.gradle.kts` 中添加：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/gradle-plugins/") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}
```

### Q: 权限被拒绝

执行：
```bash
chmod +x gradlew
```

### Q: 存储空间不足

清理 Gradle 缓存：
```bash
./gradlew clean
rm -rf ~/.gradle/caches
```

## 下一步

编译成功后，请参考 README.md 中的"后台保活策略"章节，在手机上配置权限设置。
