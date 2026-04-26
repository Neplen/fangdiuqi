# Hilt 依赖注入错误修复

## 错误分析

### 1. 重复绑定错误
```
[Dagger/DuplicateBindings] BleDeviceDao is bound multiple times
[Dagger/DuplicateBindings] LocationRecordDao is bound multiple times  
[Dagger/DuplicateBindings] AppDatabase is bound multiple times
```

**原因**: Room 的 `@HiltDatabase` 或自动绑定可能与手动的 `@Provides` 方法冲突

### 2. Context 缺失错误
```
[Dagger/MissingBinding] android.content.Context cannot be provided without an @Provides-annotated method
```

**原因**: `AlarmSoundManager` 需要 `Context`，但当前只有 `@ApplicationContext Context` 的绑定

## 修复方案

### 方案 1: 使用 Hilt 的自动绑定（推荐）

Room 2.4+ 支持与 Hilt 自动集成，无需手动提供 DAO。

### 方案 2: 添加 Context 提供方法

在 `DatabaseModule` 中添加 `provideContext()` 方法。

### 方案 3: 移除 DatabaseModule（最佳方案）

使用 Hilt 与 Room 的标准集成方式，让 Hilt 自动管理。

## 执行步骤

1. 移除 `AppDatabase.kt` 中的 `DatabaseModule`
2. 创建单独的 `AppModule.kt` 提供 `Context`
3. 确保 `BleManager` 和 `AlarmSoundManager` 使用 `@ApplicationContext`
