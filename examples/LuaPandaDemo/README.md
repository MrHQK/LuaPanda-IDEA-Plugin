# LuaPanda 演示工程（LuaPandaDemo）

一个**自包含、可一键运行**的 LuaPanda 调试演示工程：场景贴近日常手游开发（战斗结算 / 掉落 / 背包），
覆盖插件的 8 个核心调试能力点。自带便携 Lua 5.4 运行时与 LuaSocket，不依赖任何本机 Lua 环境。

> ⚡ **想看某个功能点？** 对照下方 [8 个演示点](#8-个演示点demolua-行号速查) 表格，
> 在 `demo.lua` 里 `Ctrl+F` 搜锚点（如 `BP-A`）即可跳到对应行。

> 📌 仓库**不含**录屏产物。`demo*.bat`、`run_recording.bat`、`video/` 属于本机录屏
> 工具链（依赖本机 Python 与录屏环境），未随仓库发布，说明见文末。

## 目录结构

```
LuaPandaDemo/
├── demo.lua                     # 演示脚本（8 个演示点，行号见下表）
├── LuaPanda.lua                 # 调试器 Lua 端（v3.3.0）
├── run_demo.bat                 # 一键启动 debuggee（自动探测 runtime\lua54）
├── runtime/lua54/               # 便携 Lua 5.4 + LuaSocket 3.0.0
└── .idea/                       # 预置好的工程配置（含运行配置）
    └── runConfigurations/
        └── LuaPanda Demo (Server).xml
```

> 本机开发时还会多出 `demo.bat` / `demo1.bat … demo8.bat`（功能点一键演示）与
> `video/`（录屏工具链 + 成品 mp4），这些均被 `.gitignore` 排除。

## 快速开始（手动 3 步）

1. 用 IDEA 打开本目录（`LuaPandaDemo`）。
2. 选中运行配置 **LuaPanda Demo (Server)**，点 🐞 Debug（IDE 开始监听 `127.0.0.1:8818`）。
3. 双击 `run_demo.bat`（或命令行执行）。Lua 侧会自动等待 IDE 就绪后连上，命中断点即暂停。

> 无 IDEA 环境时可以做纯自检：`run_demo.bat --no-debug`（跳过调试连接，只跑业务逻辑）。

## 一键演示某个功能点（本地开发机专用，未随仓库发布）

> 本节描述的是本机录屏工具链的用法，对应的 `demo*.bat` / `video/*.py` **不在仓库里**。
> 公开 clone 的用户请走手动流程：`Ctrl+F` 搜锚点定位行号 → `Ctrl+F8` 打点 →
> 启动 Debug 会话 → 跑 `run_demo.bat`。下面这张表可当作「每个演示点在哪一行、看什么」的清单。

不想手动找行号、打点、起会话？双击对应的 bat，脚本把剩下的事全做了：

| 想看什么 | 双击 | 脚本做了什么 |
|----------|------|--------------|
| 行断点 | `demo1.bat` | Ctrl+G 跳 128 行 → Ctrl+F8 打点 → 重启 Server 会话 → 运行 → 停在 128 |
| 条件断点 | `demo2.bat` | 打点 145 → 运行 → 停住后**自动打开断点对话框**，填 `i == 5` 即可 |
| 日志断点 | `demo3.bat` | 同上，对话框里勾 Log message 填 `第 {i} 次攻击 -> {damage}` |
| 变量/table | `demo4.bat` | 打点 165 → 停在 `local inspect = bag` |
| 调用堆栈 | `demo5.bat` | 打点 176（3 层调用最深处）→ Frames 面板逐层跳 |
| 表达式求值 | `demo6.bat` | 打点 195 → 自动 Alt+F8 求 `hero.crit * 100` |
| 手写 BP() | `demo7.bat` | **不打任何 IDE 断点**，直接运行，代码里的 `LuaPanda.BP()` 必然暂停 |
| Watch + 单步 | `demo8.bat` | 打点 218 → 自动 Step Over ×2 |

也可以双击 `demo.bat` 输数字（它还带 `a` = 录全片）。等价于命令行：

```
python video\demo_point.py --point 6            # 只演示第 6 点
python video\demo_point.py --point 1 --record   # 演示并录成 point1.mp4
python video\demo_point.py --point 4 --no-clean-bps   # 保留已有断点
```

说明：

- 每个点都会**先清掉遗留断点**，再只打目标行那一个，避免停在别处；
  第 7 点刻意不打任何断点。
- 脚本用 IDEA 日志做校验：看 `sourcePosition resolved: … demo.lua, line=N`
  判断是否真的停在目标行；停在别的行会告警并打印 IDE 实际注册的断点行。
- 打字（Ctrl+G 行号 / Alt+F8 表达式）前会备份 demo.lua，一旦弹窗没打开
  导致字符落进编辑器，立刻还原文件，不会污染演示脚本。
- 结束后 IDEA **保持暂停**，方便你慢慢看变量面板；Ctrl+F2 停止会话。
- 需要在一个已登录的 Windows 桌面会话里运行（锁屏/断开会话下键鼠注入无效，
  脚本会直接提示退出）。

## 8 个演示点（demo.lua 行号速查）

| # | 能力点 | demo.lua 行号 | 搜索锚点 | 一键脚本 |
|---|--------|--------------|----------|----------|
| 1 | 行断点 | **128** | `[BP-A]` | `demo1.bat` |
| 2 | 条件断点（i == 5） | **145** | `[BP-B]` | `demo2.bat` |
| 3 | 日志断点 | 145（同一行） | `[BP-C]` | `demo3.bat` |
| 4 | 变量与 table 展开 | **165** | `[BP-D]` | `demo4.bat` |
| 5 | 调用堆栈（3 层 Frames） | **176** | `[BP-E]` | `demo5.bat` |
| 6 | 表达式求值（Alt+F8） | **195** | `[BP-F]` | `demo6.bat` |
| 7 | 手写断点 `LuaPanda.BP()` | **208** | `[BP-G]` | `demo7.bat` |
| 8 | Watch + 单步控制 | **218** | `[BP-H]` | `demo8.bat` |

- 行号同时以 `-- ★断点：…（N）[BP-x]` 注释写在 demo.lua 里，锚点 `[BP-A]` … `[BP-H]` 依次对应演示点 1 … 8（点 2 / 3 同行，锚点分别为 `[BP-B]` `[BP-C]`），Ctrl+F 搜 `BP-A` 即可跳转。
- demo.lua 固定随机种子，每次演示输出一致。
- 常用参数：`run_demo.bat --only=3`（只跑第 3 个演示点）、`--mode=client`（真机/远程模式）。

## 一键全自动录屏（本地开发机专用，未随仓库发布）

> `video/` 整个目录都不随仓库发布，本节仅对本机开发有效。

`video/record_demo.py` 会全自动完成：启动/复用 IDEA → 最大化窗口 → 开始 ffmpeg 录屏 →
打开 demo.lua → 启动 Server 调试会话 → 打行断点（并解析 `idea.log` 校验行号）→ 启动 debuggee →
命中断点 → Step Over / 变量面板 / Evaluate → Resume ×N → Stop → 停止录制输出 mp4。

```
cd video
record_demo.bat                          # 默认输出 video/LuaPanda-Demo.mp4
python record_demo.py --seconds 240      # 预留更长录制时长
python record_demo.py --probe-debug      # 干跑：真开会话但不录屏，用于调试脚本
```

常用参数：`--breakpoint-line 128`（主断点行）、`--resume-times`、`--index-wait`（索引等待秒数）、
`--no-toggle-bp`（不通过 UI 打行断点，仅演示 `LuaPanda.BP()` 暂停）。

> ⚠️ 录屏会真实操作鼠标键盘，并把 `LuaPandaDemo` 窗口置前。脚本用「窗口标题 en-dash 前的项目名
> 精确匹配」定位窗口，不会误伤其他 IDEA 工程窗口；但仍建议录制期间不要操作机器。

## 运行配置说明

`.idea/runConfigurations/LuaPanda Demo (Server).xml` 已预置，Server 模式监听 `8818`。
首次打开工程后，在工具栏的运行配置下拉里手动选中 **LuaPanda Demo (Server)** 即可
（`workspace.xml` 属个人 IDE 状态，未随仓库发布，所以不会自动选中）。

注意两点（都是踩过的坑）：

- `factoryName` 必须写 **`LuaPandaDebugConfigurationFactory`**（factory 的 `getId()`），
  写显示名 `LuaPanda Debug` 会让配置在 Edit Configurations 里变成「未知」。
- 若你需要自己维护 `workspace.xml`：其 `RunManager selected` 必须写成
  `LuaPandaDebugConfiguration.LuaPanda Demo (Server)`（类型 ID 前缀）。

## 常见问题

| 现象 | 原因 / 处理 |
|------|-------------|
| `8818` 一直 LISTENING 但没有 `client accepted` | demo.lua 语法错误导致 Lua 进程秒退，先跑 `run_demo.bat --no-debug` 看报错 |
| 断点没有停在 128，而是停在 208 | 208 行是手写 `LuaPanda.BP()`（必然暂停）；行断点未生效时程序会一路跑到这里。record_demo.py 已加入 idea.log 校验与自动重试 |
| IDEA 里配置显示「未知」 | `factoryName` 写错，见上文运行配置说明 |
| 中文注释乱码 | demo.lua 必须保存为 UTF-8；.bat 文件保持纯 ASCII |

## 关于本地脚本（未随仓库发布）

为了让公开仓库只包含「clone 下来就能跑」的内容，以下文件已被 `.gitignore` 排除，仓库中不存在：

| 文件 / 目录 | 用途 | 排除原因 |
|-------------|------|----------|
| `demo.bat` | 功能点菜单（输入 1-8 / `a` 录全片） | 启动器内写死了本机 Python 绝对路径 |
| `demo1.bat … demo8.bat` | 每个演示点一个，双击即演示 | 同上 |
| `run_recording.bat` | 全流程自动录屏入口 | 同上 |
| `video/` | 录屏工具链（Python 脚本、分镜稿、成品 mp4、中间帧） | 依赖本机 Python / ffmpeg / 已登录桌面会话，且体积大 |

对公开 clone 的用户没有影响：`run_demo.bat` 会自行探测 `runtime/lua54/lua54.exe`，
不依赖上述任何文件；演示点行号与锚点见上文表格。
