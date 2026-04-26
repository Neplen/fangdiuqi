@echo off
chcp 65001 >nul
echo ========================================
echo 生成完整的 Gradle Wrapper
echo ========================================
echo.

REM 检查目录
if not exist "gradle\wrapper" mkdir gradle\wrapper

echo 正在下载 gradle-wrapper.jar...
powershell -Command "& {Invoke-WebRequest -Uri 'https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'}"

if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo.
    echo [成功] gradle-wrapper.jar 下载成功
) else (
    echo.
    echo [失败] gradle-wrapper.jar 下载失败
    echo.
    echo 请手动下载：
    echo 1. 访问：https://services.gradle.org/distributions/gradle-8.2-bin.zip
    echo 2. 解压后找到 gradle/wrapper/gradle-wrapper.jar
    echo 3. 复制到本项目的 gradle/wrapper/ 目录
    echo.
    pause
    exit /b 1
)

echo.
echo ========================================
echo Gradle Wrapper 已生成!
echo ========================================
echo.
echo 文件列表:
dir /s gradlew gradlew.bat gradle\wrapper\gradle-wrapper.jar gradle\wrapper\gradle-wrapper.properties
echo.
echo 现在可以提交到 GitHub 了:
echo.
echo   git add gradlew gradlew.bat gradle\wrapper/
echo   git commit -m "Add complete Gradle Wrapper"
echo   git push
echo.
pause
