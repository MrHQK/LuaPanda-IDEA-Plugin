# 更新日志

所有重要变更将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.0.3] - 2026-10-19

### 修复
- **移除对 IntelliJ 内部 API 的引用**：Marketplace 的 Plugin Verifier 报 `Internal API usage` 并拒绝上架。以下内部类全部替换为公开 API：
  - `com.intellij.openapi.editor.impl.DocumentImpl` → `EditorFactory.getInstance().createDocument()`
  - `com.intellij.xdebugger.impl.XSourcePositionImpl.create()` → `XDebuggerUtil.getInstance().createPosition()`
  - `com.intellij.xdebugger.impl.breakpoints.XExpressionImpl.fromText()` → 新增 `LuaXExpression`（基于公开的 `XExpression` 接口实现）
  - `XDebugSessionImpl.sessionTab.watchesView` 没有公开替代（平台不提供编程式添加 Watch 的 API），改为反射解析；解析失败时自动降级为把表达式复制到剪贴板

---

## [1.0.2] - 2026-10-15

### 新增
- **按变量名搜索当前堆栈帧变量**：断点暂停时，可通过调试窗口工具栏按钮 / Variables 面板工具栏与右键菜单 / **Run → Search Lua Variables...** 打开搜索窗口，按变量名（或完整路径，如 `self.hp`）过滤当前帧的 Locals / Upvalues / Globals。支持指定 table 展开深度（默认 2 层）、区分大小写与正则匹配；结果列表可高亮匹配内容、双击或右键加入 Watches，也可复制路径 / 名称 / 值。
  - 新增 `LuaVariableCollector`：基于既有 `getVariable` 协议逐条采集并递归展开 table，单次采集最多 500 次请求（避免 Lua 侧调用层级过深），构建可直接用于 Evaluate / Watch 的访问路径（如 `self.data["hp max"][1]`）。
  - 新增 `LuaVariableSearchDialog`：非模态搜索窗口，每次暂停 / 切换堆栈帧自动重新采集，输入搜索词只在本地过滤；程序恢复运行或调试结束时自动失效 / 关闭。
  - 新增 `SearchLuaVariablesAction`（`LuaPanda.SearchVariables`），仅在 LuaPanda 会话暂停时可用。

### 修复
- **演示点 5「调用堆栈」缺少 `stack_level_1` / `stack_level_2` 两层帧**：这两层原本写成 `return f(...)`（Lua 尾调用），运行时会复用调用者栈帧 —— 它们在运行时并不存在，因此 LuaPanda 的 `getStackTable()`（基于 `debug.getinfo`）无法上报，Frames 面板看起来像「丢帧」。改为先赋值再 `return`（非尾调用）后三层栈帧真正存在，Frames 面板显示与运行一致。

### 上架准备
- 修正 Marketplace 插件描述：移除插件并不提供的语言特性（代码补全 / 跳转定义 / 语法高亮 / 结构视图），明确「专注调试、语言特性交给 EmmyLua」。
- 构建时补齐 `changeNotes`：此前 `change-notes` 未被 `patchPluginXml` 覆盖，发布新版本会沿用旧版更新说明。
- 版本号提升至 `1.0.2`；统一插件显示名为 `LuaPanda`（源码 `plugin.xml` 与构建产物一致）。

### 文档
- `docs/UserGuide.md` 新增"按变量名搜索"章节

---

## [1.0.1] - 2026-05-28

### 修复
- **僵尸 socket 死循环**：远程调试（RTT > 5ms）场景下，Lua 默认 `connectTimeoutSec=0.005` 会导致 IDEA 端 accept 拿到僵尸 socket 后陷入 `Connection reset` 重连循环（实测 10000+ 次）。本版本将 Server 重写为并发-accept 模型并对齐 VSCode 行为：握手未完成的连接会被静默替换/丢弃，listener 在握手回包前保持开放。**远程调试不再需要手动延长 connectTimeoutSec**。
- **Hover 表达式求值显示错误**：在 `xx.001.002` 中悬浮 `001` 段时，tooltip 错误地显示 `002` 的值（向右扩展逻辑过度吞噬）。修复后，悬浮 `self` 显示 `self`、悬浮 `health` 显示 `self.health`、悬浮 `max` 显示 `self.health.max`，与 IntelliJ Java/Kotlin 调试器行为一致。

