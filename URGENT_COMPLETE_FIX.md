# 紧急完整修复方案

由于代码量大，我已创建完整的修复包。以下是关键修改：

## 1. 设备扫描功能（最高优先级）

**新增文件**:
- `ScanFragment.kt` - 设备扫描页面
- `ScanViewModel.kt` - 扫描逻辑
- `fragment_scan.xml` - 扫描页面布局
- `item_scan_device.xml` - 设备列表项布局
- `main_menu.xml` - 右上角菜单（+号按钮）

**修改文件**:
- `MainActivity.kt` - 添加菜单支持
- `HomeFragment.kt` - 简化连接显示
- `nav_graph.xml` - 添加 scan destination
- `BleManager.kt` - 支持多设备连接

## 2. 报警铃声修复

**AlarmSoundManager.kt**:
- 使用 `STREAM_RING` 播放（来电音量）
- 修复 `playDefaultAlarm()` 类型错误
- 录音保存到 `/Android/data/.../Music/alarms/`

## 3. 定时勿扰模式

**SettingsFragment.kt**:
- 使用 `MaterialTimePicker` 选择时间
- 时间保存到 DataStore
- 重启不丢失

## 4. 开启监控闪退修复

**HomeViewModel.kt**:
- 添加 try-catch 包裹 `startMonitoring()`
- 适配 Android 8.0+ 前台服务 API

## 5. 后台保活

已添加权限:
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `SYSTEM_ALERT_WINDOW`

---

## 使用说明

1. 打开 APP → 点击右上角 **+** 号
2. 进入设备扫描页面
3. 自动扫描附近 BLE 设备
4. 点击列表中的设备连接
5. 连接成功后返回主页

请查看 `/workspace` 目录下的完整代码文件。
