# Android BLE 防丢 APP 技术设计文档

## 1. 文档概述

### 1.1 文档目的

本文档描述 Android BLE 防丢 APP 的技术架构设计、模块划分、技术选型和实现方案，为开发团队提供技术指导。

### 1.2 参考资料

- 需求文档：`.monkeycode/specs/ble-lost-and-found/requirements.md`
- Android BLE 官方文档：https://developer.android.com/guide/topics/connectivity/bluetooth-le
- Android 前台服务文档：https://developer.android.com/guide/components/foreground-services

---

## 2. 项目架构设计

### 2.1 整体架构

采用 MVVM (Model-View-ViewModel) 架构模式，结合 Clean Architecture 分层思想。

架构层级说明：

- **UI Layer**：界面展示、用户交互（Activity、Fragment、Custom Views）
- **ViewModel Layer**：业务逻辑、状态管理（ViewModel、LiveData/StateFlow）
- **Repository Layer**：数据协调、业务规则（Repositories、Use Cases）
- **Data Layer**：数据源、底层实现（Ble Manager、DataStore、LocationManager）

### 2.2 模块分层

| 层级 | 职责 | 主要组件 |
|------|------|----------|
| **UI Layer** | 界面展示、用户交互 | Activity、Fragment、Custom Views |
| **ViewModel Layer** | 业务逻辑、状态管理 | ViewModel、LiveData/StateFlow |
| **Repository Layer** | 数据协调、业务规则 | Repositories、Use Cases |
| **Data Layer** | 数据源、底层实现 | BLE Manager、DataStore、LocationManager |

### 2.3 模块依赖关系

app (主模块) 依赖以下子模块：
- core-ble (BLE 核心库)：封装 BLE 扫描、连接、读写操作
- core-location (位置服务库)：封装 GPS 定位、地理编码
- feature-* (功能模块)：feature-home (主页)、feature-settings (设置)、feature-map (地图)

---

## 3. 技术栈选择

### 3.1 开发语言与框架

| 技术 | 版本 | 说明 |
|------|------|------|
| **语言** | Kotlin 1.9+ | 主开发语言 |
| **最低 API** | API 26 (Android 8.0) | 支持前台服务 |
| **目标 API** | API 34 (Android 14) | 最新兼容 |
| **构建工具** | Gradle 8.0+ | KTS 构建脚本 |

### 3.2 Android Jetpack 组件

| 组件 | 用途 |
|------|------|
| **Lifecycle** | 生命周期感知 |
| **ViewModel** | MVVM 状态管理 |
| **LiveData / StateFlow** | 响应式数据流 |
| **Room** | 本地数据库 (位置记录) |
| **DataStore** | 偏好设置存储 |
| **WorkManager** | 后台定时任务 |
| **Navigation** | 页面导航 |
| **Hilt** | 依赖注入 |

### 3.3 BLE 相关库

| 库 | 用途 | 选型理由 |
|------|------|----------|
| **Android BLE 原生 API** | BLE 核心功能 | 官方支持，功能完整 |
| **EasyBle** (可选) | BLE 简化封装 | 简化连接管理，处理碎片化 |

**推荐方案**：优先使用原生 BLE API，封装为 BleManager 单例。

### 3.4 地图 SDK

**推荐方案**：高德地图 Android SDK

| SDK | 版本 | 功能 |
|------|------|------|
| **高德地图基础 SDK** | 最新稳定版 | 地图展示、定位 |
| **高德地图搜索 SDK** | 最新稳定版 | 逆地理编码 |
| **高德地图导航 SDK** | 最新稳定版 | 唤起导航 |

**理由**：
- 国内地图数据更准确
- 定位精度高
- 免费额度充足
- 文档完善

### 3.5 其他依赖

| 类别 | 库 | 用途 |
|------|------|------|
| **网络** | OkHttp 4+ | HTTP 客户端 |
| **协程** | kotlinx-coroutines | 异步编程 |
| **UI** | Material Components | Material Design 组件 |
| **图片** | Coil | 图片加载 |
| **日志** | Timber | 日志工具 |

---

## 4. 核心模块设计

### 4.1 BLE 管理模块

#### 4.1.1 架构设计

BleManager 作为单例类，包含以下核心组件：
- BluetoothAdapter
- BluetoothLeScanner
- BluetoothLeAdvertiser
- GATT 连接映射表
- 设备回调映射表

核心方法：
- connect(device: BleDevice): Boolean
- disconnect(deviceAddress: String)
- startScan(filter: BleFilter): Flow<BleDevice>
- stopScan()
- writeCommand(address: String, cmd: ByteArray)
- setNotification(address: String, enable: Boolean)
- readRssi(address: String): Int

