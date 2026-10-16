# 设计模式参考

## 1. Settings/RunConfiguration 中的 Usage Template（使用模板）

### 来源

参考 EmmyLua 插件的 `EmmyDebugSettingsPanel`（[GitHub - IntelliJ-EmmyLua](https://github.com/EmmyLua/IntelliJ-EmmyLua)）。

### 模式描述

在 Run Configuration 编辑器中嵌入一个**只读的代码编辑器**，根据用户配置的参数（Host、Port、连接模式等）**动态生成**一段代码片段，让用户可以直接复制粘贴到自己的项目中。

### 核心 API

```kotlin
// 1. 创建只读文档
val document = EditorFactory.getInstance().createDocument(initialCode)

// 2. 创建只读编辑器（带语法高亮）
val luaFileType = FileTypeManager.getInstance().getFileTypeByExtension("lua")
val editor = EditorFactory.getInstance().createEditor(
    document, project, luaFileType,
    true  // isViewer = true → 只读
) as EditorEx

// 3. 配置编辑器外观（紧凑模式）
editor.settings.apply {
    isLineNumbersShown = true
    isFoldingOutlineShown = false
    isAdditionalPageAtBottom = false
    isCaretRowShown = false
    isRightMarginShown = false
}

// 4. 动态更新文档内容
ApplicationManager.getApplication().runWriteAction {
    document.setText(newCode)
}

// 5. 释放编辑器（必须！否则内存泄漏）
EditorFactory.getInstance().releaseEditor(editor)
```

### 本项目实现

- **`UsageTemplatePanel`**（`src/main/kotlin/com/luapanda/idea/debugger/UsageTemplatePanel.kt`）
  - 独立的可复用组件，封装了编辑器创建、更新、释放的全部逻辑
  - 构造参数：`project`、`initialCode`、`editorHeight`
  - 公开方法：`updateTemplate(code: String)`、`dispose()`
  - 公开属性：`component`（可嵌入父布局）、`headerLabel`、`hintLabel`（可自定义文字）

- **`LuaPandaRunConfigurationEditor`**（`src/main/kotlin/com/luapanda/idea/debugger/LuaPandaRunConfigurationEditor.kt`）
  - 使用 `UsageTemplatePanel` 展示 LuaPanda 接入代码
  - 通过 Swing `DocumentListener` 和 `ActionListener` 监听 Host/Port/ConnectionMode 变化
  - 在 `disposeEditor()` 中调用 `UsageTemplatePanel.dispose()` 释放资源

### 生成的模板示例

**Server 模式（IDE 监听，Lua 连接 IDE）：**

```lua
-- Add the following code at the entry point of your Lua program
-- Change the require path to match your LuaPanda.lua location
-- Server mode: IDE listens on port 8818, Lua connects to IDE
-- Step 1: Click Debug in IDE  →  Step 2: Run your Lua program
require("xx/LuaPanda").start("127.0.0.1", 8818)
```

**Client 模式（Lua 监听，IDE 连接 Lua）：**

```lua
-- Add the following code at the entry point of your Lua program
-- Change the require path to match your LuaPanda.lua location
-- Client mode: Lua listens on port 8818, IDE connects to Lua
-- Step 1: Run your Lua program  →  Step 2: Click Debug in IDE
require("xx/LuaPanda").startServer("127.0.0.1", 8818)
```

> **API 映射关系**：LuaPanda.lua 中 `start()` = Lua 作为 Client 连接 IDE；`startServer()` = Lua 作为 Server 监听等待 IDE 连接。`require` 路径使用 `"xx/LuaPanda"` 提示用户需要替换为实际文件路径。

### 注意事项

1. **必须释放编辑器**：`EditorFactory.createEditor()` 创建的编辑器不会自动 GC，必须在组件销毁时调用 `releaseEditor()`
2. **SettingsEditor.dispose() 是 final 的**：不能 override `dispose()`，应在 `disposeEditor()` 中释放资源
3. **文档更新需要 WriteAction**：`document.setText()` 必须在 `runWriteAction` 中执行
4. **语法高亮回退**：如果用户未安装 Lua 语言支持插件，`getFileTypeByExtension("lua")` 会返回 `UnknownFileType`，编辑器退化为纯文本显示，不会报错
5. **Swing DocumentListener ≠ IntelliJ DocumentListener**：监听 JBTextField 变化用 `javax.swing.event.DocumentListener`，监听 IntelliJ Document 用 `com.intellij.openapi.editor.event.DocumentListener`，注意不要混淆
