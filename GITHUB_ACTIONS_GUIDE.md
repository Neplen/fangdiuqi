# 🚀 GitHub Actions 自动编译指南

## 使用步骤（超简单！）

### 步骤 1：上传代码到 GitHub

#### 方式 A：使用 GitHub 网页上传（最简单）

1. **创建新仓库**
   - 访问 https://github.com/new
   - 仓库名称：`BleLostFinder`（或你喜欢的名字）
   - 选择 **Public**（公开）或 **Private**（私有）
   - 点击 **Create repository**

2. **上传项目文件**
   - 在新建的文件页面，点击 "uploading an existing file"
   - 把整个项目文件夹的所有文件拖拽到上传区域
   - 或者使用命令行：
     ```bash
     cd /workspace
     git init
     git add .
     git commit -m "Initial commit"
     git branch -M main
     git remote add origin https://github.com/你的用户名/BleLostFinder.git
     git push -u origin main
     ```

### 步骤 2：生成 Gradle Wrapper（重要！）

上传后，GitHub Actions 需要 `gradlew` 文件才能编译。

#### 选项 1：使用 GitHub Codespaces（推荐，无需本地环境）

1. 在你的 GitHub 仓库页面，点击 **Code** 按钮
2. 切换到 **Codespaces** 标签
3. 点击 **Create codespace on main**
4. 等待 Codespace 启动（约 1-2 分钟）
5. 在终端执行：
   ```bash
   chmod +x gradlew
   ./gradlew --version
   ```
6. 提交更改：
   ```bash
   git add gradlew gradlew.bat gradle/wrapper/
   git commit -m "Add Gradle Wrapper"
   git push
   ```

#### 选项 2：使用在线 Gradle Wrapper 生成器

1. 访问：https://gradle.org/next-steps/?version=8.2&format=bin
2. 下载 Gradle 8.2
3. 解压后，在终端执行：
   ```bash
   cd /path/to/gradle-8.2
   ./bin/gradle wrapper
   ```
4. 复制生成的 `gradlew` 和 `gradlew.bat` 到项目根目录

#### 选项 3：找有 Android Studio 的朋友帮忙

让朋友打开项目，Android Studio 会自动生成 gradlew 文件

### 步骤 3：启用 GitHub Actions

1. 在你的仓库页面，点击 **Settings**
2. 点击 **Actions** → **General**
3. 确保 **Allow all actions** 已选中
4. 如果没有这个选项，说明 Actions 已默认启用

### 步骤 4：触发编译

#### 自动触发
- 每次你 `git push` 推送代码时，都会自动编译

#### 手动触发
1. 在仓库页面，点击 **Actions** 标签
2. 选择 **Android CI - Build APK** workflow
3. 点击 **Run workflow** 按钮
4. 选择分支（通常是 main）
5. 点击 **Run workflow**

### 步骤 5：下载 APK

编译完成后（约 5-10 分钟）：

1. 在 **Actions** 页面，点击最近的运行记录
2. 滚动到页面底部，找到 **Artifacts** 区域
3. 点击 **BleLostFinder-Debug** 下载 APK
4. 解压后得到 `app-debug.apk`

**APK 保留 30 天！**

---

## 编译产物

### Debug 版本（推荐测试用）
- 文件名：`app-debug.apk`
- 特点：未优化，可调试，自动签名
- 用途：测试、开发

### Release 版本（未签名）
- 文件名：`app-release-unsigned.apk`
- 特点：已优化，但未签名
- 用途：需要手动签名后才能安装

**提示：** Debug 版本可以直接安装使用！

---

## 常见问题

### Q1: Workflow 运行失败 - "gradlew: No such file or directory"

**解决方法：** 你需要生成 Gradle Wrapper

最简单的方式：
1. 在你的仓库页面创建 Codespace（见步骤 2）
2. 执行 `./gradlew --version`
3. 提交生成的文件

或者直接下载现成的 gradlew：
```bash
# 在项目根目录执行
wget https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradlew
wget https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradlew.bat
chmod +x gradlew
git add gradlew gradlew.bat
git commit -m "Add gradlew"
git push
```

### Q2: 编译失败 - SDK 未找到

GitHub Actions 会自动配置 Android SDK，不应该出现这个问题。

如果仍然失败，检查 `.github/workflows/build.yml` 是否正确上传。

### Q3: 编译时间太长

首次编译需要下载所有依赖（约 5-10 分钟）

后续编译会使用缓存（约 2-3 分钟）

### Q4: 如何发布正式版本？

1. 创建 Release 版本需要签名证书
2. 在仓库 Settings → Secrets and variables → Actions
3. 添加以下 secrets：
   - `KEYSTORE` - Base64 编码的 keystore 文件
   - `KEYSTORE_PASSWORD` - 密钥库密码
   - `KEY_ALIAS` - 密钥别名
   - `KEY_PASSWORD` - 密钥密码

或者直接使用 Debug 版本（功能完全正常）

---

## 快速验证清单

- [ ] 代码已上传到 GitHub
- [ ] gradlew 文件已生成并提交
- [ ] Actions 已启用
- [ ] Workflow 运行成功
- [ ] APK 已下载
- [ ] APK 已安装到手机测试

---

## 下一步

1. **上传代码** → 触发自动编译
2. **下载 APK** → 安装测试
3. **发现问题** → 修改代码 → 再次推送
4. **自动获得新 APK** → 无需任何配置！

---

## 需要帮助？

如果遇到问题：
1. 查看 Actions 页面的日志
2. 复制错误信息
3. 提交 Issue 或询问 AI 助手

祝你编译成功！🎉
