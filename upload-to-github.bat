@echo off
chcp 65001 >nul
echo ========================================
echo GitHub Actions 自动编译 - 快速上传脚本
echo ========================================
echo.

REM 检查是否已安装 git
where git >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo 错误：未找到 git
    echo 请先安装 git: https://git-scm.com/download/win
    pause
    exit /b 1
)

echo [成功] 检测到 git
echo.

REM 检查是否已在 git 仓库中
if not exist ".git" (
    echo [初始化] 创建 git 仓库...
    git init
    git branch -M main
    echo [成功] Git 仓库已初始化
    echo.
)

REM 添加所有文件
echo [添加] 项目文件...
git add .
echo [成功] 文件已添加
echo.

REM 创建提交
echo [提交] 创建提交...
set /p commit_message="请输入提交信息 (直接回车使用默认): "
if "%commit_message%"=="" set commit_message=Initial commit - 项目初始化
git commit -m "%commit_message%"
echo [成功] 提交完成：%commit_message%
echo.

echo ========================================
echo 下一步：推送到 GitHub
echo ========================================
echo.
echo 请执行以下命令：
echo.
echo 1. 在 GitHub 创建新仓库（不要勾选 Initialize）
echo    访问：https://github.com/new
echo.
echo 2. 复制你的仓库地址（类似以下格式）：
echo    https://github.com/你的用户名/BleLostFinder.git
echo.
echo 3. 执行以下命令（将 ^<你的仓库地址^> 替换为实际地址）：
echo.
echo    git remote add origin ^<你的仓库地址^>
echo    git push -u origin main
echo.
echo ========================================
echo.
echo 推送后，在仓库页面的 Actions 标签查看编译进度
echo 编译完成后（约 5-10 分钟），在 Artifacts 区域下载 APK
echo.
echo 详细指南请查看：GITHUB_ACTIONS_GUIDE.md
echo ========================================
echo.
pause