BleServiceWrapper 作为前台服务包装器，负责管理 BleManager 的生命周期。

#### 4.1.2 关键接口定义

```kotlin
// BLE 设备数据类
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val timestamp: Long,
    val manufacturerData: ByteArray?
)

// BLE 操作回调
interface BleCallback {
    fun onConnected(address: String)
    fun onDisconnected(address: String, status: Int)
    fun onServicesDiscovered(address: String, services: List<BluetoothGattService>)
    fun onCharacteristicChanged(address: String, uuid: String, value: ByteArray)
    fun onRssiRead(address: String, rssi: Int)
}

// BLE 管理器接口
interface IBleManager {
    suspend fun startScan(filter: BleFilter): Flow<BleDevice>
    fun stopScan()
    suspend fun connect(device: BleDevice): Result<BluetoothGatt>
    fun disconnect(address: String)
    suspend fun writeCommand(address: String, command: BleCommand): Result<Unit>
    fun enableNotification(address: String, characteristicUuid: String)
    fun getRssi(address: String): Int
}
```

#### 4.1.3 状态机设计

状态转换流程：
1. IDLE → SCANNING：调用 scan()
2. SCANNING → CONNECTING：调用 connect()
3. SCANNING → SCANNING：超时后继续扫描
4. CONNECTING → CONNECTED：连接成功
5. CONNECTING → SCANNING：连接失败，重新扫描
6. CONNECTED → DISCONNECTED：调用 disconnect() 或发生错误
7. DISCONNECTED：最终状态，可重新开始扫描

#### 4.1.4 UUID 常量定义

```kotlin
object BleUuids {
    // 服务 UUID
    val CONNECTION_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    
    // 特征 UUID
    val CONTROL_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val NOTIFICATION_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    
    // 命令值
    object Commands {
        const val PHOTO = 0xFF.toByte()
        const val MUTE = 0xFE.toByte()
        const val CONNECT_REQUEST = 0x01.toByte()
        const val DISCONNECT_REQUEST = 0x00.toByte()
    }
    
    // 响应值
    object Responses {
        const val BUTTON_PRESSED = 0x01.toByte()
        const val LOW_BATTERY = 0x02.toByte()
        const val CONNECTED = 0x03.toByte()
        const val DISCONNECTED = 0x04.toByte()
    }
}
```

### 4.2 RSSI 监控模块

#### 4.2.1 滑动平均滤波器

RssiFilter 类实现滑动平均滤波，窗口大小默认为 5。每次添加新的 RSSI 测量值时，返回窗口内的平均值。

#### 4.2.2 信号滞后比较器

RssiHysteresisComparator 类实现信号滞后比较，防止临界值抖动。默认阈值为 -75 dBm，滞后区间为 5 dBm。

状态定义：
- SAFE：安全状态
- WARNING：警告状态
- DANGER：危险状态
- DISCONNECTED：断开状态

#### 4.2.3 报警触发器

AlarmTrigger 类实现报警触发逻辑，需要连续触发 3 次（默认）才触发报警，避免误报。

#### 4.2.4 RSSI 监控器整体流程

RssiMonitor 类作为监控主控制器，实现以下流程：
1. 检查勿扰模式（WiFi/定时）
2. 读取 RSSI 原始值
3. 应用滑动平均滤波
4. 评估连接状态
5. 触发报警逻辑
6. 更新 UI 状态

采样间隔为 1000ms（1 秒）。

### 4.3 报警服务模块

#### 4.3.1 服务架构

MonitorForegroundService 作为前台服务，包含以下组件：
- NotificationManager：通知管理
- MediaPlayer：音频播放
- Vibrator：振动控制
- PowerManager.WakeLock：电源锁

#### 4.3.2 前台服务实现

服务实现要点：
1. 服务类型：FOREGROUND_SERVICE_TYPE_LOCATION + FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
2. 通知渠道：ble_monitor_channel
3. 通知优先级：PRIORITY_LOW
4. onStartCommand 返回 START_STICKY 确保服务被杀后重启
5. 使用 PARTIAL_WAKE_LOCK 保持 CPU 运行
6. 支持报警触发、停止报警、更新通知等功能

#### 4.3.3 报警类型

| 类型 | 触发条件 | 铃声特性 |
|------|----------|----------|
| **DISTANCE** | RSSI 低于阈值 | 持续响铃，音量 100% |
| **DISCONNECT** | 设备断开连接 | 急促提示音，3 次 |
| **LOW_BATTERY** | 设备电量低 | 温和提示音，1 次 |

### 4.4 设置管理模块

#### 4.4.1 存储方案

