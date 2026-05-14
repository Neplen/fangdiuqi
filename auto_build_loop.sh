#!/bin/bash

# =========================================
# 自动构建、错误收集和修复循环脚本
# =========================================

REPO_URL="https://github.com/Neplen/fangdiuqi"
MAX_RETRIES=5
RETRY_COUNT=0

echo "=========================================="
echo "自动构建和修复循环"
echo "=========================================="
echo ""
echo "GitHub 仓库：$REPO_URL"
echo "最大重试次数：$MAX_RETRIES"
echo ""

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    
    echo ""
    echo "========== 第 $RETRY_COUNT 次构建 =========="
    echo ""
    
    # 步骤 1：推送代码到 GitHub
    echo "📤 [步骤 1] 推送代码到 GitHub..."
    git add -A
    git commit -m "chore: 自动构建提交" || true
    git push
    
    echo ""
    echo "✅ 代码已推送，请打开以下链接查看构建状态："
    echo "   https://github.com/Neplen/fangdiuqi/actions"
    echo ""
    
    # 步骤 2：等待用户反馈
    echo "⏳ [步骤 2] 等待构建结果..."
    echo ""
    echo "请在 GitHub Actions 页面查看构建状态："
    echo "  - 如果构建成功 ✅ → 请输入：success"
    echo "  - 如果构建失败 ❌ → 请输入错误信息（复制粘贴）"
    echo "  - 如果要退出 → 请输入：quit"
    echo ""
    
    read -p "构建结果：" BUILD_RESULT
    
    if [ "$BUILD_RESULT" == "success" ]; then
        echo ""
        echo "🎉 构建成功！"
        echo "APK 下载链接：https://github.com/Neplen/fangdiuqi/actions"
        echo "在最新的构建记录中可以下载 APK 文件"
        exit 0
    elif [ "$BUILD_RESULT" == "quit" ]; then
        echo ""
        echo "👋 已退出构建循环"
        exit 0
    else
        echo ""
        echo "❌ 构建失败，正在分析错误..."
        echo ""
        
        # 步骤 3：分析错误（这里需要 AI 模型介入）
        echo "🤖 [步骤 3] 请将以上错误信息发送给 AI 助手"
        echo "   AI 助手会自动："
        echo "   1. 分析错误原因"
        echo "   2. 修复代码错误"
        echo "   3. 重新推送代码"
        echo ""
        echo "   然后循环继续..."
        echo ""
        
        # 等待 AI 修复代码
        read -p "AI 是否已完成修复？(yes/no) " AI_FIXED
        
        if [ "$AI_FIXED" == "yes" ]; then
            echo "✅ 继续下一次构建..."
            continue
        else
            echo "❌ AI 未修复代码，退出循环"
            exit 1
        fi
    fi
done

echo ""
echo "❌ 达到最大重试次数 ($MAX_RETRIES)，构建失败"
exit 1
