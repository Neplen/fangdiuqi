# 设置页数字输入修复报告

## 问题描述

RSSI 阈值和报警延迟两个设置项：
- ❌ 点击后只有输入法输入数字，没有确定键
- ❌ 更改无效，无法保存设置

## 修复方案

### 1. 布局修改 (`fragment_settings.xml`)

**修改前**：使用 `TextInputEditText` 输入框
```xml
<com.google.android.material.textfield.TextInputEditText
    android:id="@+id/et_rssi_threshold"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="-90"
    android:inputType="numberSigned" />
```

**修改后**：改为「显示 + 按钮」模式（与时间选择器一致）
```xml
<!-- RSSI 阈值显示 -->
<TextView
    android:id="@+id/tv_rssi_threshold"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="-90"
    android:textStyle="bold" />

<!-- 设置按钮 -->
<com.google.android.material.button.MaterialButton
    android:id="@+id/btn_set_rssi_threshold"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="设置 RSSI 阈值" />
```

### 2. SettingsFragment.kt 修改

#### 2.1 更新观察者
```kotlin
// 修改前
viewModel.rssiThreshold.collect { threshold ->
    binding.etRssiThreshold.setText(threshold.toString())
}

// 修改后
viewModel.rssiThreshold.collect { threshold ->
    binding.tvRssiThreshold.text = "$threshold dBm"
}
```

#### 2.2 添加弹窗方法
```kotlin
private fun showNumberPickerDialog(
    title: String,
    initialValue: Int,
    minValue: Int,
    maxValue: Int,
    unit: String,
    onConfirm: (Int) -> Unit
) {
    // 创建 EditText 用于输入数字
    val editText = android.widget.EditText(requireContext()).apply {
        setText(initialValue.toString())
        inputType = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED
        setPadding(48, 16, 48, 16)
        setHint("范围：$minValue ~ $maxValue$unit")
    }
    
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setView(editText)
        .setPositiveButton("确定") { _, _ ->
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                var value = text.toIntOrNull() ?: initialValue
                value = value.coerceIn(minValue, maxValue)
                onConfirm(value)
            }
        }
        .setNegativeButton("取消", null)
        .setNeutralButton("重置") { _, _ ->
            // 重置为默认值
            val newValue = when (title) {
                "设置 RSSI 阈值" -> -90
                "设置报警延迟" -> 60
                else -> initialValue
            }
            editText.setText(newValue.toString())
        }
        .show()
}
```

#### 2.3 更新点击监听器
```kotlin
// RSSI 阈值
binding.btnSetRssiThreshold.setOnClickListener {
    showNumberPickerDialog(
        title = "设置 RSSI 阈值",
        initialValue = viewModel.rssiThreshold.value,
        minValue = -100,
        maxValue = 0,
        unit = " dBm",
        onConfirm = { value ->
            viewModel.updateRssiThreshold(value)
        }
    )
}

// 报警延迟
binding.btnSetAlarmDelay.setOnClickListener {
    showNumberPickerDialog(
        title = "设置报警延迟",
        initialValue = viewModel.alarmDelay.value,
        minValue = 10,
        maxValue = 300,
        unit = " 秒",
        onConfirm = { value ->
            viewModel.updateAlarmDelay(value)
        }
    )
}
```

---

## 功能特性

### RSSI 阈值设置
- **范围**：-100 ~ 0 dBm
- **默认值**：-90 dBm
- **单位显示**：dBm
- **重置功能**：点击「重置」按钮恢复默认值 -90

### 报警延迟设置
- **范围**：10 ~ 300 秒
- **默认值**：60 秒
- **单位显示**：秒
- **重置功能**：点击「重置」按钮恢复默认值 60

---

## 用户体验改进

| 功能 | 修改前 | 修改后 |
|------|--------|--------|
| 输入方式 | 直接点击输入框 | 弹出对话框 |
| 确定按钮 | ❌ 无 | ✅ 有 |
| 取消按钮 | ❌ 无 | ✅ 有 |
| 重置按钮 | ❌ 无 | ✅ 有 |
| 范围提示 | ❌ 无 | ✅ 有 |
| 单位显示 | ✅ 有 (hint 中) | ✅ 有 (实时显示) |
| 样式一致性 | ❌ 与时间设置不一致 | ✅ 与时间设置一致 |

---

## 修改文件清单

1. **`/app/src/main/res/layout/fragment_settings.xml`**
   - 删除 `et_rssi_threshold` 和 `et_alarm_delay` 输入框
   - 添加 `tv_rssi_threshold` 和 `tv_alarm_delay` 显示文本
   - 添加 `btn_set_rssi_threshold` 和 `btn_set_alarm_delay` 按钮

2. **`/app/src/main/java/com/monkeycode/blelostfinder/ui/settings/SettingsFragment.kt`**
   - 修改观察者：从 `setText()` 改为更新 TextView
   - 删除 `onFocusChangeListener` 监听器
   - 添加 `showNumberPickerDialog()` 方法
   - 修改点击监听器：从 EditText 点击改为按钮点击

---

## 测试建议

### 1. 基础功能测试
- [ ] 点击「设置 RSSI 阈值」→ 弹出对话框
- [ ] 输入 -80 → 点击确定 → 显示更新为「-80 dBm」
- [ ] 点击「取消」→ 对话框关闭 → 值不变
- [ ] 点击「重置」→ 恢复为 -90 dBm

### 2. 边界值测试
- [ ] 输入 -101 → 自动限制为 -100
- [ ] 输入 1 → 自动限制为 0
- [ ] 输入 5 → 自动限制为 10
- [ ] 输入 301 → 自动限制为 300

### 3. 实时生效测试
- [ ] 修改 RSSI 阈值 → 后台服务立即生效
- [ ] 修改报警延迟 → 后台服务立即生效

---

## 注意事项

1. **输入验证**：
   - 用户输入超出范围的值会自动限制在有效范围内
   - 输入非数字会保持原值不变

2. **重置功能**：
   - 点击「重置」按钮不会立即关闭对话框
   - 用户可以修改重置后的值再点击确定

3. **单位显示**：
   - RSSI 阈值：实时显示「XX dBm」
   - 报警延迟：实时显示「XX 秒」

4. **与时间设置一致性**：
   - 现在 RSSI 和延迟的设置方式与时间设置完全一致
   - 都是「显示 + 按钮 + 弹窗」的模式
