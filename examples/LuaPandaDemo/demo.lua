--[===[
    LuaPanda IDEA Plugin —— 一键演示脚本
    ---------------------------------------------------------------------
    场景：游戏战斗结算（伤害计算 / 掉落结算 / 背包整理）
    主题贴近日常手游开发，每一步都是一个可独立演示的调试能力点。

    用法：
        run_demo.bat                      跑全部演示点
        run_demo.bat --only=3             只跑第 3 个演示点
        run_demo.bat --mode=client        Client 模式（真机 / 远程）
    ---------------------------------------------------------------------
    演示点清单（行号与锚点同「演示速查卡.md」，Ctrl+F 搜 BP-A 可跳转）：
       1  行断点            128  calc_damage()                    [BP-A]
       2  条件断点 i == 5    145  Demo_Conditional_And_Logpoint()  [BP-B]
       3  日志断点          145  Demo_Conditional_And_Logpoint()  [BP-C]
       4  变量与 table 展开  165  Demo_Variables()                 [BP-D]
       5  调用堆栈          176  stack_level_1/2/3()              [BP-E]
       6  表达式求值         195  Demo_Evaluate()                  [BP-F]
       7  手写断点 BP()     208  Demo_ManualBP()                  [BP-G]
       8  Watch + 单步控制   218  apply_reward()（Demo_Watch 调用）[BP-H]
--]===]

local LuaPanda = require("LuaPanda")

--------------------------------------------------------------------------
-- 参数解析
--------------------------------------------------------------------------
local only = nil                 -- --only=N   只跑第 N 个演示点
local mode = "server"            -- --mode=server|client
local no_debug = false           -- --no-debug 不建立调试连接，纯跑一遍看输出（自检用）

for _, a in ipairs(arg or {}) do
    local v = a:match("^%-%-only=(%d+)$");   if v then only = tonumber(v) end
    local m = a:match("^%-%-mode=(%w+)$");   if m then mode = m:lower() end
    if a == "--no-debug" then no_debug = true end
end

local function should_run(n)
    return only == nil or only == n
end

local function banner(n, title)
    print("")
    print(">>> [演示点 " .. n .. "] " .. title)
end

--------------------------------------------------------------------------
-- 建立调试连接
--------------------------------------------------------------------------
local HOST, PORT = "127.0.0.1", 8818

local socket_ok, socket = pcall(require, "socket")

-- 探测 TCP 端口是否已在监听（Server 模式下用来判断 IDEA 是否先点了 Debug）
local function port_open(host, port)
    if not socket_ok then return true end          -- 没有 socket 就无法探测，照常连接
    local tcp, err = socket.tcp()
    if not tcp then return true end
    tcp:settimeout(0.5)
    local ok = tcp:connect(host, port)
    if ok then tcp:close() end
    return ok ~= nil
end

if no_debug then
    print("[demo] --no-debug：跳过调试连接，纯跑一遍业务逻辑")
elseif mode == "client" then
    -- Client 模式：Lua 端监听，IDE 主动连过来（真机 / 远程调试）
    print("[demo] Client 模式：Lua 端监听 " .. HOST .. ":" .. PORT .. "，请在 IDEA 中点 Debug")
    LuaPanda.startServer(HOST, PORT)
else
    -- Server 模式：IDE 先监听，Lua 主动连过去（本地开发调试）
    -- 允许「先跑 Lua、后点 Debug」：最多等 30 秒，IDE 一就位就立刻连上
    local waited = 0
    while not port_open(HOST, PORT) and waited < 30 do
        if waited == 0 then
            print("[demo] 等待 IDEA 启动调试监听 " .. HOST .. ":" .. PORT .. " ...")
            print("       （在 IDEA 里选中 LuaPanda Demo (Server)，点 Debug 即可）")
        end
        if socket_ok and socket.sleep then socket.sleep(1) end
        waited = waited + 1
    end
    print("[demo] Server 模式：连接 IDEA " .. HOST .. ":" .. PORT .. (waited > 0 and ("（等待 " .. waited .. " 秒）") or ""))
    LuaPanda.start(HOST, PORT)
end

