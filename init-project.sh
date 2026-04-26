#!/bin/bash

echo "======================================"
echo "BLE 防丢器 - 项目初始化"
echo "======================================"
echo ""

# 检查 gradlew 是否存在
if [ ! -f "./gradlew" ]; then
    echo "❌ gradlew 未找到，请确保这是 Android 项目根目录"
    exit 1
fi

# 创建 local.properties 模板
if [ ! -f "./local.properties" ]; then
    echo "📝 创建 local.properties 模板..."
    cat > local.properties << EOF
# 高德地图 API Key（请替换为你的实际 Key）
AMAP_API_KEY=YOUR_AMAP_API_KEY_HERE

# Android SDK 路径（如未自动配置请手动填写）
# sdk.dir=/path/to/Android/sdk
EOF
    echo "✅ local.properties 模板已创建"
    echo ""
    echo "⚠️  请编辑 local.properties 文件，填入你的高德地图 API Key"
    echo ""
else
    echo "✅ local.properties 已存在"
    echo ""
fi

# 检查 API Key 是否配置
if grep -q "YOUR_AMAP_API_KEY_HERE" local.properties; then
    echo "⚠️  高德地图 API Key 尚未配置"
    echo "   请访问 https://console.amap.com/ 获取 API Key"
    echo "   然后编辑 local.properties 文件"
    echo ""
else
    echo "✅ 高德地图 API Key 已配置"
    echo ""
fi

echo "======================================"
echo "下一步操作："
echo "======================================"
echo ""
echo "1. 使用 Android Studio 打开项目"
echo "2. 等待 Gradle 同步完成"
echo "3. 运行到模拟器或真机"
echo ""
echo "或使用命令行构建："
echo "  ./gradlew assembleDebug"
echo ""
echo "======================================"