使用 AndroidX DataStore Preferences 存储用户设置，包括：
- 报警阈值 (alarm_threshold)
- 报警铃声 (alarm_ringtone)
- 振动开关 (vibration_enabled)
- 连续触发次数 (continuous_count)
- WiFi 勿扰开关 (wifi_dnd_enabled)
- 信任 WiFi 列表 (trusted_wifi_list)
- 定时勿扰设置 (timed_dnd_enabled, dnd_start_time, dnd_end_time)
- 其他设置

#### 4.4.2 勿扰模式检查

isDoNotDisturbActive() 方法检查当前是否处于勿扰模式：
1. 检查 WiFi 勿扰：当前连接的 WiFi SSID 是否在信任列表中
2. 检查定时勿扰：当前时间是否在设定的勿扰时间段内

---

## 5. 数据模型设计

### 5.1 Room 数据库实体

#### 5.1.1 BLE 设备表

表名：ble_devices

字段：
- address (主键)：设备 MAC 地址
- name：设备名称
- customName：用户自定义名称
- lastConnectedTime：最后连接时间
- lastRssi：最后信号强度
- batteryLevel：电量
- isFavorite：是否收藏
- createdAt：创建时间

#### 5.1.2 位置记录表

表名：location_records

字段：
- id (自增主键)：记录 ID
- deviceAddress：设备地址
- latitude：纬度
- longitude：经度
- accuracy：精度
- timestamp：时间戳
- addressDescription：地址描述（逆地理编码结果）
- triggerType：触发类型（DISTANCE/DISCONNECT/MANUAL）

### 5.2 领域模型

#### 5.2.1 BLE 设备模型

包含地址、名称、自定义名称、电量、最后连接时间等字段。

#### 5.2.2 位置记录模型

包含坐标（经纬度、精度）、时间戳、地址描述、触发类型。

#### 5.2.3 报警配置模型

AlarmConfig 包含阈值、滞后区间、连续触发次数、铃声 URI、振动开关、音量等配置。

### 5.3 数据传输对象 (DTO)

#### MonitorUiState

用于 UI 状态展示，包含设备信息、连接状态、RSSI 值、距离估计、监控状态、勿扰状态。

#### BleDeviceDisplay

用于界面显示的设备信息，包含地址、名称、自定义名称、电量、最后可见时间。

---

## 6. UI 界面设计

### 6.1 主界面

#### 6.1.1 布局结构

采用 CoordinatorLayout + AppBarLayout + ConstraintLayout + BottomNavigationView 结构。

主界面元素：
- 顶部工具栏：显示应用标题
- 设备状态卡片：设备图标、名称、连接状态、信号强度、距离估计
- 监控按钮：开始/停止监控
- 底部导航：主页、设备、我的

#### 6.1.2 状态颜色映射

| 状态 | 颜色代码 | 说明 |
|------|----------|------|
| SAFE | #4CAF50 (绿色) | 设备已连接 |
| WARNING | #FFC107 (黄色) | 设备距离较远 |
| DANGER | #FF9800 (橙色) | 设备即将断开 |
| DISCONNECTED | #F44336 (红色) | 设备已断开 |

### 6.2 地图界面

#### 6.2.1 布局设计

包含地图容器、位置信息卡片、导航按钮。

位置信息卡片显示：
- 最后位置地址
- 断开时间
- 距离当前手机的距离

#### 6.2.2 地图集成

使用高德地图 SDK 实现：
- 当前位置标记（蓝色）
- 设备最后位置标记（红色）
- 距离计算
- 唤起导航功能

### 6.3 设置界面

#### 6.3.1 PreferenceScreen 设计

设置项分类：

**设备管理**
- 已连接设备
- 添加新设备

**防丢设置**
- 报警阈值（滑块 -90~-50 dBm）
- 报警铃声选择
- 报警振动开关

**勿扰模式**
- WiFi 勿扰（开关、信任 WiFi 列表、添加当前 WiFi）
- 定时勿扰（开关、开始时间、结束时间）

**后台设置**
- 开机自启
- 电池优化豁免引导

---

## 7. 后台服务设计

### 7.1 服务生命周期管理

服务启动流程：
1. 应用启动
2. 检查权限，未授权则引导用户授权
3. 启动 MonitorForegroundService
4. 创建前台通知、获取 WakeLock、初始化 BLE Manager
5. 扫描并连接 BLE 设备
6. 连接成功后开始 RSSI 监控循环
7. 每秒读取 RSSI，进行滤波处理和状态判断
8. 根据状态触发报警或继续监控

### 7.2 自动重连机制

AutoReconnectManager 实现自动重连逻辑，支持三种重连场景：

| 场景 | 重连间隔 | 最大重试次数 | 超时时间 |
|------|----------|-------------|----------|
| 正常断开 | 5 秒 | 无限 | 30 秒 |
| 报警后断开 | 3 秒 | 10 次 | 15 秒 |
| 后台运行 | 30 秒 | 无限 | 60 秒 |