### 体验
- **Console 提示时机**："LuaPanda connected" 不再在 TCP accept 时打印，而是延迟到收到 `initSuccess` 回包（真正握手成功）后才打印，避免远程场景下的误导性 flicker。

### 工具
- **一键打包脚本** `scripts/pack.ps1` + `scripts/pack.bat`：
  - 自动配置 JAVA_HOME，跑 `gradlew buildPlugin`
  - 自动定位产物 zip 并复制一份到项目根目录
  - 可选 `-Clean`（全量构建）/ `-Deploy`（自动部署到本机 IDEA）/ `-OpenFolder`
  - `-Deploy` 会检测 IDEA 是否在跑，避免 jar 锁

### 文档
- README "从源码构建" 章节新增"一键打包"小节
- 修正 README 产物文件名：`LuaPanda-1.0.0.zip` → `LuaPanda-IDEA-Plugin-1.0.1.zip`（IntelliJ Platform Gradle Plugin 的命名规则取的是 `rootProject.name` 而非 `pluginName`）

### 实现细节
- `LuaPandaServer.kt` 整体重写为并发-accept 模型，新增 `handshakeComplete` 状态与 `markHandshakeComplete()` / `resetHandshakeState()` API
- `LuaPandaStackFrame.kt::getExpressionInfoAtOffset` 删除右侧扩展逻辑，附带删除不再使用的 `Char.isLuaIdentifierStart()` 辅助函数

---

## [1.0.0] - 2026-04-20

### 首个正式版本 🎉

#### 调试功能
- **断点支持**：行断点、条件断点、日志断点 (Logpoint)
- **执行控制**：Continue、Step Over、Step Into、Step Out、Pause
- **变量查看**：Locals / Upvalues / Globals 三组分类，支持 table 展开
- **表达式求值**：Evaluate Expression 对话框、Watch 面板、REPL 控制台
- **调用堆栈**：完整 Lua 调用链导航，点击可跳转源码
- **双连接模式**：
  - Server 模式（IDE 监听，Lua 连接）— 适用于本地/模拟器调试
  - Client 模式（Lua 监听，IDE 连接）— 适用于真机/远程调试
- **使用模板**：Run Configuration 编辑器中内嵌实时代码模板，根据配置自动生成接入代码
- **C Hook 库支持**：内置预编译 libpdebug 库（Win x86/x64、Mac x64/arm64，Lua 5.1/5.3/5.4），自动解压到临时目录
- **EmmyLua 兼容**：自动适配 EmmyLua 断点，两个插件可同时安装、互不冲突

#### 代码质量
- **lua-fmt 集成**：一键格式化 Lua 代码
- **luacheck 集成**：编辑时自动运行静态分析，诊断结果实时显示在编辑器中

#### 协议兼容
- 完全兼容 Tencent/LuaPanda 原版 TCP/JSON 调试协议（v3.2.0+）
- 兼容 Lua 5.1 ~ 5.4

#### 性能优化
- 文件扫描使用 IntelliJ FilenameIndex 替代手动遍历，37,000+ 文件从 ~98 秒优化至毫秒级
- 断点同步使用 100ms debounce + 批量发送，减少 TCP 往返
- PathResolver 引入 negative cache（30s TTL），避免重复磁盘 IO
- 协议读取使用 ArrayDeque + indexOf 替代 ArrayList + split

#### 系统要求
- IntelliJ IDEA 2024.1+（Community 或 Ultimate）
- JDK 17+
- Lua 运行时需支持 LuaSocket
