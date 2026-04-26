# 📱 GitHub Actions 一键编译 APK

**无需配置任何环境，5 分钟自动获取 APK！**

---

## 🎯 完整流程

```mermaid
graph LR
    A[上传代码到 GitHub] --> B[Actions 自动编译]
    B --> C[下载 APK]
    C --> D[安装到手机]
```

---

## 📝 步骤详解

### 步骤 1：上传代码（2 分钟）

#### 方法 A：使用网页上传（最简单）

1. **创建 GitHub 仓库**
   ```
   访问：https://github.com/new
   仓库名：BleLostFinder
   类型：Public 或 Private
   ✅ 不要勾选 "Initialize this repository"
   ```

2. **上传文件**
   ```
   在仓库页面点击：adding an existing file
   选择：Upload files
   拖拽整个项目文件夹到上传区域
   点击 Commit changes
   ```

#### 方法 B：使用 Git 命令行

```bash
# 进入项目目录
cd /workspace

# 初始化仓库
git init
git branch -M main

# 添加所有文件
git add .
git commit -m "Initial commit"

# 关联 GitHub 仓库（替换为你的仓库地址）
git remote add origin https://github.com/你的用户名/BleLostFinder.git

# 推送到 GitHub
git push -u origin main
```

**快捷方式：** 运行项目根目录的脚本
- Windows: `upload-to-github.bat`
- Mac/Linux: `chmod +x upload-to-github.sh && ./upload-to-github.sh`

---

### 步骤 2：生成 Gradle Wrapper（关键！3 分钟）

GitHub Actions 需要 `gradlew` 文件才能编译。

#### 最简单方法：使用 GitHub Codespaces

1. **打开 Codespace**
   ```
   在你的仓库页面
   点击 Code 按钮
   选择 Codespaces 标签
   点击 Create codespace on main
   ```

2. **生成 Wrapper**
   ```bash
   # 等待 Codespace 启动（约 1 分钟）
   # 在终端执行：
   chmod +x gradlew
   ./gradlew --version
   ```

3. **提交文件**
   ```bash
   git add gradlew gradlew.bat gradle/wrapper/
   git commit -m "Add Gradle Wrapper"
   git push
   ```

#### 替代方法：下载现成文件

```bash
# 在项目根目录执行
wget https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradlew
wget https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradlew.bat
chmod +x gradlew
git add gradlew gradlew.bat
git commit -m "Add gradlew"
git push
```

---

### 步骤 3：启用 Actions（1 分钟）

1. **进入 Actions 设置**
   ```
   仓库页面 → Settings → Actions → General
   ```

2. **确保权限开启**
   ```
   ✅ Allow all actions and reusable workflows
   ```

---

### 步骤 4：触发编译（10 秒）

#### 自动触发
- 每次 `git push` 都会自动编译

#### 手动触发
1. **进入 Actions 页面**
   ```
   仓库页面 → Actions 标签
   ```

2. **运行 Workflow**
   ```
   选择 Android CI - Build APK
   点击 Run workflow
   选择分支 (main)
   点击 Run workflow
   ```

---

### 步骤 5：下载 APK（1 分钟）

**编译完成后（5-10 分钟）：**

1. **查看编译结果**
   ```
   Actions 页面 → 点击最近的运行记录
   ```

2. **下载 APK**
   ```
   滚动到页面底部
   找到 Artifacts 区域
   点击 BleLostFinder-Debug
   下载并解压得到 app-debug.apk
   ```

**APK 保留 30 天！**

---

## ✅ 快速验证清单

- [ ] GitHub 仓库已创建
- [ ] 代码已上传
- [ ] gradlew 文件已生成
- [ ] Actions 已启用
- [ ] Workflow 运行成功（绿色勾）
- [ ] APK 已下载
- [ ] APK 已安装测试

---

## 📦 编译产物说明

| 产物 | 文件名 | 特点 | 用途 |
|------|--------|------|------|
| **Debug 版** | `app-debug.apk` | 未优化，可调试，已签名 | ✅ 推荐测试使用 |
| **Release 版** | `app-release-unsigned.apk` | 已优化，未签名 | 需要手动签名 |

**第一次使用 Debug 版本即可，功能完全正常！**

---

## ⏱️ 编译时间

| 阶段 | 时间 |
|------|------|
| 首次编译 | 5-10 分钟（下载依赖） |
| 后续编译 | 2-3 分钟（使用缓存） |
| 手动触发 | 立即开始 |

---

## 🔧 常见问题

### Q1: Workflow 失败 - gradlew 不存在

**原因：** 未生成 Gradle Wrapper

**解决：** 按步骤 2 生成 gradlew 文件

```bash
# 在 Codespace 中执行
./gradlew --version
git add gradlew gradlew.bat
git commit -m "Add wrapper"
git push
```

### Q2: 编译失败 - SDK not found

**原因：** 配置文件未正确上传

**解决：** 检查以下文件是否存在：
- `.github/workflows/build.yml`
- `build.gradle.kts`
- `app/build.gradle.kts`

### Q3: 编译成功但无法下载 APK

**原因：** 浏览器拦截了下载

**解决：**
1. 检查浏览器下载设置
2. 尝试其他浏览器
3. 使用 `gh` 命令行工具：
   ```bash
   gh run download --repo 你的用户名/BleLostFinder
   ```

### Q4: 如何自动发布 Release？

创建带标签的推送：

```bash
git tag v1.0.0
git push origin v1.0.0
```

Actions 会自动创建 Release 并上传 APK

---

## 🎉 成功标志

如果你看到以下信息，说明编译成功：

```
✅ Android CI - Build APK
   运行时间：6m 23s
   状态：Success (绿色勾)
   
Artifacts:
   BleLostFinder-Debug ↓
   BleLostFinder-Release-Unsigned ↓
```

---

## 📚 相关文档

- `GITHUB_ACTIONS_GUIDE.md` - 详细指南
- `upload-to-github.sh` / `.bat` - 上传脚本
- `.github/workflows/build.yml` - 编译配置

---

## 💡 提示

1. **首次编译较慢**：耐心等待，后续会快很多
2. **APK 保留 30 天**：及时下载
3. **推荐使用 Debug 版**：功能完整，无需签名
4. **每次推送都编译**：可以持续集成

---

**立即开始：上传代码 → 等待编译 → 下载 APK → 安装使用**

就这么简单！🚀
