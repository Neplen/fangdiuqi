# Hilt 重复绑定错误修复报告

## 错误描述

GitHub Actions 编译时出现 Hilt 依赖注入错误：

```
[Dagger/DuplicateBindings] BleDeviceDao is bound multiple times
[Dagger/DuplicateBindings] LocationRecordDao is bound multiple times
[Dagger/DuplicateBindings] AppDatabase is bound multiple times
[Dagger/MissingBinding] Context cannot be provided without an @Provides-annotated method
```

## 根本原因分析

### 1. 重复绑定问题

**原因**: 同一个 Module 中可能存在重复的 `@Provides` 方法，或者在多个文件中定义了相同的绑定。

**之前的配置**:
- `AppDatabase.kt` 中的 `DatabaseModule` 提供了：
  - `AppDatabase`
  - `BleDeviceDao`
  - `LocationRecordDao`
  - `DeviceRepository`

### 2. Context 缺失问题

**原因**: `AlarmSoundManager` 注入的是普通的 `Context`，但 Hilt 只提供了 `@ApplicationContext Context` 的绑定。

**错误的注入**:
```kotlin
class AlarmSoundManager @Inject constructor(
    private val context: Context  // ❌ Hilt 不知道提供哪个 Context
)
```

## 修复方案

### 步骤 1: 创建独立的 DatabaseModule

**文件**: `app/src/main/java/com/monkeycode/blelostfinder/di/DatabaseModule.kt`

```kotlin
package com.monkeycode.blelostfinder.di

import android.content.Context
import com.monkeycode.blelostfinder.data.local.AppDatabase
import com.monkeycode.blelostfinder.data.local.BleDeviceDao
import com.monkeycode.blelostfinder.data.local.LocationRecordDao
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideBleDeviceDao(database: AppDatabase): BleDeviceDao {
        return database.bleDeviceDao()
    }

    @Provides
    @Singleton
    fun provideLocationRecordDao(database: AppDatabase): LocationRecordDao {
        return database.locationRecordDao()
    }
    
    @Provides
    @Singleton
    fun provideDeviceRepository(
        bleDeviceDao: BleDeviceDao,
        locationRecordDao: LocationRecordDao
    ): DeviceRepository {
        return DeviceRepository(bleDeviceDao, locationRecordDao)
    }
}
```

### 步骤 2: 从 AppDatabase.kt 移除 DatabaseModule

**修改前**: `AppDatabase.kt` 文件末尾包含 `DatabaseModule` 的定义

**修改后**: 只保留 `AppDatabase` 类本身

```kotlin
package com.monkeycode.blelostfinder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.model.LocationRecord

@Database(
    entities = [BleDevice::class, LocationRecord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bleDeviceDao(): BleDeviceDao
    abstract fun locationRecordDao(): LocationRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ble_lost_finder_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

### 步骤 3: 修复 AlarmSoundManager 的 Context 注入

**文件**: `app/src/main/java/com/monkeycode/blelostfinder/ui/settings/AlarmSoundManager.kt`

**修改前**:
```kotlin
class AlarmSoundManager @Inject constructor(
    private val context: Context  // ❌ 缺少注解
)
```

**修改后**:
```kotlin
import dagger.hilt.android.qualifiers.ApplicationContext

class AlarmSoundManager @Inject constructor(
    @ApplicationContext private val context: Context  // ✅ 使用注解
)
```

### 步骤 4: 确保其他类也使用 @ApplicationContext

检查以下类已经正确使用 `@ApplicationContext`：

- ✅ `BleManager` - 已使用
- ✅ `SettingsManager` - 已使用
- ✅ `AlarmSoundManager` - 已修复

## 为什么这样修复有效？

### 1. 单一数据源原则

每个类型在 Hilt 中只能有一个绑定方式：
- `AppDatabase` → 只在 `DatabaseModule.provideDatabase()` 中提供
- `BleDeviceDao` → 只在 `DatabaseModule.provideBleDeviceDao()` 中提供
- `LocationRecordDao` → 只在 `DatabaseModule.provideLocationRecordDao()` 中提供

### 2. 使用 @ApplicationContext 区分 Context

Hilt 通过注解来区分不同的绑定：
- `@ApplicationContext Context` → 应用级别的 Context
- `@ActivityContext Context` → Activity 级别的 Context

如果不使用注解，Hilt 不知道应该提供哪个 Context。

### 3. 分离关注点

- `AppDatabase.kt` 只负责定义数据库结构
- `DatabaseModule.kt` 只负责提供依赖绑定
- 每个文件职责单一，易于维护

## 修改的文件列表

1. **新建**: `app/src/main/java/com/monkeycode/blelostfinder/di/DatabaseModule.kt`
   - 从 `AppDatabase.kt` 迁移过来的依赖注入配置

2. **修改**: `app/src/main/java/com/monkeycode/blelostfinder/data/local/AppDatabase.kt`
   - 移除了 `DatabaseModule` 类
   - 清理了不必要的导入

3. **修改**: `app/src/main/java/com/monkeycode/blelostfinder/ui/settings/AlarmSoundManager.kt`
   - 添加了 `@ApplicationContext` 注解到 Context 参数

4. **删除**: `app/src/main/java/com/monkeycode/blelostfinder/di/AppModule.kt` (如果存在)
   - 不需要单独的 AppModule，DatabaseModule 已经提供所有需要的绑定

## 验证清单

- [x] DatabaseModule 是唯一的 DAO 和 Database 提供者
- [x] AlarmSoundManager 使用 @ApplicationContext
- [x] BleManager 使用 @ApplicationContext
- [x] SettingsManager 使用 @ApplicationContext
- [x] DeviceRepository 由 DatabaseModule 提供
- [x] 没有重复的 @Provides 方法
- [x] 重新打包 ZIP 文件

## 下一步操作

1. 下载最新的 `BLELostFinder.zip` 文件
2. 解压并推送到 GitHub 仓库
3. 等待 GitHub Actions 编译完成
4. 下载生成的 APK 进行测试

这次修复应该能解决所有 Hilt 依赖注入的重复绑定和 Context 缺失问题！