-- 给 IDE 一点时间完成握手和下发断点（实测 2 秒足够，留 3 秒余量）
if socket_ok and socket.sleep then socket.sleep(3) end

--------------------------------------------------------------------------
-- 演示数据
--------------------------------------------------------------------------
math.randomseed(20240922)        -- 固定随机种子，保证每次演示结果一致

local hero = {
    name    = "露娜",
    level   = 15,
    atk     = 320,
    crit    = 0.35,
    hp      = 4200,
    skills  = {
        { id = 101, name = "月光斩",   rate = 1.8, cost = 30 },
        { id = 102, name = "新月突击", rate = 2.4, cost = 45 },
        { id = 103, name = "月之终焉", rate = 4.2, cost = 90 },
    },
}

local bag = {
    capacity = 60,
    gold     = 12800,
    equip    = {
        weapon = { name = "月轮",   quality = "传说", atk = 128 },
        armor  = { name = "银铠",   quality = "史诗", def = 96  },
        ring   = { name = "星戒",   quality = "稀有", crit = 0.12 },
    },
    consumables = {
        { id = 201, name = "治疗药剂", count = 12 },
        { id = 202, name = "法力药剂", count = 8  },
    },
}

--------------------------------------------------------------------------
-- 演示点 1：行断点
--   在 calc_damage() 内的第一行下断点，观察局部变量与返回值
--------------------------------------------------------------------------
local function calc_damage(unit, skill)

    local base       = unit.atk * skill.rate          -- ★断点：行断点（1） [BP-A]
    local is_crit    = math.random() < unit.crit
    local crit_mult  = is_crit and 2.0 or 1.0
    local damage     = math.floor(base * crit_mult)
    return damage, is_crit
end

