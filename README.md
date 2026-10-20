# LuaPanda IDEA Plugin

IntelliJ IDEA 平台的 Lua 调试插件，移植自腾讯开源的 [LuaPanda](https://github.com/Tencent/LuaPanda) VSCode 扩展。

**专注调试功能，与 [EmmyLua](https://plugins.jetbrains.com/plugin/9768-emmylua) 协同工作** — 本插件提供完整的调试能力，EmmyLua 提供语法高亮、代码补全等语言支持。两个插件可同时安装，互不冲突。

> 🛒 **[JetBrains Marketplace →](https://plugins.jetbrains.com/plugin/35183-luapanda)** ｜ 📖 **[完整使用文档 →](docs/UserGuide.md)** ｜ 📋 **[更新日志 →](CHANGELOG.md)** ｜ 🎬 **[演示工程 →](examples/LuaPandaDemo/README.md)**

---

## 功能特性

### 🐛 调试

| 功能 | 说明 |
|------|------|
| **断点** | 行断点、条件断点、日志断点 (Logpoint) |
| **执行控制** | Continue、Step Over、Step Into、Step Out、Pause |
| **变量查看** | Locals / Upvalues / Globals 分组展示，支持 table 展开 |
| **调用堆栈** | 完整 Lua 调用链导航 |
| **表达式求值** | Evaluate Expression、Watch 面板、REPL 控制台 |
| **双连接模式** | Server 模式（本地调试）+ Client 模式（真机/远程调试） |
| **使用模板** | 配置界面内嵌实时代码模板，一键复制接入代码 |
| **C Hook 加速** | 内置 libpdebug 预编译库，调试性能提升 10~100 倍 |
| **EmmyLua 兼容** | 自动适配 EmmyLua 断点，无需额外配置 |
| **协议兼容** | 完全兼容原版 LuaPanda.lua TCP/JSON 协议 |

### 🔧 代码质量

- **lua-fmt** 代码格式化集成
- **luacheck** 静态分析集成（编辑时实时诊断）

---

## 系统要求

| 要求 | 版本 |
|------|------|
| IntelliJ IDEA | 2024.1+（Community 或 Ultimate） |
| JDK | 17+（仅从源码构建时需要） |
| Lua 运行时 | Lua 5.1 ~ 5.4，需支持 LuaSocket |
| lua-fmt（可选） | 用于代码格式化 |
| luacheck（可选） | 用于静态分析 |

---

## 安装

### 方式一：从 Marketplace 安装（推荐）

1. 打开 IntelliJ IDEA → **Settings → Plugins → Marketplace**
2. 搜索 **LuaPanda**
3. 点 **Install**，重启 IDE

> 插件主页：<https://plugins.jetbrains.com/plugin/35183-luapanda>

### 方式二：从 ZIP 文件安装（离线 / 内网环境）

1. 从 [Releases](https://github.com/MrHQK/LuaPanda-IDEA-Plugin/releases) 下载 `LuaPanda-IDEA-Plugin-<version>-signed.zip`
2. 打开 IntelliJ IDEA → **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. 选择下载的 ZIP 文件，点击 OK
4. 重启 IDE

### 方式三：推荐同时安装 EmmyLua

本插件仅提供调试功能。为获得完整 Lua 开发体验（语法高亮、代码补全、跳转定义等），**强烈建议同时安装 EmmyLua**：

**Settings → Plugins → Marketplace** → 搜索 "EmmyLua" → 安装

---

## 快速开始

> 🎬 **想先看效果？** 仓库内提供了可直接运行的演示工程 **[examples/LuaPandaDemo](examples/LuaPandaDemo/README.md)**：
> 内置 8 个演示点（行断点 / 条件断点 / 日志断点 / 变量与 table 展开 / 调用堆栈 / 表达式求值 / 手写断点 / Watch + 单步控制）；
> Client 模式（真机 / 远程）不属于演示点，可用 `run_demo.bat --mode=client` 单独演示。
>
> **打开即用，只需两步**：用 IDEA 打开该目录 → 选中预置的 `LuaPanda Demo (Server)` 点 Debug，然后双击 `run_demo.bat`
> （脚本会自动探测 Lua 运行时，无需手工建运行配置、无需配置 PATH）。
> 想只讲其中几个点？`run_demo.bat --only=3` 可以单点重跑，不必每次重跑全流程。
>
> 另附 **[演示速查卡](examples/LuaPandaDemo/演示速查卡.md)**（一页纸步骤 + 断点行号）。

### 第一步：获取 LuaPanda.lua

调试需要在你的 Lua 项目中放入 `LuaPanda.lua` 调试脚本。

- **推荐**：IDE 菜单 **Tools → Copy LuaPanda.lua to Project** —— 插件内置的就是**完整版** `LuaPanda.lua`，会直接复制到项目根目录
- **可选**：自行从 [GitHub](https://github.com/Tencent/LuaPanda) 下载 `Debugger/LuaPanda.lua`（例如你需要使用自己定制/更新的版本）

### 第二步：创建调试配置

1. **Run → Edit Configurations... → + → LuaPanda Debug**
2. 设置 Host（默认 `127.0.0.1`）和 Port（默认 `8818`）
3. 配置界面底部会自动显示**使用模板**，包含需要添加的 Lua 代码

### 第三步：在 Lua 代码中引入调试器

根据连接模式，在 Lua 脚本入口添加对应代码：

**Server 模式**（默认，适用于本地/模拟器调试）：
```lua
-- 将 xx 替换为 LuaPanda.lua 在你项目中的实际路径
require("xx/LuaPanda").start("127.0.0.1", 8818)
```

**Client 模式**（适用于真机/远程调试）：
```lua
-- 将 xx 替换为 LuaPanda.lua 在你项目中的实际路径
require("xx/LuaPanda").startServer("127.0.0.1", 8818)
```

> ⚠️ 注意两种模式使用**不同的 API**：`start()` 和 `startServer()`

### 第四步：启动调试

**Server 模式**：
```
① 在 IDE 中点击 Debug 按钮 → IDE 开始监听
② 运行你的 Lua 程序 → 自动连接到 IDE
③ 控制台显示连接成功 → 开始调试！
```

**Client 模式**：
```
① 先运行你的 Lua 程序 → Lua 端开始监听
② 在 IDE 中点击 Debug 按钮 → IDE 主动连接
③ 控制台显示连接成功 → 开始调试！
```

---

## 调试配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| **Host** | `127.0.0.1` | 连接地址。本地调试用 `127.0.0.1`，远程调试填目标 IP |
| **Port** | `8818` | 连接端口，需与 Lua 端一致 |
| **Connection Mode** | Server | Server = IDE 监听，Client = IDE 连接 |
| **Auto reconnect** | ✅ | Lua 断开后自动等待重连（仅 Server 模式） |
| **Stop on entry** | ❌ | 是否在 Lua 第一行自动暂停 |
| **LuaPanda.lua path** | 空 | LuaPanda.lua 路径（可选，用于路径解析参考） |
| **libpdebug path** | 空 | C Hook 库目录路径（留空则使用内置库，自动解压） |

---

## 连接模式选择指南

| 场景 | 推荐模式 | Lua 端代码 |
|------|---------|-----------|
| 本地开发调试 | **Server** | `require("xx/LuaPanda").start("127.0.0.1", 8818)` |
| 模拟器调试 | **Server** | `require("xx/LuaPanda").start("127.0.0.1", 8818)` |
| 真机设备调试 | **Client** | `require("xx/LuaPanda").startServer("0.0.0.0", 8818)` |
| 远程服务器调试 | **Client** | `require("xx/LuaPanda").startServer("0.0.0.0", 8818)` |

> `xx` 替换为 LuaPanda.lua 在你项目中的实际路径，例如 `"libs/LuaPanda"` 或 `"debugger/LuaPanda"`

---

## 全局设置

**Settings → Tools → LuaPanda**

| 设置项 | 说明 |
|-------|------|
| **lua-fmt path** | lua-fmt 可执行文件路径（代码格式化） |
| **luacheck path** | luacheck 可执行文件路径（静态分析） |

---

## 常见问题

### 连接不上？

1. 确认 IDE 和 Lua 端的 **Host** 和 **Port** 一致
2. 确认 Lua 运行时有 LuaSocket：`require("socket")` 不应报错
3. 确认项目的 `LuaPanda.lua` 确实位于 Lua 的 `package.path` 中（可用 **Tools → Copy LuaPanda.lua to Project** 复制内置完整版）
4. 检查防火墙是否阻止了 TCP 连接

### 断点不生效？

1. 确认文件扩展名是 `.lua`
2. 如安装了 EmmyLua，断点图标由 EmmyLua 管理（正常现象），LuaPanda 会自动拦截
3. 确认调试连接已建立（控制台有连接成功日志）

### 调试性能慢？

- 确保 libpdebug C Hook 库已加载（连接时控制台会提示 `UseHookLib` 状态）
- 如果显示 `UseHookLib=0`，说明 C 库未加载成功，此时使用纯 Lua Hook（性能较低）
- 可检查 `libpdebug path` 配置是否正确

> 📖 更多问题请查看 **[完整使用文档](docs/UserGuide.md#常见问题排查)**

---

## 从源码构建

```bash
# 克隆项目
git clone https://github.com/MrHQK/LuaPanda-IDEA-Plugin.git
cd LuaPanda-IDEA-Plugin

# 编译
./gradlew compileKotlin

# 打包插件（输出：build/distributions/LuaPanda-IDEA-Plugin-<version>.zip）
./gradlew buildPlugin

# 在沙箱 IDE 中运行
./gradlew runIde

# 运行测试
./gradlew test
```

### 一键打包（Windows，推荐）

`scripts/pack.ps1` 封装了构建 + 拷贝到根目录 + 可选自动部署的全流程：

```powershell
# 仅打包（增量，最快）
.\scripts\pack.ps1

# 全量打包并自动部署到本机 IntelliJ IDEA（需先关闭 IDEA）
.\scripts\pack.ps1 -Clean -Deploy

# 打包后顺手打开产物目录
.\scripts\pack.ps1 -OpenFolder
```

也可以双击 `scripts\pack.bat` 直接打包。
产物会自动复制一份到项目根目录：`LuaPanda-IDEA-Plugin-<version>.zip`。

---

## 致谢

- [Tencent/LuaPanda](https://github.com/Tencent/LuaPanda) — 原版 VSCode 调试扩展
- [EmmyLua](https://github.com/EmmyLua/IntelliJ-EmmyLua) — IntelliJ Lua 语言支持插件

## 许可证

[Apache License 2.0](LICENSE)

插件内置的第三方组件（`LuaPanda.lua`、`libpdebug`，均为 Tencent/LuaPanda 的 BSD 3-Clause）遵循其原有许可，声明见 [THIRD_PARTY_NOTICES.txt](src/main/resources/META-INF/THIRD_PARTY_NOTICES.txt)（该文件也会随插件包一起分发）。
