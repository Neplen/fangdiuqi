@echo off
echo ======================================
echo BLE 防丢器 - 项目初始化
echo ======================================
echo.

REM 检查 gradlew 是否存在
if not exist ".\gradlew.bat" (
    echo gradlew.bat 未找到，请确保这是 Android 项目根目录
    exit /b 1
)

REM 创建 local.properties 模板
if not exist ".\local.properties" (
    echo 创建 local.properties 模板...
    (
        echo # 高德地图 API Key（请替换为你的实际 Key）
        echo AMAP_API_KEY=YOUR_AMAP_API_KEY_HERE
        echo.
        echo # Android SDK 路径（如未自动配置请手动填写）
        echo # sdk.dir=C:\Users\YourName\AppData\Local\Android\Sdk
    ) > local.properties
    echo.
    echo ⚠️ 请编辑 local.properties 文件，填入你的高德地图 API Key
    echo.
) else (
    echo local.properties 已存在
    echo.
)

echo ======================================
echo 下一步操作：
echo ======================================
echo.
echo 1. 使用 Android Studio 打开项目
echo 2. 等待 Gradle 同步完成
echo 3. 运行到模拟器或真机
echo.
echo 或使用命令行构建：
echo   gradlew assembleDebug
echo.
echo ======================================
pause
