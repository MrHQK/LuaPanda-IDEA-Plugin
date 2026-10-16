# LuaPanda IDEA Plugin — Lua 调试技术原理文档

> 本文档基于项目源码分析，详细阐述 LuaPanda IDEA 插件的 Lua 远程调试技术原理，涵盖整体架构、通信协议、调试生命周期、断点机制、变量查看、路径映射、性能优化等核心技术点。

---

## 目录

- [1. 总体架构](#1-总体架构)
- [2. 调试原理概述](#2-调试原理概述)
- [3. IntelliJ XDebugger 框架集成](#3-intellij-xdebugger-框架集成)
- [4. TCP 通信层](#4-tcp-通信层)
- [5. 自定义协议详解](#5-自定义协议详解)
- [6. 调试会话生命周期](#6-调试会话生命周期)
- [7. 断点系统](#7-断点系统)
- [8. 执行控制（Step / Continue / Stop）](#8-执行控制stepcontinuestop)
- [9. 调用栈与变量查看](#9-调用栈与变量查看)
- [10. 表达式求值与 REPL](#10-表达式求值与-repl)
- [11. 路径映射系统](#11-路径映射系统)
- [12. C Hook Library (libpdebug)](#12-c-hook-library-libpdebug)
- [13. EmmyLua 兼容性](#13-emmylua-兼容性)
- [14. 性能优化](#14-性能优化)
- [15. 关键数据流图](#15-关键数据流图)

---

## 1. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                     IntelliJ IDEA Platform                    │
│  ┌───────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ XDebugger API │  │  Actions API │  │ Settings/Services │  │
│  └───────┬───────┘  └──────┬───────┘  └────────┬──────────┘  │
└──────────┼──────────────────┼──────────────────┼─────────────┘
           │                  │                  │
     ┌─────▼───────────┐ ┌───▼──────────┐ ┌─────▼──────────────┐
     │   debugger/     │ │  actions/    │ │    settings/       │
     │                 │ │  formatter/  │ │                    │
     │ DebugProcess ◄──┼─┤  annotator/  │ │ LuaPandaSettings   │
     │ StackFrame      │ │              │ │ SettingsConfigurable│
     │ RunConfig       │ │ CopyAction   │ └────────────────────┘
     │ Breakpoints     │ │ LuaFmt       │
     │ ProgramRunner   │ │ Luacheck     │
     └─────┬───────────┘ └──────────────┘
           │
     ┌─────▼───────────┐
     │   protocol/     │
     │                 │
     │ Server / Client │ ◄──── TCP / JSON ────► LuaPanda.lua (Lua 运行时)
     │ Processor       │
     │ PathResolver    │
     │ Messages        │
     └─────────────────┘
```

插件由 **6 个包** 组成：

| 包 | 职责 |
|---|------|
| `debugger` | 调试核心：会话管理、断点处理、栈帧查看、运行配置 |
| `protocol` | 通信层：TCP 服务器/客户端、协议帧处理、路径映射 |
| `actions` | 用户动作：复制 LuaPanda.lua 到项目 |
| `formatter` | 代码格式化：集成 lua-fmt |
| `annotator` | 代码诊断：集成 luacheck |
| `settings` | 全局设置：工具路径、默认端口等持久化配置 |

---

## 2. 调试原理概述

### 2.1 Lua 调试的核心机制

Lua 语言内置了 `debug` 库，提供了 `debug.sethook()` 函数，允许注册一个回调函数，在以下事件发生时被调用：

- **`call`** — 函数调用时触发
- **`return`** — 函数返回时触发
- **`line`** — 每执行一行 Lua 代码时触发

LuaPanda 的 Lua 端（`LuaPanda.lua`）利用 `debug.sethook(hook_function, "lrc")` 注册了一个钩子函数，拦截所有行事件、调用事件和返回事件。当钩子被触发时：

1. **检查当前行是否有断点** → 如果有，暂停执行并通知 IDE
2. **检查是否处于单步执行模式** → 如果是，暂停执行并通知 IDE
3. **正常情况** → 直接返回，Lua 继续执行

### 2.2 远程调试架构

LuaPanda 采用 **TCP/JSON** 远程调试架构，IDE（调试器前端）和 Lua 运行时（调试器后端）通过 TCP Socket 通信：

```
┌─────────────────┐         TCP Socket          ┌─────────────────────┐
│  IntelliJ IDEA  │ ◄═══════════════════════════►│   Lua 运行时        │
│  (调试前端)      │     JSON over TCP            │   (LuaPanda.lua)   │
│                 │                              │   (调试后端)         │
│  发送命令:       │  ────  initSuccess  ────►   │  注册 debug.sethook │
│  设置断点        │  ────  setBreakPoint ────►   │  管理断点表          │
│  继续/单步       │  ────  continue     ────►   │  控制执行状态        │
│                 │                              │                     │
│  接收事件:       │  ◄──── stopOnBreakpoint ──── │  Hook 检测到断点     │
│  显示栈帧        │  ◄──── getVariable回复  ──── │  收集变量信息        │
│  显示变量        │  ◄──── output         ──── │  输出日志            │
└─────────────────┘                              └─────────────────────┘
```

### 2.3 两种连接模式

插件支持两种 TCP 连接模式，通过 `LuaPandaRunConfiguration.ConnectionMode` 枚举定义：

| 模式 | 方向 | 适用场景 |
|------|------|---------|
| **SERVER**（标准模式） | IDE 监听端口，Lua 端连接 | 本地开发、PC 端调试 |
| **CLIENT**（反向连接模式） | IDE 作为客户端连接 Lua 端 | 真机调试、移动设备调试 |

---

## 3. IntelliJ XDebugger 框架集成

### 3.1 核心类映射

IntelliJ Platform 提供了完整的 `XDebugger` API，插件通过继承这些抽象类来集成调试功能：

| IntelliJ API | 插件实现 | 职责 |
|-------------|---------|------|
| `XDebugProcess` | `LuaPandaDebugProcess` | 调试会话的核心控制器，管理生命周期 |
| `XBreakpointHandler` | `LuaPandaBreakpointHandler` | 处理断点的增删改 |
| `XBreakpointType` | `LuaPandaBreakpointType` | 定义断点类型（ID: `luapanda-line`） |
| `XBreakpointProperties` | `LuaPandaBreakpointProperties` | 断点属性容器 |
| `XStackFrame` | `LuaPandaStackFrame` | 栈帧展示、变量查看、表达式求值 |
| `XSuspendContext` | `LuaPandaSuspendContext` | 暂停上下文，包含执行栈信息 |
| `XExecutionStack` | `LuaPandaExecutionStack` | 执行栈（调用链） |
| `XDebuggerEvaluator` | `LuaPandaEvaluator`（内部类） | 表达式求值器 |
| `XDebuggerEditorsProvider` | `LuaPandaDebuggerEditorsProvider` | 表达式求值编辑器提供者 |
| `RunConfiguration` | `LuaPandaRunConfiguration` | 运行配置数据模型 |
| `ProgramRunner` | `LuaPandaProgramRunner` | 启动调试会话的执行器 |

### 3.2 注册机制

通过 `plugin.xml` 向 IntelliJ 平台注册扩展点：

```xml
<extensions defaultExtensionNs="com.intellij">
    <configurationType implementation="...LuaPandaRunConfigurationType"/>
    <programRunner implementation="...LuaPandaProgramRunner"/>
    <xdebugger.breakpointType implementation="...LuaPandaBreakpointType"/>
</extensions>
```

### 3.3 RunConfigurationWithSuppressedDefaultRunAction

`LuaPandaRunConfiguration` 实现了 `RunConfigurationWithSuppressedDefaultRunAction` 标记接口，阻止 IntelliJ 内置的 `DefaultRunProgramRunner` 接管配置，使 **Run 按钮置灰**，**只保留 Debug 按钮**。这是因为 LuaPanda 是纯调试工具，没有独立的"运行"概念。

---

## 4. TCP 通信层

### 4.1 服务器模式（`LuaPandaServer`）

```kotlin
class LuaPandaServer(port: Int, listener: ServerListener)
```

**工作流程：**

1. 创建 `ServerSocket` 监听指定端口（默认 8818）
2. 在循环中 `accept()` 等待 Lua 端连接
3. 连接建立后调用 `handleClient(socket)`：
   - 创建 `ProtocolProcessor` 处理协议帧
   - 调用 `initStreams()` 初始化 I/O
   - 通知 `onClientConnected` 回调（触发握手）
   - 进入 `startReadLoop()` 阻塞读取消息
4. 支持自动重连：客户端断开后继续 `accept()` 等待新连接

### 4.2 客户端模式（`LuaPandaClient`）

```kotlin
class LuaPandaClient(host: String, port: Int, listener: ClientListener)
```

**工作流程：**

1. 创建 `Socket(host, port)` 连接 Lua 端
2. 创建 `ProtocolProcessor` 处理协议帧
3. 调用 `initStreams()` 初始化 I/O
4. 通知 `onConnected` 回调（触发握手）
5. 进入 `startReadLoop()` 阻塞读取消息

### 4.3 关键设计：先初始化流，后进入读循环

```
initStreams()          → 设置 reader/writer（此后可发送消息）
↓
onClientConnected()    → 发送 initSuccess 握手
↓
startReadLoop()        → 进入阻塞读取循环
```

这种分离设计至关重要：`initStreams()` 和 `startReadLoop()` 解耦后，握手命令可以在进入读循环之前发送。如果合并为一步，握手消息将无法在连接建立的第一时间发出。

### 4.4 TCP_NODELAY 优化

```kotlin
socket.tcpNoDelay = true  // 禁用 Nagle 算法
```

LuaPanda.lua 在 `debugger_wait_msg()` 中阻塞等待 `initSuccess` 命令。如果不禁用 Nagle 算法，小消息可能被延迟合并发送，导致握手出现可感知的延迟。

---

## 5. 自定义协议详解

### 5.1 消息帧格式

LuaPanda 使用自定义的文本帧格式（非标准的 DAP 协议）：

```
[JSON 字符串]|*|\n
```

- `|*|` 是帧分隔符（`TCPSplitChar`）
- 每条消息以 `\n` 结尾
- 多条消息可能粘连在同一个 TCP 报文中（粘包）

### 5.2 消息 JSON 结构

```json
{
    "cmd": "<命令名>",
    "callbackId": "<回调ID>",
    "info": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `cmd` | string | 命令/事件名称 |
| `callbackId` | string | 回调匹配 ID，"0" 表示无回调 |
| `info` | object/array | 数据负载，类型随命令不同而变 |

### 5.3 协议命令清单

#### IDE → Lua 命令

| 命令 | 常量 | 用途 |
|------|------|------|
| `initSuccess` | `CMD_INIT_SUCCESS` | 初始化握手 |
| `continue` | `CMD_CONTINUE` | 继续执行 |
| `stopOnStep` | `CMD_STEP_OVER` | 单步跳过（Step Over） |
| `stopOnStepIn` | `CMD_STEP_INTO` | 单步进入（Step Into） |
| `stopOnStepOut` | `CMD_STEP_OUT` | 单步跳出（Step Out） |
| `stopRun` | `CMD_STOP` | 停止调试 |
| `setBreakPoint` | `CMD_SET_BREAKPOINTS` | 同步断点 |
| `getVariable` | `CMD_GET_VARIABLE` | 获取变量信息 |
| `getWatchedVariable` | `CMD_GET_WATCHED_VARIABLE` | 表达式求值 |
| `setVariable` | `CMD_SET_VARIABLE` | 修改变量值 |
| `runREPLExpression` | `CMD_RUN_REPL` | 执行 REPL 表达式 |
| `LuaGarbageCollect` | `CMD_LUA_GC` | 强制 Lua GC |

#### Lua → IDE 事件

| 事件 | 常量 | 用途 |
|------|------|------|
| `stopOnCodeBreakpoint` | `EVT_STOP_ON_CODE_BREAKPOINT` | 命中代码断点 |
| `stopOnBreakpoint` | `EVT_STOP_ON_BREAKPOINT` | 命中手动 `BP()` 断点 |
| `stopOnEntry` | `EVT_STOP_ON_ENTRY` | 入口停止 |
| `stopOnStep` | `EVT_STOP_ON_STEP` | Step Over 完成 |
| `stopOnStepIn` | `EVT_STOP_ON_STEP_IN` | Step Into 完成 |
| `stopOnStepOut` | `EVT_STOP_ON_STEP_OUT` | Step Out 完成 |
| `output` | `EVT_OUTPUT` | 控制台输出 |
| `debug_console` | `EVT_DEBUG_CONSOLE` | 调试控制台输出 |
| `tip` / `tipError` | `EVT_TIP` / `EVT_TIP_ERROR` | 通知/错误提示 |
| `refreshLuaMemory` | `EVT_REFRESH_LUA_MEMORY` | 内存刷新信息 |

### 5.4 协议帧处理器 (`ProtocolProcessor`)

#### 粘包与分包处理

TCP 是流协议，不保证消息边界。`ProtocolProcessor.readLoop()` 使用 `ArrayDeque` + `indexOf` 分割消息：

```
接收到的 TCP 数据:
  {"cmd":"A"}|*|\n{"cmd":"B"}|*|\n{"cmd":

分割后的完整消息:
  recvMsgQueue = [{"cmd":"A"}, {"cmd":"B"}]
  cutoffString = {"cmd":    ← 不完整的部分，等待下一次读取
```

关键实现细节：
- 使用 `ArrayDeque` 而非 `ArrayList`，`poll()` 操作为 O(1)
- 使用 `indexOf` + `substring` 而非 `split()` 避免创建中间 `List<String>`，减少 GC 压力

#### 回调机制

请求-响应模式通过 `callbackId` 匹配：

```kotlin
// 发送请求时注册回调
val cbId = callbackIdGenerator.getAndIncrement().toString()
pendingCallbacks[cbId] = CallbackEntry(callback)

// 收到响应时触发回调
val entry = pendingCallbacks.remove(callbackId)
entry?.callback(json)
```

过期回调通过定时清理任务（每 5 秒）自动移除，避免内存泄漏。

---

## 6. 调试会话生命周期

### 6.1 完整流程时序图

```
用户点击 Debug ─► IntelliJ 创建 XDebugSession
                     │
                     ▼
              LuaPandaProgramRunner.execute()
                     │
                     ▼
              创建 LuaPandaDebugProcess
                     │
                     ▼
              createConsole()  ← 创建控制台视图
                     │
                     ▼
              sessionInitialized()
                     │
            ┌────────┴────────┐
            ▼                 ▼
     startConnection()   scanAndRegisterLuaFiles()
     (后台线程1)          (后台线程2, 非阻塞)
            │
            ▼
     ┌─ SERVER 模式 ──────────── CLIENT 模式 ─┐
     │ ServerSocket.accept()    Socket.connect()│
     │ 等待 Lua 连接...          连接 Lua 端...   │
     └──────────────┬─────────────────────────┘
                    ▼
          TCP 连接建立
                    │
                    ▼
         onDebuggerConnected()
                    │
                    ▼
          sendInitSuccess()  ──── 握手命令 ────►  LuaPanda.lua
                    │                                  │
                    │    ◄── initSuccess 回复 ────      │
                    ▼                                  │
         handleInitSuccessReply()                      │
                    │                                  ▼
                    ▼                           debugger_wait_msg(1)
          sendSetBreakpoints() ── 断点同步 ────►  (WAIT_CMD 状态, 1秒窗口)
                    │                                  │
                    │                                  ▼
                    │                           changeRunState(RUN)
                    │                           开始执行用户代码
                    ▼
            进入正常调试循环
            等待停止事件...
```

### 6.2 握手详解（initSuccess）

握手是调试会话最关键的阶段。LuaPanda.lua 在 TCP 连接建立后，会在 `connectSuccess()` 中调用 `debugger_wait_msg()` **阻塞等待** IDE 发送 `initSuccess` 命令。

`initSuccess` 命令的 `info` 字段包含完整的调试配置：

```json
{
    "stopOnEntry": "false",           // 是否入口停止（字符串布尔值！）
    "pathCaseSensitivity": "false",   // Windows 上必须为 "false"
    "autoPathMode": "true",           // 自动路径匹配
    "logLevel": 2,                    // 日志级别: 0=verbose, 1=info, 2=error
    "useCHook": "true",               // 允许使用 C Hook 库
    "adapterVersion": "3.2.0",        // 协议版本
    "cwd": "D:/Project",              // 工作目录
    "luaFileExtension": "",           // Lua 文件扩展名
    "TempFilePath": "",               // 临时文件路径
    "OSType": "Windows_NT",           // 操作系统
    "isNeedB64EncodeStr": "false",    // Base64 编码
    "distinguishSameNameFile": "false",// 区分同名文件
    "truncatedOPath": "",             // 路径截断
    "clibPath": ".../luapanda-clib/"  // C 库路径
}
```

> **⚠️ 关键：** 布尔值字段必须以**字符串形式**发送（`"true"` / `"false"`），因为 LuaPanda.lua 使用 `== "true"` 做字符串比较。

### 6.3 断点同步时序窗口

握手后 LuaPanda.lua 会进入 **1 秒的 WAIT_CMD 等待窗口**：

```
LuaPanda.lua 时序:
  收到 initSuccess → 处理配置 → 发送回复 → debugger_wait_msg(1) → changeRunState(RUN)
                                              ↑
                                         在这 1 秒内
                                     必须完成断点同步！
```

插件在 `handleInitSuccessReply()` 中（收到 Lua 回复时）**立即**发送断点，确保在 1 秒窗口内完成。同时设置了 500ms 安全网：如果回复丢失，也会兜底发送断点。

### 6.4 停止/断开

```kotlin
override fun stop() {
    // 1. 发送 continue + stopRun 命令
    proc.sendCommand("continue")
    proc.sendCommand("stopRun")

    // 2. 保持 socket 开放 2 秒（Grace Period）
    //    LuaPanda.lua 在 RUN 状态下每 ~1 秒检查一次 TCP 消息
    //    2 秒覆盖两个检查周期，确保两条命令都被处理
    Thread.sleep(2000)

    // 3. 强制关闭 TCP 连接
    server?.stop() / client?.disconnect()
}
```

---

## 7. 断点系统

### 7.1 断点类型

LuaPanda 支持三种断点类型，通过 `type` 字段区分：

| type 值 | 类型 | 说明 |
|---------|------|------|
| `"2"` | 普通行断点 (LINE_BREAKPOINT) | 命中即暂停 |
| `"0"` | 条件断点 (CONDITION_BREAKPOINT) | 满足条件时暂停，附带 `condition` 字段 |
| `"1"` | 日志断点 (LOG_POINT) | 命中时输出日志不暂停，附带 `logMessage` 字段 |

> **⚠️ 关键：** `type` 字段不能使用空字符串 `""`！C Hook 库 (`libpdebug.cpp`) 用 `lua_tointeger` 读取 type，空字符串会被误判为 `0`（条件断点），然后尝试读取不存在的 `condition` 字段导致崩溃。

### 7.2 断点协议格式

每条 `setBreakPoint` 消息按文件分组：

```json
{
    "cmd": "setBreakPoint",
    "callbackId": "0",
    "info": {
        "path": "d:/project/scripts/main.lua",
        "bks": [
            { "line": 10, "type": "2" },
            { "line": 25, "type": "0", "condition": "x > 5" },
            { "line": 30, "type": "1", "logMessage": "value={x}" }
        ]
    }
}
```

### 7.3 断点同步的增量机制

断点同步采用**双策略**，根据场景选择最优方式：

#### 策略 1：增量同步（运行时单断点变更）

当调试会话运行中，用户切换一个断点时，仅发送该文件的断点消息：

```kotlin
private fun scheduleBpSyncForFile(remotePath: String) {
    pendingDirtyFiles.add(remotePath)
    bpSyncPending?.cancel(false)
    bpSyncPending = bpSyncScheduler.schedule({
        sendIncrementalBreakpoints()  // 只发送变更文件的断点
    }, 16, TimeUnit.MILLISECONDS)  // 16ms ≈ 1帧@60fps
}
```

相比全量同步，增量同步避免了重新序列化所有文件的断点，减少 TCP I/O。

#### 策略 2：全量同步（握手后 / 批量操作）

握手完成后（`handleInitSuccessReply`）发送所有断点，确保 Lua 端断点表完整：

```kotlin
// handleInitSuccessReply 中直接调用：
sendSetBreakpoints()  // 发送所有文件的断点，一次 flush
```

16ms 内的多次断点变更合并为一次发送，大幅减少 TCP 写入和 Lua 端处理开销。

### 7.4 批量发送优化

断点消息使用 `sendMessages()` 批量发送，所有文件的断点消息在一次 `flush()` 中写入 TCP：

```kotlin
// 构建所有消息
val messageBatch = mutableListOf<JsonObject>()
for ((filePath, bpList) in bpsByFile) {
    messageBatch.add(buildSetBreakPointMessage(filePath, bpList))
}
// 一次 flush 发送所有消息
processor?.sendMessages(messageBatch)
```

### 7.5 断点管理器 Tool Window

插件提供了一个独立的 **Lua Breakpoints** 工具窗口，位于 IDE 底部面板，提供集中化的断点管理能力。

#### 架构

```
plugin.xml
  └── toolWindow id="Lua Breakpoints"
        └── factoryClass → LuaBreakpointManagerToolWindowFactory
              └── creates → LuaBreakpointManagerPanel
                    ├── Filter Bar (底部)
                    │   ├── Folder ComboBox — 按文件夹过滤
                    │   ├── Type ComboBox — 按类型过滤 (All/Line/Condition/Logpoint)
                    │   └── Count Label — 显示 "N / M breakpoint(s)"
                    ├── JBTable (断点列表)
                    │   ├── COL_ENABLED: 复选框（启用/禁用）
                    │   ├── COL_ICON: 断点类型图标（区分 enabled/disabled 状态）
                    │   ├── COL_FILE: 文件名（带 lua_file 图标，tooltip 显示完整路径）
                    │   ├── COL_LINE: 行号（1-based）
                    │   └── COL_TYPE: 类型文字（Line/Condition/Logpoint）
                    ├── Toolbar Actions
                    │   ├── Enable All — 一键启用所有 *已过滤* 断点
                    │   ├── Disable All — 一键禁用所有 *已过滤* 断点
                    │   ├── Remove All — 删除所有 *已过滤* 断点（需确认，提示过滤数量）
                    │   └── Refresh — 手动刷新列表
                    ├── Right-Click Context Menu
                    │   ├── Go to Source — 跳转到断点所在行
                    │   ├── Toggle Enable/Disable — 切换单个断点启用状态
                    │   ├── Edit Condition... — 编辑条件表达式（InputDialog）
                    │   ├── Edit Log Message... — 编辑日志消息（InputDialog）
                    │   └── Remove Breakpoint — 删除单个断点
                    └── XBreakpointListener (自动同步)
```

#### 功能清单

| 功能 | 交互方式 | 实现机制 |
|------|----------|----------|
| 查看断点列表 | 打开 Lua Breakpoints 工具窗口 | 从 `XBreakpointManager.allBreakpoints` 过滤 `.lua` 文件断点 |
| 跳转到断点代码 | 双击行 / 右键 "Go to Source" | `OpenFileDescriptor` 导航到文件行号 |
| 启用/禁用单个断点 | 点击行首复选框 / 右键 "Toggle Enable" | `XLineBreakpoint.isEnabled = !isEnabled` |
| 一键启用所有断点 | 工具栏 "Enable All" 按钮 | 遍历 `displayedBreakpoints` 设置 `isEnabled = true` |
| 一键禁用所有断点 | 工具栏 "Disable All" 按钮 | 遍历 `displayedBreakpoints` 设置 `isEnabled = false` |
| 删除所有断点 | 工具栏 "Remove All" 按钮 | `XBreakpointManager.removeBreakpoint()`（带过滤信息确认） |
| 删除单个断点 | 右键 "Remove Breakpoint" | `XBreakpointManager.removeBreakpoint(bp)` |
| 编辑条件表达式 | 右键 "Edit Condition..." | `Messages.showInputDialog` → `XExpressionImpl.fromText()` → `bp.conditionExpression` |
| 编辑日志消息 | 右键 "Edit Log Message..." | `Messages.showInputDialog` → `XExpressionImpl.fromText()` → `bp.logExpressionObject` |
| 按文件夹过滤 | 底部 Folder 下拉框 | 提取断点文件的父目录，相对项目根路径显示 |
| 按类型过滤 | 底部 Type 下拉框 | 匹配 `getBreakpointTypeString()` 返回值 |
| 自动同步 | 自动 | 通过 `XBreakpointListener.TOPIC` 监听增/删/改事件 |

#### 过滤机制

断点管理器维护两层列表：
- `allBreakpoints`：从 `XBreakpointManager` 获取的完整断点列表
- `displayedBreakpoints`：经过文件夹和类型过滤后的显示列表

工具栏操作（Enable All / Disable All / Remove All）仅作用于 `displayedBreakpoints`，实现精准的批量管理。例如用户可以选择 "Folder: scripts/ui" + "Type: Condition" 后一键禁用 UI 模块的所有条件断点。

#### 与调试器的联动

断点管理器直接操作 `XBreakpointManager` 的断点对象，修改立即反映到：
- **Gutter 图标**：IntelliJ 自动更新编辑器侧栏的断点标记
- **调试器同步**：`LuaPandaBreakpointHandler.registerBreakpoint/unregisterBreakpoint` 被框架自动调用，触发增量同步到 Lua 端
- **条件/日志编辑**：通过右键菜单修改 `conditionExpression` / `logExpressionObject` 后，下次同步到 Lua 端时断点类型自动更新

---

## 8. 执行控制（Step / Continue / Stop）

### 8.1 命令映射

| IDE 操作 | 调用方法 | 发送命令 |
|---------|---------|---------|
| **Continue** (F9) | `resume()` | `continue` |
| **Step Over** (F8) | `startStepOver()` | `stopOnStep` |
| **Step Into** (F7) | `startStepInto()` | `stopOnStepIn` |
| **Step Out** (Shift+F8) | `startStepOut()` | `stopOnStepOut` |
| **Pause** | `startPausing()` | `stopOnStep`（复用） |
| **Stop** (Ctrl+F2) | `stop()` | `continue` + `stopRun` |

### 8.2 Lua 端执行状态机

LuaPanda.lua 内部维护两个状态机：

**RunState（运行状态）：**
- `RUN` — 正常执行用户代码
- `STOP_ON_ENTRY` — 入口停止
- `HIT_BREAKPOINT` — 命中断点
- `STEP_OVER` / `STEP_IN` / `STEP_OUT` — 单步执行中

**HookState（钩子状态）：**
- `ALL_HOOK` — 监听所有事件（call/return/line），有断点时使用
- `LITE_HOOK` — 仅监听 return 事件，无断点时使用（性能优化）
- `MID_HOOK` — 中间状态

当收到 `continue` 命令时，Lua 从 `debugger_wait_msg()` 阻塞中返回，恢复执行。当收到 `stopOnStep` 时，Lua 设置 `runState = STEP_OVER`，在下一行触发时暂停。

---

## 9. 调用栈与变量查看

### 9.1 栈帧数据格式

Lua 端的 `SendMsgWithStack` 将 `stack` 数组放在消息**顶层**：

```json
{
    "cmd": "stopOnCodeBreakpoint",
    "callbackId": "0",
    "info": [],
    "stack": [
        {
            "file": "d:/project/main.lua",
            "oPath": "main.lua",
            "name": "文件名",
            "func": "update",
            "line": "25",
            "index": "3"
        },
        ...
    ]
}
```

> **注意：** `line` 和 `index` 是**字符串类型**，不是数字。

### 9.2 stackId / frameId 映射

`index` 字段是 Lua 端计算的栈帧索引（`functionLevel - 3 + hookLibOffset`），用于后续 `getVariable` / `getWatchedVariable` 请求。IDE 端**必须**使用 Lua 端发送的 `index` 值，不能用数组下标 +1 替代，因为 C 帧可能混入 `currentCallStack` 但不出现在 `stackTab` 中。

### 9.3 变量查看协议

变量通过 `getVariable` 命令获取，使用 `varRef` 范围区分类别：

| varRef 范围 | 变量类型 |
|------------|---------|
| `10000 ~ 19999` | 局部变量（Locals） |
| `20000 ~ 29999` | 全局变量（Globals） |
| `30000+` | 上值变量（Upvalues） |
| `< 10000` | 子变量展开（table 成员） |

```json
// IDE 发送
{
    "cmd": "getVariable",
    "callbackId": "42",
    "info": { "varRef": 10000, "stackId": "3" }
}

// Lua 回复
{
    "cmd": "querySuccess",
    "callbackId": "42",
    "info": [
        { "name": "x", "value": "10", "type": "number", "variablesReference": 0 },
        { "name": "player", "value": "table: 0x12345", "type": "table", "variablesReference": 5 }
    ]
}
```

当 `variablesReference > 0` 时，表示该变量可以展开（table/userdata）。展开时发送 `varRef=<variablesReference>, stackId=0`。

### 9.4 变量分组展示

在 Variables 面板中，变量按三个分组展示：

```
▶ Locals (自动展开)
    🔢 x = 10                          (number, Variable icon)
    📝 name = "John"                    (string, Value icon, quoted)
    ▶ 📊 player = table: 0x12345       (table, DataTables icon, expandable)
        📝 name = "John"
        🔢 health = 100
▶ Upvalues
    ▶ 📊 config = table: 0x67890
▶ Globals
    ⚙ print = function: 0xABCDE        (function, Function icon)
```

### 9.5 变量展示美化

`LuaPandaVariableValue` 和 `LuaPandaEvaluatedValue` 使用三层机制增强变量展示：自定义类型图标 → 值格式化 → 类型着色渲染。

#### 自定义类型图标 (`LuaTypeIcons` + `LuaPandaIcons`)

每种 Lua 类型使用独立的 SVG 图标（16×16，柔和色圆角矩形 + 深色粗体字符），注册在 `LuaPandaIcons` 中：

| Lua 类型 | 图标文件 | 背景色 | 文字色 | 标识 | 说明 |
|---------|---------|--------|--------|------|------|
| `number` / `int` / `float` | `lua_type_number.svg` | 淡蓝 `#D6E4F0` | 深蓝 `#2A6496` | **N** | 数值 |
| `string` | `lua_type_string.svg` | 淡绿 `#D7ECD9` | 深绿 `#2E7D32` | **S** | 字符串 |
| `boolean` | `lua_type_boolean.svg` | 淡橙 `#FDE8D0` | 深橙 `#BF6900` | **B** | 布尔值 |
| `table` | `lua_type_table.svg` | 淡紫 `#E8D5F5` | 深紫 `#7B1FA2` | **T** | 表/容器 |
| `function` | `lua_type_function.svg` | 淡红 `#F5D5D5` | 深红 `#C62828` | **F** | 函数 |
| `nil` | `lua_type_nil.svg` | 淡灰 `#DEE4E7` | 中灰 `#546E7A` | **∅** | 空值 |
| `userdata` | `lua_type_userdata.svg` | 淡青 `#D0ECE8` | 深青 `#00695C` | **U** | 用户数据 |
| `thread` | `lua_type_thread.svg` | 淡黄 `#F5ECD0` | 深黄 `#F57F17` | **⇉** | 协程 |

**暗色主题支持**：每个图标都有对应的 `_dark.svg` 变体（如 `lua_type_number_dark.svg`），采用深色半透明背景 + 柔和亮色字母。IntelliJ 的 `IconLoader` 在 Darcula/Dark 主题下会自动加载 `_dark` 变体，无需代码改动。

#### 类型着色渲染 (`LuaValuePresentationFactory`)

使用 IntelliJ `XValuePresentation` 子类实现值的语法高亮渲染：

| 类型 | XValuePresentation 子类 | 渲染方式 | 效果 |
|------|------------------------|---------|------|
| `number` | `XNumericValuePresentation` | `renderNumericValue()` | 蓝色数字 |
| `string` | `XStringValuePresentation` | `renderStringValue()` | 绿色带引号 |
| `boolean` / `nil` | `LuaKeywordPresentation` | `renderKeywordValue()` | 橙色关键字 |
| `function` | `LuaFunctionPresentation` | `renderComment()` / `renderValue()` | 灰色地址注释 |
| `table` / `userdata` / `thread` | `LuaTypePresentation` | `renderValue()` | 标准文本 |

#### 值格式化 (`LuaValueFormatter`)

| 类型 | 原始值 | 格式化后 |
|------|-------|---------|
| `string` | `hello world` | `"hello world"` （加引号，转义内部引号） |
| `boolean` | `True` / `TRUE` | `true`（规范化小写） |
| `number` | `10.0` | `10`（整数去小数点） |
| `nil` | 任何 | `nil` |
| `table` | `table: 0x1234` | 保持原样（展开查看成员） |
| 超长值 | 512+ chars | 截断 + `…` + "View Full Value" 链接 |

#### 长值查看器

当值长度超过 `XValueNode.MAX_VALUE_LENGTH` 时，自动添加 `XFullValueEvaluator` 链接，点击可在弹窗中查看完整内容。

---

## 10. 表达式求值与 REPL

### 10.1 表达式求值

通过 `getWatchedVariable` 命令实现，支持：

- **Evaluate Expression 对话框** (Alt+F8)
- **Watch 面板**
- **鼠标悬浮变量查看**

```json
// IDE 发送
{
    "cmd": "getWatchedVariable",
    "callbackId": "7",
    "info": { "varName": "player.health", "stackId": "3" }
}
```

### 10.2 鼠标悬浮智能识别

`LuaPandaEvaluator.getExpressionInfoAtOffset()` 实现了 Lua 标识符的智能识别：

- **简单变量**：`myVar`, `_count`, `x`
- **点分成员访问**：`self.health`, `player.pos.x`
- **过滤 Lua 关键字**：`if`, `then`, `local` 等不触发求值
- **不包含方法调用**：`:method()` 和 `[expr]` 索引被排除，避免副作用

### 10.3 REPL

通过 `runREPLExpression` 命令在 Lua 运行时直接执行代码：

```json
{
    "cmd": "runREPLExpression",
    "callbackId": "0",
    "info": { "expression": "print(player.health)" }
}
```

---

## 11. 路径映射系统

### 11.1 问题背景

Lua 运行时报告的文件路径（通过 `debug.getinfo().source`）与 IDE 中的本地文件路径可能不一致：

| 差异类型 | 示例 |
|---------|------|
| 路径分隔符 | `D:\Project\main.lua` vs `D:/Project/main.lua` |
| 大小写 | `d:/project/main.lua` vs `D:/Project/main.lua` |
| 相对 vs 绝对 | `scripts/main.lua` vs `D:/Project/scripts/main.lua` |
| 远程 vs 本地 | `/app/lua/main.lua` vs `D:/Project/lua/main.lua` |

### 11.2 `PathResolver` 设计

```
PathResolver
├── 显式路径映射 (pathMappings)         ← 用户配置的 local ↔ remote 映射
├── 自动路径匹配 (autoPathMode)          ← 基于文件名索引的 O(1) 查找
├── 直接路径回退 (toRealPath)            ← 文件系统磁盘大小写解析
├── 正向缓存 (remoteToLocalCache)       ← 成功映射的缓存
├── 负向缓存 (negativeCache, 30s TTL)   ← 失败映射的缓存，避免重复磁盘 I/O
└── 文件名索引 (fileNameIndex)           ← 文件名 → 完整路径列表的倒排索引
```

### 11.3 路径解析流程（`remoteToLocal`）

```
remotePath 输入
    │
    ▼
[1] 检查正向缓存 ──── 命中 ──► 返回缓存值
    │ 未命中
    ▼
[2] 检查负向缓存 ──── 命中且未过期 ──► 返回 null
    │ 未命中或已过期
    ▼
[3] 尝试显式路径映射 ──── 匹配 ──► 替换前缀，缓存并返回
    │ 无匹配
    ▼
[4] 自动路径匹配 (autoPathMode)
    │  └── 提取文件名
    │  └── 查询 fileNameIndex (O(1) HashMap 查找)
    │       ├── 唯一匹配 ──► 直接返回
    │       └── 多个匹配 ──► findBestMatch (从右向左比较路径段)
    │ 无匹配
    ▼
[5] 直接路径回退
    │  └── Paths.get(remotePath).toRealPath() ← 一次系统调用检查存在+解析大小写
    │       ├── 成功 ──► 缓存并返回
    │       └── NoSuchFileException ──► 加入负向缓存
    │
    ▼
返回 null (无法解析)
```

### 11.4 文件名索引的必要性

用户项目中可能有 **39,695+ 个 Lua 文件**。没有索引时，每次 `remoteToLocal` 都需要线性扫描所有条目。以一次断点命中为例：

- 12 层调用栈 × IntelliJ 每帧调用 4 次 `getSourcePosition()` = **48 次** `remoteToLocal` 调用
- 48 × 39,695 = **190 万次**字符串比较

使用 `fileNameIndex`（HashMap）后，查找降为 O(1) + O(k)，其中 k 为同名文件数量（通常 1-3）。

### 11.5 Windows 大小写问题

LuaPanda.lua 的 `genUnifiedPath()` 在 `pathCaseSensitivity=false` 时会将所有路径转为小写。但 IntelliJ 的 `LocalFileSystem.findFileByPath()` 在 Windows 上**需要正确的磁盘大小写**：

```
LuaPanda.lua 报告: "d:/project/test.lua"            (全小写)
磁盘实际路径:       "D:/Project/test.lua"            (混合大小写)
```

解决方案：`PathResolver` 内部使用两层标准化：
- `normalizePath()` — 小写化 + 统一分隔符，用于**比较和缓存键**
- `normalizeSeparators()` — 仅统一分隔符，保留原始大小写，用于**返回值**

---

## 12. C Hook Library (libpdebug)

### 12.1 纯 Lua Hook vs C Hook 性能对比

| 特性 | 纯 Lua debug.sethook | C Hook (libpdebug) |
|------|----------------------|---------------------|
| 钩子触发频率 | 每行 Lua 代码都触发 | 仅在相关行触发 |
| 断点检查 | Lua table 查找（慢） | C 层 HashMap 查找（快） |
| 性能开销 | **极高**，大型项目可能卡顿 | **低**，接近无调试时性能 |
| 兼容性 | 所有 Lua 版本 | 需要匹配的预编译库 |

### 12.2 内置库文件结构

插件内置了 LuaPanda 官方预编译的 `libpdebug` 库，覆盖主流平台：

```
resources/debugger/debugger_lib/plugins/
├── win/
│   ├── x86/
│   │   ├── 501/libpdebug.dll      (Lua 5.1, x86)
│   │   └── 503/libpdebug.dll      (Lua 5.3, x86)
│   └── x86_64/
│       ├── 501/libpdebug.dll      (Lua 5.1, x64)
│       ├── 503/libpdebug.dll      (Lua 5.3, x64)
│       └── 504/libpdebug.dll      (Lua 5.4, x64)
└── mac/
    ├── arm_64/
    │   ├── 503/libpdebug.so       (Lua 5.3, Apple Silicon)
    │   └── 504/libpdebug.so       (Lua 5.4, Apple Silicon)
    └── x86_64/
        ├── 501/libpdebug.so       (Lua 5.1, Intel Mac)
        └── 503/libpdebug.so       (Lua 5.3, Intel Mac)
```

### 12.3 自动解压机制

`extractBundledClibPath()` 在首次调试时将内置库解压到临时目录：

```
路径: {java.io.tmpdir}/luapanda-idea-clib-{pluginVersion}/
标记: .extracted 文件（避免重复解压）
```

LuaPanda.lua 使用 `clibPath` 按以下格式拼接搜索路径：

```
clibPath + platform + "/" + arch + "/" + lua_ver + "/?.ext;"
例: /tmp/luapanda-idea-clib-1.0.0/win/x86_64/503/?.dll;
```

### 12.4 加载失败处理

如果 C Hook 库加载失败（`UseHookLib == "0"`），插件会输出详细的性能警告：

```
[LuaPanda] WARNING: libpdebug C hook library not loaded — using pure Lua debug (slow).
  可能原因:
    - Lua 运行时版本不支持 (如 LuaJIT, Lua 5.2)
    - 宿主应用的 Lua DLL 符号不兼容
    - 平台/架构不匹配 (如 Linux, 特殊 Lua 编译)
```

---

## 13. EmmyLua 兼容性

### 13.1 双重断点处理器

LuaPanda 注册了两个断点处理器：

1. **`LuaPandaBreakpointHandler`** — 处理自身的 `luapanda-line` 断点类型
2. **`EmmyLuaBreakpointHandlerAdapter`** — 动态检测 EmmyLua 的 `lua-line` 断点类型

```kotlin
private fun createBreakpointHandlers(): Array<XBreakpointHandler<*>> {
    val handlers = mutableListOf<XBreakpointHandler<*>>()
    handlers.add(LuaPandaBreakpointHandler(this))

    // 动态检测 EmmyLua 断点类型
    val emmyType = XBreakpointType.EXTENSION_POINT_NAME.extensions
        .find { it.id == "lua-line" }
    if (emmyType != null) {
        handlers.add(EmmyLuaBreakpointHandlerAdapter(this, emmyTypeClass))
    }

    return handlers.toTypedArray()
}
```

这样，无论用户通过 EmmyLua 还是 LuaPanda 设置的行断点，在调试时都能被正确捕获。

### 13.2 断点类型自动避让

`LuaPandaBreakpointType` 在 `canPutAt()` 中检查文件类型，使用文件扩展名判断（而非 `LuaFileType`），避免与 EmmyLua 冲突。

---

## 14. 性能优化

### 14.1 优化措施汇总

| 优化项 | 技术细节 | 效果 |
|-------|---------|------|
| **IDEA 文件索引** | 用 `FilenameIndex.getAllFilesByExt()` 替代文件系统遍历 | 37,808 文件从 ~98 秒降到毫秒级 |
| **并行初始化** | TCP 连接和文件扫描在独立后台线程并行执行 | Console 不再长时间空白 |
| **readLoop 优化** | `ArrayDeque` + `indexOf` 替代 `ArrayList` + `split` | 减少 GC 压力 |
| **批量断点发送** | `sendMessages()` 单次 flush 替代 N 次 `sendMessage()` | 减少 N-1 次 TCP syscall |
| **断点防抖** | 100ms debounce 合并多次断点变更 | 避免重复发送完整断点列表 |
| **回调超时清理** | 定时 5 秒清理一次，替代每条消息都清理 | 降低高频消息时的开销 |
| **OS 检测缓存** | `isWindows` 缓存为类字段 | 避免重复 `System.getProperty` |
| **evaluate 直接回调** | 回调在 TCP 线程直接执行，不经 `invokeLater` | 减少 ~10-30ms EDT 调度延迟 |
| **文件名倒排索引** | `fileNameIndex` HashMap O(1) 查找 | 替代 O(N) 线性扫描 39,695 条目 |
| **负向缓存** | 30s TTL 缓存文件不存在的结果 | 避免重复磁盘 I/O |
| **toRealPath 单次调用** | 替代 `File.exists()` + `File.canonicalPath` 两次调用 | 系统调用减半 |
| **PathResolver 并发安全** | `ConcurrentHashMap` 替代 `HashMap` | 支持扫描和解析并行执行 |
| **栈帧 sourcePosition 缓存** | `@Volatile` 缓存解析结果 | 避免每帧重复 4 次路径解析 |
| **TCP_NODELAY** | 禁用 Nagle 算法 | 小消息立即发送，握手无延迟 |

### 14.2 关键性能瓶颈分析

**瓶颈 1：文件扫描阻塞连接**

旧实现中，37,808 个 Lua 文件的扫描（`walkTopDown`）耗时 98 秒，期间 Console 完全空白。

解决方案：
1. 改用 IDEA 内置 `FilenameIndex` → 毫秒级完成
2. 扫描与连接并行执行 → 连接不等待扫描完成

**瓶颈 2：大型项目中的路径解析**

每次断点命中时，48 次路径解析 × 39,695 次线性扫描 = 190 万次比较。

解决方案：`fileNameIndex` 倒排索引 → O(1) 查找。

---

## 15. 关键数据流图

### 15.1 断点命中完整数据流

```
 Lua 运行时                          TCP                          IntelliJ IDEA
 ─────────                          ───                          ──────────────
 debug_hook(event, line)
     │
     ▼
 checkBreakpoint(file, line)
     │ 命中!
     ▼
 changeRunState(HIT_BREAKPOINT)
     │
     ▼
 getStackTable() → stack[]
     │
     ▼
 SendMsgWithStack(                                              
   "stopOnCodeBreakpoint",          ═══► JSON|*|\n ═══►         ProtocolProcessor.readLoop()
   stack)                                                            │
                                                                     ▼
 debugger_wait_msg()                                            processMessage(json)
   [阻塞等待 IDE 命令]                                                │
     │                                                               ▼
     │                                                          handleMessage(cmd)
     │                                                               │
     │                                                               ▼
     │                                                          handleStoppedEvent()
     │                                                               │
     │                                                          ┌────┴─────────────┐
     │                                                          ▼                  ▼
     │                                                    解析 stack[]      pathResolver
     │                                                    创建 StackFrame[]  .remoteToLocal()
     │                                                          │                  │
     │                                                          └────┬─────────────┘
     │                                                               ▼
     │                                                          invokeLater (EDT)
     │                                                               │
     │                                                          session.breakpointReached()
     │                                                               │
     │                                                          ┌────┴────┐
     │                                                          ▼         ▼
     │                                                      高亮源码   Variables 面板
     │                                                      导航到行   请求变量数据
     │                                                                    │
     │              ◄═══ getVariable ═══                                  │
     │                                                                    │
     ▼                                                                    │
 processGetVariable()                                                     │
     │                                                                    │
     ▼                                                                    │
 收集 locals/upvalues/globals          ═══► variableInfo[] ═══►          │
                                                                   展示变量树
```

### 15.2 文件索引对比

```
旧方案 (File.walkTopDown):
  ┌──────────────┐      37,808 files        ┌────────────────┐
  │  File System  │ ────── 98 seconds ──────► │  PathResolver  │
  │  Recursive    │     (阻塞 TCP 连接!)       │  knownPaths    │
  └──────────────┘                            └────────────────┘

新方案 (FilenameIndex):
  ┌──────────────┐      37,808 files        ┌────────────────┐
  │  IDEA Index   │ ────── <1 second ────── ► │  PathResolver  │
  │  (已构建)     │    (且不阻塞连接)          │  knownPaths    │
  └──────────────┘                            └────────────────┘
```

---

## 附录 A：项目文件结构

```
src/main/kotlin/com/luapanda/idea/
├── LuaPandaIcons.kt                          # 图标定义
├── actions/
│   └── CopyLuaPandaAction.kt                 # 复制 LuaPanda.lua 到项目
├── annotator/
│   └── LuacheckExternalAnnotator.kt          # luacheck 集成
├── debugger/
│   ├── LuaPandaDebugProcess.kt               # ★ 核心调试进程 (73KB)
│   ├── LuaPandaStackFrame.kt                 # 栈帧 / 变量 / 求值 (19KB)
│   ├── LuaPandaBreakpoint*.kt                # 断点类型/处理器/属性
│   ├── LuaPandaRunConfiguration*.kt          # 运行配置
│   ├── LuaPandaProgramRunner.kt              # Debug 执行器
│   ├── LuaPandaExecutionStack.kt             # 调用栈
│   ├── LuaPandaSuspendContext.kt             # 暂停上下文
│   ├── LuaPandaProcessHandler.kt             # 进程句柄
│   ├── LuaPandaDebuggerEditorsProvider.kt    # 表达式编辑器
│   └── EmmyLuaBreakpointHandlerAdapter.kt    # EmmyLua 兼容
├── formatter/
│   └── FormatWithLuaFmtAction.kt             # lua-fmt 格式化
├── protocol/
│   ├── ProtocolProcessor.kt                  # ★ 协议帧处理 (14KB)
│   ├── ProtocolMessages.kt                   # 消息数据类 + 常量
│   ├── PathResolver.kt                       # ★ 路径映射 (14KB)
│   ├── LuaPandaServer.kt                     # TCP 服务器
│   └── LuaPandaClient.kt                     # TCP 客户端
└── settings/
    ├── LuaPandaSettings.kt                   # 全局设置
    └── LuaPandaSettingsConfigurable.kt       # 设置 UI

src/main/resources/
├── META-INF/plugin.xml                       # 插件注册
├── debugger/
│   ├── LuaPanda.lua                          # 调试脚本（完整版，随插件打包分发）
│   └── debugger_lib/plugins/                 # libpdebug 预编译库
└── icons/                                    # SVG 图标
```

---

## 附录 B：关键配置常量

| 常量 | 值 | 说明 |
|------|-----|------|
| 默认端口 | `8818` | LuaPanda 标准端口 |
| 帧分隔符 | `\|*\|` | TCP 消息分隔 |
| 断点防抖 | `100ms` | 断点变更合并窗口 |
| 安全网超时 | `500ms` | 握手回复超时兜底 |
| 停止等待 | `2000ms` | 关闭前的 Grace Period |
| 回调超时 | `10000ms` | 回调自动清理阈值 |
| 回调清理周期 | `5000ms` | 定时清理过期回调 |
| 负向缓存 TTL | `30000ms` | 路径不存在缓存时间 |
| logLevel | `2` | 默认只报错 |

---

*文档随插件版本迭代更新，具体行为以源码为准。*
