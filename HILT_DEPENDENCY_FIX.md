# Hilt 依赖注入 KSP 编译错误修复

## 问题描述

GitHub Actions 编译时出现 KSP 错误：
```
e: [ksp] InjectProcessingStep was unable to process 'HomeViewModel(..., DeviceRepository, error.NonExistentClass)' because 'error.NonExistentClass' could not be resolved.
```

## 根本原因

`error.NonExistentClass` 是 Hilt/KSP 在无法解析依赖时的典型错误。问题出在 `DeviceRepository` 的依赖链上：
- `DeviceRepository` 依赖 `BleDeviceDao` 和 `LocationRecordDao`
- 这些 DAO 由 Room 在编译时生成实现
- Hilt 需要在编译时知道所有依赖的完整类型信息

## 修复方案

### 1. 显式提供了 DeviceRepository

在 `DatabaseModule.kt` 中添加了 `provideDeviceRepository()` 方法，确保 Hilt 能够正确构建依赖链：

```kotlin
@Provides
@Singleton
fun provideDeviceRepository(
    bleDeviceDao: BleDeviceDao,
    locationRecordDao: LocationRecordDao
): DeviceRepository {
    return DeviceRepository(bleDeviceDao, locationRecordDao)
}