重连流程：
1. 检测设备断开事件
2. 检查勿扰模式
3. 根据场景选择重连配置
4. 按间隔扫描设备
5. 发现设备后发起连接
6. 连接成功后恢复监控
7. 达到最大重试次数后推送通知并暂停

### 7.3 开机自启

BootReceiver 监听 BOOT_COMPLETED 广播，检查是否启用开机自启设置，如启用则启动前台服务。

### 7.4 JobScheduler 定时唤醒

HeartbeatJobService 每 15 分钟检查一次服务状态，如服务已停止则自动重启。

### 7.5 厂商适配

VendorSpecificAdapter 针对不同 Android 厂商进行特殊适配：

| 厂商 | ROM | 适配措施 |
|------|------|----------|
| 小米 | MIUI | 引导开启自启动和后台运行权限 |
| 华为 | EMUI | 引导加入受保护应用名单 |
| OPPO | ColorOS | 引导开启允许后台活动 |
| VIVO | Funtouch | 引导开启后台高耗电 |

---

## 8. 权限清单

### 8.1 AndroidManifest.xml 配置

必需权限：
- BLUETOOTH / BLUETOOTH_ADMIN：BLE 基础功能
- BLUETOOTH_SCAN / BLUETOOTH_CONNECT：Android 12+ BLE 权限
- ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION：位置权限（Android 12-）
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION / FOREGROUND_SERVICE_CONNECTED_DEVICE：前台服务
- POST_NOTIFICATIONS：通知推送（Android 13+）
- WAKE_LOCK：电源锁
- RECEIVE_BOOT_COMPLETED：开机自启
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS：电池优化豁免
- ACCESS_WIFI_STATE / CHANGE_WIFI_STATE：WiFi 状态
- VIBRATE：振动

服务声明：
- MonitorForegroundService：监控前台服务
- HeartbeatJobService：定时心跳服务

广播接收器：
- BootReceiver：开机启动接收器

---

## 9. 项目目录结构

```
app/src/main/java/com/example/blelostfound/
├── BleLostFoundApp.kt              # Application 入口
├── di/                             # Hilt 依赖注入模块
│   ├── AppModule.kt
│   ├── BleModule.kt
│   └── LocationModule.kt
├── data/                           # 数据层
│   ├── local/                      # 本地数据源
│   │   ├── BleDeviceDao.kt
│   │   ├── LocationRecordDao.kt
│   │   └── AppDatabase.kt
│   ├── repository/                 # Repository 实现
│   │   ├── BleRepository.kt
│   │   ├── SettingsRepository.kt
│   │   └── LocationRepository.kt
│   └── model/                      # 数据模型
│       ├── BleDeviceEntity.kt
│       └── LocationRecordEntity.kt
├── domain/                         # 领域层
│   ├── model/                      # 领域模型
│   │   ├── BleDevice.kt
│   │   ├── LocationRecord.kt
│   │   └── AlarmConfig.kt
│   └── usecase/                    # 用例
│       ├── StartMonitoring.kt
│       ├── StopMonitoring.kt
│       └── GetDeviceLocation.kt
├── ble/                            # BLE 核心模块
│   ├── BleManager.kt
│   ├── BleScanner.kt
│   ├── BleCallback.kt
│   └── BleUuids.kt
├── monitor/                        # 监控模块
│   ├── RssiMonitor.kt
│   ├── RssiFilter.kt
│   ├── RssiHysteresisComparator.kt
│   └── AlarmTrigger.kt
├── service/                        # 后台服务
│   ├── MonitorForegroundService.kt
│   ├── HeartbeatJobService.kt
│   └── AlarmPlayer.kt
├── location/                       # 位置服务
│   ├── LocationManager.kt
│   ├── GeoCoder.kt
│   └── LocationUtils.kt
├── receiver/                       # 广播接收器
│   ├── BootReceiver.kt
│   └── WifiStateReceiver.kt
├── ui/                             # UI 层
│   ├── MainActivity.kt
│   ├── SettingsActivity.kt
│   ├── MapActivity.kt
│   ├── viewmodel/                  # ViewModel
│   │   ├── MainViewModel.kt
│   │   ├── SettingsViewModel.kt
│   │   └── MapViewModel.kt
│   ├── adapter/                    # RecyclerView 适配器
│   └── view/                       # 自定义 View
└── util/                           # 工具类
    ├── VendorSpecificAdapter.kt
    ├── PermissionHelper.kt
    └── NotificationHelper.kt
```

---

## 10. 修订历史

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-04-25 | 初始版本创建 | AI Assistant |

---

**文档结束**