--------------------------------------------------------------------------
-- 演示点 2 / 3：条件断点 + 日志断点
--   条件断点：i == 5 时命中
--   日志断点：不中断程序，只在 Console 输出 "第 {i} 次攻击 -> {damage}"
--------------------------------------------------------------------------
local function Demo_Conditional_And_Logpoint()
    local total = 0
    local detail = {}
    for i = 1, 8 do
        local skill = hero.skills[((i - 1) % #hero.skills) + 1]
        local dmg, crit = calc_damage(hero, skill)     -- ★断点：条件断点 i == 5（2）[BP-B] / 日志断点（3）[BP-C]
        total = total + dmg
        detail[i] = { round = i, skill = skill.name, damage = dmg, crit = crit }
        print(string.format("   第 %d 次攻击：%-6s 伤害 %5d %s", i, skill.name, dmg, crit and "[暴击]" or ""))
    end
    return total, detail
end

--------------------------------------------------------------------------
-- 演示点 4：变量与 table 展开
--   在 Variables 面板里展开 hero / bag 这类嵌套 table
--------------------------------------------------------------------------
local function Demo_Variables()
    local summary = {
        hero_name  = hero.name,
        hero_atk   = hero.atk,
        skill_num  = #hero.skills,
        bag_gold   = bag.gold,
        weapon     = bag.equip.weapon.name,
    }
    local inspect = bag                                 -- ★断点：变量与 table 展开（4） [BP-D]
    print("   背包容量 " .. inspect.capacity .. "，金币 " .. inspect.gold)
    return summary
end

--------------------------------------------------------------------------
-- 演示点 5：调用堆栈
--   stack_level_1 -> stack_level_2 -> stack_level_3，在 Frames 面板里逐层跳转
--
-- 注意：这里刻意写成「先赋值再 return」而不是 `return stack_level_x(...)`。
--   后者是 Lua 的尾调用，Lua 会复用调用者的栈帧，导致 level_1/level_2 在运行时
--   根本不存在对应的调用栈帧，LuaPanda 的 getStackTable() 也就无法上报，
--   Frames 面板只会显示最内层 + 外层调用，看起来好像「丢帧」。
--   改成非尾调用，运行时才会真正压出三层栈帧，与 Frames 面板显示一致。
--------------------------------------------------------------------------
local function stack_level_3(unit, skill)
    local raw, crit = calc_damage(unit, skill)
    local final = math.floor(raw * (1 + unit.level / 100))  -- ★断点：调用堆栈（5） [BP-E]
    return final, crit
end

local function stack_level_2(unit, skill)
    local dmg, crit = stack_level_3(unit, skill)  -- 非尾调用：保留本层栈帧
    return dmg, crit
end

local function stack_level_1(unit, skill)
    local dmg, crit = stack_level_2(unit, skill)  -- 非尾调用：保留本层栈帧
    return dmg, crit
end

--------------------------------------------------------------------------
-- 演示点 6：表达式求值
--   暂停后按 Alt+F8，输入： hero.crit * 100（hero 被本函数捕获为上值，avg / dps 等局部变量亦可直接用）
--------------------------------------------------------------------------
local function Demo_Evaluate(total_damage)
    local avg      = total_damage / 8
    local dps      = avg / 0.8
    local verdict  = (avg >= 800) and "爆发充足" or "需要补强"  -- ★断点：表达式求值（6） [BP-F]
    print(string.format("   %s 平均单次伤害 %.1f，DPS %.1f，评价：%s", hero.name, avg, dps, verdict))
    return avg, dps
end

--------------------------------------------------------------------------
-- 演示点 7：手写断点
--   不依赖 IDE 打点，代码里直接写 LuaPanda.BP()，运行到此处必然暂停
--------------------------------------------------------------------------
local function Demo_ManualBP()
    local drop_rate = 0.18
    local rolls     = 25
    local expected  = rolls * drop_rate
    if LuaPanda and LuaPanda.BP then LuaPanda.BP() end   -- ★断点：手写断点 BP()（7） [BP-G]
    print(string.format("   掉落期望 %.2f 件（%d 次掉落判定，概率 %.0f%%）", expected, rolls, drop_rate * 100))
    return expected
end

--------------------------------------------------------------------------
-- 演示点 8：Watch + 单步控制
--   把 bag.gold 加进 Watch，然后 Step Over / Step Into / Step Out
--------------------------------------------------------------------------
local function apply_reward(gold, bonus)
    local after = gold + bonus                          -- ★断点：Watch + 单步控制（8） [BP-H]
    return after
end

local function Demo_Watch()
    local bonus = 1500
    bag.gold = apply_reward(bag.gold, bonus)
    print("   结算后金币：" .. bag.gold)
    return bag.gold
end

--------------------------------------------------------------------------
-- 主流程
--------------------------------------------------------------------------
local function main()
    print("=========================================================")
    print(" LuaPanda IDEA Plugin 演示  |  模式：" .. mode)
    print("=========================================================")

    if should_run(1) then
        banner(1, "行断点 —— 停在最普通的一行")
        local dmg, crit = calc_damage(hero, hero.skills[1])
        print(string.format("   单次伤害 %d %s", dmg, crit and "(暴击)" or ""))
    end

    local total, detail = 0, {}
    if should_run(2) or should_run(3) then
        -- 演示点 2 / 3 共用同一处断点：单点运行时标出真正在跑的那个点号
        local tag = (should_run(2) and should_run(3)) and "2 / 3" or (should_run(2) and "2" or "3")
        banner(tag, "条件断点 + 日志断点 —— 只在 i == 5 停下，其余只打日志")
        total, detail = Demo_Conditional_And_Logpoint()
        print("   8 轮总伤害：" .. total)
    end

    if should_run(4) then
        banner(4, "变量与 table 展开 —— 展开嵌套的 bag / hero")
        Demo_Variables()
    end

    if should_run(5) then
        banner(5, "调用堆栈 —— 三层调用，Frames 面板逐层跳")
        local f, c = stack_level_1(hero, hero.skills[3])
        print(string.format("   终极技结算 %d %s", f, c and "(暴击)" or ""))
    end

    if should_run(6) then
        banner(6, "表达式求值 —— Alt+F8 输入 hero.crit * 100")
        Demo_Evaluate(total > 0 and total or 6400)
    end

    if should_run(7) then
        banner(7, "手写断点 —— 代码里的 LuaPanda.BP()")
        Demo_ManualBP()
    end

    if should_run(8) then
        banner(8, "Watch + 单步控制 —— 盯住 bag.gold，Step Over / Into / Out")
        Demo_Watch()
    end

    print("")
    print("=============== 演示结束，Lua 进程正常退出 ===============")
end

main()
