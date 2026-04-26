# 🚀 5 分钟获取 APK - 超简单指南

**无需安装任何东西，无需配置环境！**

---

## 📋 三步搞定

### 第 1 步：上传代码到 GitHub（2 分钟）

1. **访问 GitHub：https://github.com/new**
   - 输入仓库名：`BleLostFinder`
   - 选择 Public 或 Private
   - ❌ **不要勾选** "Initialize this repository"
   - 点击 **Create repository**

2. **上传文件**
   - 点击页面中的 "uploading an existing file"
   - 把项目所有文件拖进去
   - 点击 **Commit changes**

---

### 第 2 步：生成编译脚本（3 分钟）

使用 GitHub Codespaces（在线编辑器）：

1. **打开 Codespace**
   - 在你的仓库页面
   - 点击 **Code** 按钮
   - 选择 **Codespaces** 标签
   - 点击 **Create codespace on main**

2. **等待启动**（约 1-2 分钟）

3. **在终端执行**：
   ```bash
   ./gradlew --version
   ```

4. **提交文件**：
   ```bash
   git add gradlew gradlew.bat gradle/wrapper/
   git commit -m "Add Gradle Wrapper"
   git push
   ```

---

### 第 3 步：下载 APK（5-10 分钟等待）

1. **查看编译进度**
   - 点击仓库的 **Actions** 标签
   - 点击正在运行的任务

2. **下载 APK**
   - 滚动到页面底部
   - 点击 **BleLostFinder-Debug**
   - 下载并解压

3. **安装到手机**
   - 传输 APK 到手机
   - 点击安装

---

## ✅ 完成！

你现在有了可安装的 APK 文件！

文件名：`app-debug.apk`

---

## 📚 详细文档

如果有问题，查看详细指南：
- `AUTO_BUILD_README.md` - 完整步骤说明
- `GITHUB_ACTIONS_GUIDE.md` - GitHub 使用教程

---

## ⏱️ 时间统计

| 步骤 | 时间 |
|------|------|
| 上传代码 | 2 分钟 |
| 生成 gradlew | 3 分钟 |
| 等待编译 | 5-10 分钟 |
| **总计** | **约 15 分钟** |

---

## 💡 提示

- **第一次慢**：后续编译只需 2-3 分钟
- **随时编译**：每次 git push 都会自动编译
- **APK 保留 30 天**：及时下载

---

**开始吧：https://github.com/new** 🎉
