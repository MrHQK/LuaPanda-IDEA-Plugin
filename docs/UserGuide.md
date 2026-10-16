# LuaPanda IDEA Plugin 使用文档

## 目录

- [简介](#简介)
- [系统要求](#系统要求)
- [安装](#安装)
  - [安装插件](#安装插件)
  - [推荐搭配 EmmyLua 使用](#推荐搭配-emmylua-使用)
  - [安装外部工具（可选）](#安装外部工具可选)
- [快速开始](#快速开始)
  - [1. 准备 LuaPanda.lua 调试脚本](#1-准备-luapandalua-调试脚本)
  - [2. 创建调试配置](#2-创建调试配置)
  - [3. 在 Lua 代码中引入调试器](#3-在-lua-代码中引入调试器)
  - [4. 启动调试](#4-启动调试)
- [调试配置详解](#调试配置详解)
  - [配置参数](#配置参数)
  - [连接模式](#连接模式)
- [调试功能](#调试功能)
  - [断点](#断点)
  - [执行控制](#执行控制)
  - [变量查看](#变量查看)
  - [按变量名搜索](#按变量名搜索)
  - [表达式求值](#表达式求值)
  - [调用堆栈](#调用堆栈)
  - [控制台输出](#控制台输出)
- [全局设置](#全局设置)
- [代码质量工具](#代码质量工具)
  - [lua-fmt 代码格式化](#lua-fmt-代码格式化)
  - [luacheck 静态分析](#luacheck-静态分析)
- [路径映射](#路径映射)
  - [自动路径匹配模式](#自动路径匹配模式)
  - [手动路径映射](#手动路径映射)
- [与 EmmyLua 的协作机制](#与-emmylua-的协作机制)
- [常见问题排查](#常见问题排查)
- [从源码构建](#从源码构建)

---

## 简介

**LuaPanda IDEA Plugin** 是一个为 IntelliJ IDEA 开发的 Lua 调试插件，移植自腾讯开源的 [LuaPanda](https://github.com/Tencent/LuaPanda) VSCode 扩展。

本插件**专注于调试功能**，通过 TCP/JSON 协议与 `LuaPanda.lua` 调试脚本通信，完全兼容原版 LuaPanda 协议。语法高亮、代码补全、Go to Definition 等语言特性建议搭配 [EmmyLua](https://plugins.jetbrains.com/plugin/9768-emmylua) 插件使用。

### 核心功能

| 功能类别 | 说明 |
|---------|------|
| **断点调试** | 行断点、条件断点、日志断点 (Logpoint) |
| **执行控制** | 继续 (Continue)、单步跳过 (Step Over)、单步进入 (Step Into)、单步跳出 (Step Out)、暂停 (Pause) |
| **变量检查** | 局部变量 (Locals)、上值变量 (Upvalues)、全局变量 (Globals) 树形展示 |
| **表达式求值** | Evaluate Expression 对话框、Watch 面板 |
| **调用堆栈** | 完整的 Lua 调用堆栈导航 |
| **REPL 控制台** | 调试期间在 Lua 运行时执行表达式 |
| **连接模式** | Server 模式（IDE 监听）和 Client 模式（IDE 主动连接） |
| **代码质量** | 集成 lua-fmt 格式化和 luacheck 静态分析 |
| **EmmyLua 兼容** | 与 EmmyLua 断点无缝兼容，避免重复标记 |

---

## 系统要求

| 要求 | 版本 |
|------|------|
| **IntelliJ IDEA** | 2024.1 或更高版本（Community 或 Ultimate 均可） |
| **JDK** | 17+ |
| **Lua 运行时** | Lua 5.1 ~ 5.4（需 LuaSocket 支持） |
| **lua-fmt**（可选） | 用于代码格式化 |
| **luacheck**（可选） | 用于代码静态分析 |

---

## 安装

### 安装插件

1. 从 Releases 页面下载插件 ZIP 文件（`LuaPanda-IDEA-Plugin-x.x.x.zip`）
2. 在 IntelliJ IDEA 中：**Settings → Plugins → ⚙️（齿轮图标）→ Install Plugin from Disk...**
3. 选择下载的 ZIP 文件
4. 点击 **OK**，然后重启 IDE

### 推荐搭配 EmmyLua 使用

本插件仅提供调试功能。为获得完整的 Lua 开发体验（语法高亮、代码补全、跳转定义等），**强烈建议同时安装 EmmyLua 插件**：

**Settings → Plugins → Marketplace** → 搜索 "EmmyLua" → 安装

> 安装 EmmyLua 后，LuaPanda 会自动让 EmmyLua 管理断点的 gutter 图标（避免重复显示），但 LuaPanda 的断点处理器会拦截 EmmyLua 的断点，将其转发给 LuaPanda 调试器。

### 安装外部工具（可选）

| 工具 | 用途 | 安装方式 |
|------|------|---------|
| **lua-fmt** | Lua 代码格式化 | `npm install -g lua-fmt` 或从 GitHub 下载 |
| **luacheck** | Lua 代码静态分析 | `luarocks install luacheck` 或从 GitHub 下载 |

安装后在 **Settings → Tools → LuaPanda** 中配置可执行文件路径。

---

## 快速开始

### 1. 准备 LuaPanda.lua 调试脚本

LuaPanda 调试需要在 Lua 项目中引入 `LuaPanda.lua` 调试脚本。有两种方式获取：

**方式 A：通过菜单复制（推荐）**
- 在 IDE 中选择 **Tools → Copy LuaPanda.lua to Project**
- 插件内置的就是**完整版** `LuaPanda.lua`（BSD 3-Clause，与上游保持一致），会直接复制到项目根目录
- 把它放在 Lua 的 `package.path` 下，确保 `require("LuaPanda")` 能找到它

**方式 B：自行从 GitHub 下载**
1. 访问 [https://github.com/Tencent/LuaPanda](https://github.com/Tencent/LuaPanda)
2. 下载 `Debugger/LuaPanda.lua` 文件
3. 将其放入你的 Lua 项目中（确保 `require` 能找到它）

### 2. 创建调试配置

1. 在 IDE 中选择 **Run → Edit Configurations...**
2. 点击左上角 **+** 号，选择 **LuaPanda Debug**
3. 配置调试参数（见[调试配置详解](#调试配置详解)）
4. 点击 **OK** 保存

> **注意**：LuaPanda Debug 配置**仅支持 Debug 模式**。工具栏的 Run（绿色箭头）按钮会被灰置，只有 Debug（🐛）按钮可用。

### 3. 在 Lua 代码中引入调试器

在你的 Lua 脚本入口处，根据连接模式添加对应代码。

> 💡 创建调试配置后，配置界面底部的**使用模板**会根据你的设置自动生成对应代码，可直接复制。

#### Server 模式（默认，推荐）

```lua
-- 将 xx 替换为 LuaPanda.lua 在你项目中的实际路径
-- 例如 "libs/LuaPanda" 或 "debugger/LuaPanda"
require("xx/LuaPanda").start("127.0.0.1", 8818)
```

#### Client 模式（反向连接，用于真机调试）

```lua
-- 将 xx 替换为 LuaPanda.lua 在你项目中的实际路径
-- Client 模式使用 startServer()，Lua 端监听等待 IDE 连接
require("xx/LuaPanda").startServer("0.0.0.0", 8818)
```

> ⚠️ **注意**：两种模式使用**不同的 API** —— Server 模式用 `start()`（Lua 主动连接 IDE），Client 模式用 `startServer()`（Lua 监听端口等待 IDE 连接）。

参数说明：
- 第一个参数：IP 地址（Server 模式填 IDE 的 IP，Client 模式通常用 `"0.0.0.0"` 监听所有网卡）
- 第二个参数：端口号（需与 IDE 配置一致，默认 `8818`）
- `require` 路径中的 `xx` 需替换为 `LuaPanda.lua` 在你项目 `package.path` 中的实际位置

### 4. 启动调试

#### Server 模式（默认，推荐）

```
步骤 1：在 IDE 中点击 Debug 按钮 → IDE 开始监听端口
步骤 2：运行你的 Lua 程序 → Lua 调用 start() 主动连接到 IDE
步骤 3：控制台显示 "LuaPanda connected" → 调试就绪！
```

#### Client 模式（反向连接，用于真机调试）

```
步骤 1：运行 Lua 程序 → Lua 调用 startServer() 监听端口
步骤 2：在 IDE 中点击 Debug 按钮 → IDE 主动连接到 Lua 端
步骤 3：控制台显示 "Connected to LuaPanda" → 调试就绪！
```

---

## 调试配置详解

### 配置参数

在 **Run → Edit Configurations → LuaPanda Debug** 中可以设置以下参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| **Host** | `127.0.0.1` | TCP 连接的主机地址。本地调试使用 `127.0.0.1`，远程调试填写目标机器 IP |
| **Port** | `8818` | TCP 连接端口。需与 Lua 端 `require("xx/LuaPanda").start()` 或 `.startServer()` 中的端口一致 |
| **Connection Mode** | `Server (IDE listens)` | 连接模式，详见下方 |
| **Auto reconnect** | ✅ 勾选 | Lua 端断开后是否自动等待重新连接（仅 Server 模式有效） |
| **LuaPanda.lua path** | 空 | LuaPanda.lua 调试脚本的路径（可选，用于路径解析参考） |
| **Use C hook library (libpdebug)** | ✅ 已勾选 | 是否启用 libpdebug C Hook 库。**默认开启**，性能比纯 Lua hook 高 10~100 倍。插件内置预编译库覆盖 Lua 5.1 / 5.3 / 5.4 on Win x86/x64、Mac x64/arm64，同机调试绝大多数场景开箱即用。若加载失败（如 LuaJIT、Lua 5.2、远程调试对端机器没有对应库文件、宿主 DLL 未导出 Lua 符号），Console 会打印 WARNING 并自动回退到纯 Lua hook；此时可取消勾选以关闭提示 |
| **libpdebug path** | 空 | 可选。自定义 libpdebug 目录（如 `LuaPanda/Debugger/debugger_lib/`）。留空则使用插件内置的预编译库。**仅当上面的 C hook 开关启用时生效** |

### 连接模式

| 模式 | 使用场景 | 连接流程 |
|------|---------|---------|
| **Server (IDE listens)** | 本地开发、模拟器调试 | IDE 启动 TCP 服务端监听 → Lua 程序启动后主动连接到 IDE |
| **Client (IDE connects)** | 真机设备调试、远程调试 | Lua 程序先启动并监听 → IDE 作为客户端主动连接到 Lua 进程 |

**如何选择连接模式？**

- 如果你的 Lua 程序运行在**本机**或**模拟器**上 → 使用 **Server 模式**（默认）
- 如果 Lua 运行在**远程设备/真机**上，且你能访问设备的 IP → 使用 **Client 模式**，将 Host 设为设备 IP

---

## 调试功能

### 断点

在 `.lua` 文件的编辑器左侧 gutter 区域点击即可设置断点。

| 断点类型 | 设置方式 | 说明 |
|---------|---------|------|
| **行断点** | 点击 gutter 行号旁边 | 程序执行到该行时暂停 |
| **条件断点** | 右键断点 → 添加 Condition | 仅当条件表达式为真时暂停。例如 `i > 10` |
| **日志断点 (Logpoint)** | 右键断点 → 勾选 Log message | 不暂停程序，仅在控制台输出日志。例如 `"x = " .. tostring(x)` |
| **手动断点** | 在 Lua 代码中调用 `LuaPanda.BP()` | 等效于行断点，适用于动态设置 |

> **EmmyLua 兼容**：如果安装了 EmmyLua，断点图标由 EmmyLua 管理。LuaPanda 的调试器会自动拦截 EmmyLua 的断点事件，无需额外配置。

### 执行控制

连接成功并命中断点后，可以使用以下控制：

| 操作 | 快捷键 | 工具栏按钮 | 说明 |
|------|--------|-----------|------|
| **Continue（继续）** | `F9` | ▶️ | 恢复程序执行，直到下一个断点 |
| **Step Over（单步跳过）** | `F8` | ⏭ | 执行当前行，不进入函数内部 |
| **Step Into（单步进入）** | `F7` | ⬇️ | 进入当前行调用的函数内部 |
| **Step Out（单步跳出）** | `Shift+F8` | ⬆️ | 执行完当前函数，返回调用处 |
| **Pause（暂停）** | — | ⏸ | 在下一条 Lua 语句处暂停 |
| **Stop（停止）** | `Ctrl+F2` | ⏹ | 停止调试会话，Lua 程序恢复正常执行 |

### 变量查看

断点命中后，在 **Variables** 面板中可以查看三组变量：

| 变量组 | 说明 | 默认展开 |
|-------|------|---------|
| **Locals** | 当前函数的局部变量 | ✅ 自动展开 |
| **Upvalues** | 当前函数捕获的上值变量（闭包引用） | 默认折叠 |
| **Globals** | Lua 全局变量 | 默认折叠 |

每个变量显示：
- **名称** (name)
- **类型** (type)：`number`, `string`, `boolean`, `table`, `function`, `nil` 等
- **值** (value)
- **子元素**：table 类型的变量可以展开查看内部字段

### 按变量名搜索

当变量层级较深（如 `self.cache` 下的字段）或全局变量较多时，可以按变量名直接搜索当前堆栈帧内的所有变量。

**打开方式**（需处于断点暂停状态）：
- 调试窗口工具栏上的 **Search Lua Variables**（放大镜图标）按钮
- **Variables** 面板工具栏按钮，或在该面板中右键 → **Search Lua Variables...**
- 主菜单 **Run → Search Lua Variables...**

**使用说明**：

| 控件 | 说明 |
|------|------|
| 搜索框 | 默认按**变量名**过滤（不区分大小写）；输入含 `.` / `[` 的内容时按**完整访问路径**过滤（如 `self.hp`） |
| Locals / Upvalues / Globals | 勾选要搜索的变量范围（Globals 默认关闭，因为 `_G` 变量很多） |
| Depth | table 展开深度，默认 2 层（0 = 只搜索顶层变量） |
| Match case | 区分大小写 |
| Regex | 按正则表达式匹配 |
| Reload | 重新采集当前帧变量 |

**结果列表**：显示 **Name / Path**（可双击或右键 **Add to Watches** 加入监视，右键可复制路径/名称/值）、**Scope**、**Type**、**Value** 四列，匹配到的内容会高亮显示。

**说明**：
- 变量在每次暂停/切换堆栈帧时自动采集一次，之后输入搜索词只是本地过滤，不会重复请求 Lua 端；
- 采集时会递归展开 table 成员，路径列给出的就是可直接用于 Evaluate / Watch 的 Lua 表达式（如 `self.data["hp max"][1]`）；
- 为避免在 Lua 侧产生过深的调用层级，单次采集最多发起 500 次请求；达到上限时状态栏会提示，此时可减小 Depth 或缩小搜索范围。

### 表达式求值

在断点暂停状态下，可以使用以下方式求值：

**方式 1：Evaluate Expression 对话框**
- 快捷键 `Alt+F8`（或菜单 **Run → Evaluate Expression**）
- 输入任意 Lua 表达式，回车查看结果
- 例如：`player.hp + 100`、`string.format("name=%s", obj.name)`

**方式 2：Watch 面板**
- 在 Variables 面板上方的 **Watches** 区域
- 点击 **+** 添加要监视的表达式
- 每次断点命中时自动刷新

**方式 3：REPL 控制台**
- 在调试控制台直接输入表达式
- 表达式会在 Lua 运行时中执行，可修改变量

### 调用堆栈

在 **Frames** 面板中查看 Lua 调用堆栈。每个堆栈帧显示：

```
函数名 (文件名:行号)
```

示例：
```
update (GameScene.lua:142)
run (Main.lua:56)
<anonymous> (Main.lua:3)
```

- 点击堆栈帧可跳转到对应的源码位置
- 匿名函数显示为 `<anonymous>`
- 选择不同堆栈帧后，Variables 面板会切换到对应帧的变量

### 控制台输出

调试控制台显示以下信息：

| 消息类型 | 前缀/标识 | 说明 |
|---------|----------|------|
| 系统消息 | 灰色文字 | 连接状态、断点同步等 |
| Lua print 输出 | 正常文字 | `print()` 和 `io.write()` 的输出 |
| 调试控制台 | `[DebugConsole]` | 调试器自身的控制台输出 |
| 提示信息 | `[LuaPanda]` | 来自 LuaPanda.lua 的通知 |
| 错误信息 | 红色文字 | 错误提示和异常 |
| 内存信息 | `[Memory]` | Lua 内存使用情况 |

---

## 全局设置

在 **Settings → Tools → LuaPanda** 中配置全局参数：

| 设置项 | 说明 |
|-------|------|
| **lua-fmt path** | lua-fmt 可执行文件路径，用于 Lua 代码格式化。点击 Browse 按钮选择文件 |
| **luacheck path** | luacheck 可执行文件路径，用于静态分析诊断。点击 Browse 按钮选择文件 |
| **Default host** | 默认连接主机地址（`127.0.0.1`） |
| **Default port** | 默认连接端口（`8818`） |

---

## 代码质量工具

### lua-fmt 代码格式化

**前提**：在全局设置中配置了 lua-fmt 路径。

**使用方式**：
1. 打开一个 `.lua` 文件
2. 菜单选择 **Code → Format with lua-fmt**
3. 文件内容会被格式化后的内容替换

**工作原理**：
- 通过 `--stdin` 管道模式将当前文件内容传给 lua-fmt
- 格式化成功后自动替换编辑器中的文件内容

> 如果 lua-fmt 路径未配置，会弹出提示对话框引导到设置页面。

### luacheck 静态分析

**前提**：在全局设置中配置了 luacheck 路径。

**使用方式**：
- **自动运行**：打开或编辑 `.lua` 文件时，IDE 在后台自动运行 luacheck
- 诊断结果以行内标注的形式显示在编辑器中

**诊断严重性**：
| 前缀 | 级别 | 显示方式 |
|------|------|---------|
| `E` 开头的代码（如 E011） | Error | 红色波浪线 |
| `W` 开头的代码（如 W111） | Warning | 黄色波浪线 |

> 如果 luacheck 路径未配置，静态分析会静默跳过，不影响正常使用。

---

## 路径映射

当 Lua 程序运行在远程环境（服务器、真机设备）时，LuaPanda.lua 上报的文件路径（远程路径）可能与 IDE 中的本地路径不同。路径映射解决了这个问题。

### 自动路径匹配模式

**默认开启**。当没有配置显式路径映射时，插件会自动根据文件名匹配本地文件：

1. 从远程路径中提取文件名
2. 在已知的本地文件中查找同名文件
3. 如果只有一个匹配 → 直接使用
4. 如果有多个匹配 → 从右向左比较路径段，选择匹配度最高的

**示例**：
```
远程路径: /data/game/scripts/player/Player.lua
本地文件: D:/MyProject/src/player/Player.lua
→ 自动匹配成功（文件名 Player.lua + 目录 player 匹配）
```

### 手动路径映射

对于复杂的项目结构，可以在 Run Configuration 中配置显式路径映射（通过代码配置 `pathMappings`）：

| 参数 | 说明 |
|------|------|
| **localRoot** | 本地项目路径根目录（如 `D:/MyProject/src`） |
| **remoteRoot** | 远程运行环境的路径根目录（如 `/data/game/scripts`） |

路径映射是双向的：
- **断点设置**：`localRoot` 下的路径 → 转换为 `remoteRoot` 下的路径，发送给 LuaPanda.lua
- **断点命中**：LuaPanda.lua 上报的路径 → 从 `remoteRoot` 转换为 `localRoot`，定位到 IDE 中的文件

---

## 与 EmmyLua 的协作机制

LuaPanda IDEA Plugin 专为与 EmmyLua 协同工作而设计：

| 功能 | EmmyLua 未安装 | EmmyLua 已安装 |
|------|---------------|---------------|
| **断点 gutter 图标** | LuaPanda 提供（`luapanda-line` 类型） | EmmyLua 提供（`lua-line` 类型），LuaPanda 自动让出 |
| **断点处理** | LuaPanda 自己的 Handler | LuaPanda 通过适配器拦截 EmmyLua 的断点 |
| **语法高亮** | 不提供 | 由 EmmyLua 提供 |
| **代码补全** | 不提供 | 由 EmmyLua 提供 |
| **Go to Definition** | 不提供 | 由 EmmyLua 提供 |

> **两个插件可以同时安装，互不冲突。**

---

## 常见问题排查

### 1. 点击 Debug 后控制台没有任何反应

**原因**：可能使用了错误的 Run Configuration 类型。

**解决**：确认使用的是 "LuaPanda Debug" 配置，而不是 EmmyLua 或其他类型的配置。

### 2. 控制台显示 "Waiting for LuaPanda connection..." 但 Lua 端连不上

**可能原因**：
- Lua 代码中的端口与 IDE 配置不一致
- 防火墙阻止了连接
- Lua 运行时没有 LuaSocket 库

**排查步骤**：
1. 确认 IDE 端和 Lua 端的 **Host** 和 **Port** 一致
2. 检查防火墙设置，允许对应端口的 TCP 连接
3. 确认 Lua 运行环境安装了 LuaSocket：`require("socket")` 不应报错
4. 确认项目的 `LuaPanda.lua` 确实位于 Lua 的 `package.path` 中（可用 **Tools → Copy LuaPanda.lua to Project** 复制内置完整版）

### 3. 连上后 Lua 程序卡住/不执行

**可能原因**：LuaPanda.lua 连接成功后会等待 IDE 端发送 `initSuccess` 握手命令。如果握手失败，Lua 端会阻塞等待。

**解决**：确认使用了最新版的插件。此问题在早期版本中存在时序 BUG，已在最新版中修复。

### 4. 断开调试后 Lua 进程没有恢复

**解决**：确认使用了最新版的插件。点击 Stop 时，插件会先发送 `continue` 命令唤醒可能暂停的 Lua 端，然后发送 `stopRun` 让 Lua 退出调试模式。

### 5. 断点不生效/点击 gutter 没有断点标记

**原因**：如果安装了 EmmyLua，断点图标由 EmmyLua 管理。

**排查步骤**：
1. 确认文件扩展名是 `.lua`
2. 如果安装了 EmmyLua，确认 EmmyLua 已正确识别该文件为 Lua 文件
3. 确认在调试会话启动后设置了断点（或在启动前设置，插件会在连接建立后自动同步）

### 6. 变量面板为空

**可能原因**：变量请求超时或 LuaPanda.lua 版本不兼容。

**排查**：
1. 确认使用的是 Tencent/LuaPanda 原版的 `LuaPanda.lua`
2. 检查 IDE 日志：**Help → Show Log in Explorer** → 搜索 "LuaPanda" 关键字

### 7. 路径匹配失败，断点命中但未定位到源码

**解决**：
1. 确认 Lua 端上报的文件路径格式
2. 如果路径差异较大，配置显式 `pathMappings`（localRoot ↔ remoteRoot）
3. 查看 IDE 日志中的 "Could not resolve remote path" 信息确认具体路径

### 8. lua-fmt / luacheck 不工作

**排查**：
1. 确认在 **Settings → Tools → LuaPanda** 中配置了正确的可执行文件路径
2. 在终端中手动运行 `lua-fmt --help` 或 `luacheck --help` 验证工具可用
3. 确认文件扩展名是 `.lua`

---

## 从源码构建

### 环境要求

- JDK 17+
- Gradle 8.13+（项目包含 Gradle Wrapper，无需单独安装）

### 构建步骤

```bash
# 克隆项目
git clone https://github.com/MrHQK/LuaPanda-IDEA-Plugin.git
cd LuaPanda-IDEA-Plugin

# 编译
./gradlew compileKotlin

# 打包插件
./gradlew buildPlugin

# 插件 ZIP 文件位于：
# build/distributions/LuaPanda-IDEA-Plugin-<version>.zip
```

### 开发调试

```bash
# 在沙箱 IDE 中运行插件（用于开发测试）
./gradlew runIde

# 运行单元测试
./gradlew test

# 验证插件兼容性
./gradlew verifyPlugin
```

### 项目结构

```
src/main/kotlin/com/luapanda/idea/
├── LuaPandaIcons.kt                          # 图标定义
├── actions/
│   └── CopyLuaPandaAction.kt                 # "Copy LuaPanda.lua to Project" 菜单动作
├── annotator/
│   └── LuacheckExternalAnnotator.kt          # luacheck 集成
├── debugger/
│   ├── EmmyLuaBreakpointHandlerAdapter.kt    # EmmyLua 断点兼容适配器
│   ├── LuaPandaBreakpointHandler.kt          # 自有断点处理器
│   ├── LuaPandaBreakpointProperties.kt       # 断点属性
│   ├── LuaPandaBreakpointType.kt             # 断点类型定义
│   ├── LuaPandaDebugProcess.kt               # 核心调试进程（主文件）
│   ├── LuaPandaDebuggerEditorsProvider.kt    # 表达式求值编辑器
│   ├── LuaPandaExecutionStack.kt             # 调用堆栈
│   ├── LuaPandaProcessHandler.kt             # 进程生命周期
│   ├── LuaPandaProgramRunner.kt              # Debug Runner
│   ├── LuaPandaRunConfiguration.kt           # 运行配置定义
│   ├── LuaPandaRunConfigurationEditor.kt     # 运行配置 UI
│   ├── LuaPandaRunConfigurationType.kt       # 运行配置类型注册
│   ├── LuaPandaRunProfileState.kt            # 运行状态
│   ├── LuaPandaStackFrame.kt                 # 堆栈帧 + 变量组 + 求值器
│   └── LuaPandaSuspendContext.kt             # 暂停上下文
├── formatter/
│   └── FormatWithLuaFmtAction.kt             # lua-fmt 格式化动作
├── protocol/
│   ├── LuaPandaClient.kt                     # TCP 客户端（Client 模式）
│   ├── LuaPandaServer.kt                     # TCP 服务端（Server 模式）
│   ├── PathResolver.kt                       # 路径解析器
│   ├── ProtocolMessages.kt                   # 协议消息定义
│   └── ProtocolProcessor.kt                  # 协议处理器
└── settings/
    ├── LuaPandaSettings.kt                   # 全局持久化设置
    └── LuaPandaSettingsConfigurable.kt       # 设置页面 UI
```

---

## 协议参考

> 本节面向开发者，描述 LuaPanda TCP/JSON 协议的关键细节。

### 消息帧格式

```
JSON_BODY + " " + "|*|" + "\n"
```

### 消息结构

```json
{
  "cmd": "<command_name>",
  "callbackId": "<id>",
  "info": { ... }
}
```

### IDE → Lua 命令

| 命令 | cmd 值 | 说明 |
|------|--------|------|
| 初始化握手 | `initSuccess` | 连接建立后必须第一个发送 |
| 继续执行 | `continue` | 恢复暂停的程序 |
| 单步跳过 | `stopOnStep` | Step Over |
| 单步进入 | `stopOnStepIn` | Step Into |
| 单步跳出 | `stopOnStepOut` | Step Out |
| 停止调试 | `stopRun` | 退出调试模式 |
| 设置断点 | `setBreakPoint` | 同步所有断点 |
| 获取变量 | `getVariable` | Variables 面板请求 |
| 求值表达式 | `getWatchedVariable` | Evaluate / Watch |
| 设置变量 | `setVariable` | 修改变量值 |
| 执行 REPL | `runREPLExpression` | REPL 控制台 |
| 强制 GC | `LuaGarbageCollect` | Lua 垃圾回收 |

#### initSuccess 握手详解

连接握手是调试会话的关键步骤。时序如下：

```
IDE (Server)                    LuaPanda.lua (Client)
     │                                │
     │ ◄──── TCP 连接建立 ─────────── │
     │                                │  (Lua 阻塞在 debugger_wait_msg())
     │ ─── initSuccess 消息 ────────► │  (包含完整配置参数)
     │                                │  (Lua 处理配置，加载 C Hook 库等)
     │ ◄──── initSuccess 回复 ─────── │  (包含 debuggerVer, UseHookLib 等)
     │                                │
     │ ─── setBreakPoint 消息 ──────► │  (同步断点)
     │                                │
     │                                ▼  进入 RUN 或 STOP_ON_ENTRY 状态
```

**initSuccess info 字段**（IDE → Lua）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `stopOnEntry` | string | `"true"` / `"false"` — 是否在入口暂停 |
| `pathCaseSensitivity` | string | 路径大小写敏感 |
| `autoPathMode` | string | 自动路径匹配模式 |
| `logLevel` | number | 日志级别（0=verbose/debug 最详细, 1=info, 2=error 仅错误） |
| `useCHook` | string | `"true"` / `"false"` — 是否使用 C Hook 库 |
| `adapterVersion` | string | 协议版本号（如 `"3.2.0"`） |
| `cwd` | string | 工作目录路径 |
| `luaFileExtension` | string | Lua 文件扩展名（空字符串表示 `.lua`） |
| `TempFilePath` | string | 临时文件路径 |
| `OSType` | string | `"Windows_NT"` / `"Darwin"` / `"Linux"` |
| `isNeedB64EncodeStr` | string | 是否 Base64 编码字符串 |
| `distinguishSameNameFile` | string | 是否区分同名文件 |
| `truncatedOPath` | string | 路径截断标志 |
| `clibPath` | string | C 调试库路径 |

> ⚠️ **注意**：LuaPanda.lua 对布尔值字段使用字符串比较（`== "true"`），因此必须以字符串形式发送。缺少关键字段（特别是 `useCHook`、`adapterVersion`）会导致 Lua 端初始化失败并断开连接。

#### setBreakPoint 断点同步详解

LuaPanda.lua 期望 **按文件分组** 发送断点，每个文件一条 `setBreakPoint` 消息：

```json
{
  "cmd": "setBreakPoint",
  "callbackId": "0",
  "info": {
    "path": "/path/to/test.lua",
    "bks": [
      { "line": 10, "type": "" },
      { "line": 25, "type": "0", "condition": "x > 10" },
      { "line": 30, "type": "1", "logMessage": "value = {x}" }
    ]
  }
}
```

**断点类型 (`type` 字段)**：

| type 值 | 说明 |
|---------|------|
| `""` (空字符串) | 普通行断点 |
| `"0"` | 条件断点（需配合 `condition` 字段） |
| `"1"` | 日志点（需配合 `logMessage` 字段，不中断执行） |

> ⚠️ **注意**：不能将所有文件的断点合并到一个消息中。LuaPanda.lua 从 `info.path` 获取文件路径，从 `info.bks` 获取该文件的断点列表。发送空 `bks` 数组可清除该文件的所有断点。

### Lua → IDE 事件

| 事件 | cmd 值 | 说明 |
|------|--------|------|
| 命中代码行断点 | `stopOnCodeBreakpoint` | 执行到断点行 |
| 命中手动断点 | `stopOnBreakpoint` | `LuaPanda.BP()` 调用 |
| 入口暂停 | `stopOnEntry` | `stopOnEntry=true` 时触发 |
| Step Over 完成 | `stopOnStep` | 下一行暂停 |
| Step Into 完成 | `stopOnStepIn` | 进入函数暂停 |
| Step Out 完成 | `stopOnStepOut` | 跳出函数暂停 |
| 控制台输出 | `output` | Lua 端的 print 输出 |
| 调试控制台输出 | `debug_console` | 调试器自身输出 |
| 信息提示 | `tip` | 通知消息 |
| 错误提示 | `tipError` | 错误通知 |
| 握手回复 | `initSuccess` | Lua 端处理握手后的回复 |
| 内存刷新 | `refreshLuaMemory` | 内存使用信息 |

#### stopOnCodeBreakpoint 消息结构

当 Lua 命中断点时，发送的消息结构：

```json
{
  "cmd": "stopOnCodeBreakpoint",
  "callbackId": "0",
  "stack": [
    { "file": "路径", "oPath": "原始路径", "name": "文件名", "line": "10", "index": "0" },
    { "file": "路径", "oPath": "原始路径", "name": "文件名", "line": "25", "index": "1" }
  ],
  "info": {}
}
```

> ⚠️ **注意**：`line` 和 `index` 是**字符串类型**（不是数字）。**`stack` 数组在消息顶层**（与 `cmd`、`callbackId`、`info` 并列），**不在 `info` 内部**。解析时必须从完整 message 对象中提取 `stack`，而不是从 `info` 子对象中提取。LuaPanda.lua 不发送函数名（`func` 字段不存在），`name` 字段固定为 `"文件名"`。

---

## 故障排查

### 远程调试：客户端"不停连接 / 每秒一次 Connection reset"

> ✅ **插件 1.0.1+ 已在 IDE 侧根治此问题**（2026-04-24，参考 VSCode 行为改造）。正常情况下你不会再看到本节描述的 `Connection reset` 循环，也不需要再修改 Lua 源码。本节保留作为背景说明和异常场景的兜底方案。
>
> 改造要点：IDE 侧 server 在收到 Lua 的 `initSuccess` 回包之前保持 `listen`，僵尸 socket 会被**静默**替换；只有握手真正完成后才打印 `LuaPanda connected`。跟 VSCode 官方扩展的行为一致。

**症状**（仅在旧版本 < 1.0.1 或特殊场景下可见）：IDEA Console 反复出现：

```
LuaPanda connected
Sent initSuccess to LuaPanda
LuaPanda disconnected (after ~1000ms): Connection reset
LuaPanda connected
...
```

每次连接持续约 1 秒，**从未**在 Console 看到 `LuaPanda debugger version: x.x.x` 这行（说明 Lua 端一次也没把 `initSuccess` reply 发回来）。

**典型场景**：

- `host` 不是 `127.0.0.1`（跨机 / VPN / 虚拟机 / 容器）
- 3 次失败后 Console 会自动打印 `[LuaPanda] Detected 3 short-lived connections where Lua never replied to initSuccess...`
- 10 次失败后 Console 会升级到 ERROR 并附带修复代码片段

**根因**：LuaPanda.lua 默认 `connectTimeoutSec = 0.005`（5ms），这对 `LuaPanda.start('127.0.0.1', ...)` 本机调试没问题，但对 **LAN / VPN** 的 30~200ms RTT 来说严重不足：

1. Lua 侧 `sock:connect(host, port)` 5ms 超时返回失败
2. Lua 侧 `start()` 直接放弃此 socket，**不会**进入 `receive()` 循环
3. 但内核层 TCP 三次握手仍会完成 → IDE `accept()` 成功 → IDE 发 `initSuccess`
4. Lua 侧没人读这条消息，约 1 秒后 `reConnect()` 关闭陈旧 fd → IDE 观测到 `Connection reset`
5. 循环 goto 1

**修复（Lua 侧）**：修改项目中使用的 `LuaPanda.lua` 第 266 行附近的 `function this.start(host, port)`：

```lua
-- 原：
sock:settimeout(connectTimeoutSec);
local connectSuccess = this.sockConnect(sock);

-- 改为：
if host == "127.0.0.1" or host == "localhost" then
    sock:settimeout(connectTimeoutSec);      -- 本机保持 5ms
else
    sock:settimeout(0.5);                    -- LAN/VPN 放宽到 500ms
end
local connectSuccess = this.sockConnect(sock);
```

**注意**：
- 仅改 `start()` 里的首次连接超时，**不要**改全局变量 `connectTimeoutSec`。否则 attach 模式下每次 Lua 函数 return 触发的 `reConnect()` 都会阻塞 500ms，造成业务卡顿。
- 本机 `127.0.0.1` 调试不受任何影响。

**自检小窍门**：
- 如果你用的是插件 **1.0.1+**，正常不会再看到 "Connected → disconnected" flicker。Console 只会在握手真正成功时打印一条 `LuaPanda connected`，之前的僵尸 socket 被 IDE 静默丢弃。
- 如果升级到 1.0.1+ 仍出现 flicker（极罕见），才需要考虑按上面方法修改 Lua 源码。

### 其它"短连接"提示

如果 Console 出现这段提示：

```
[LuaPanda] Detected 3 short-lived (<2s) connections in a row — the Lua host
is likely reloading scripts or calling LuaPanda.stop()/start() repeatedly.
```

且**同一次连接**里能看到 `LuaPanda debugger version: ...`（说明握手其实成功过），那么问题在 Lua 业务代码侧：可能是热重载重复 `require("LuaPanda")`、初始化代码每帧执行、或看门狗周期性 reset Lua 状态。建议检查业务侧 `LuaPanda.start()` 的调用路径。

---

## License

[Apache License 2.0](../LICENSE)

内置第三方组件（`LuaPanda.lua`、`libpdebug`，均为 Tencent/LuaPanda 的 BSD 3-Clause）遵循其原有许可，声明见 [THIRD_PARTY_NOTICES.txt](../src/main/resources/META-INF/THIRD_PARTY_NOTICES.txt)。
