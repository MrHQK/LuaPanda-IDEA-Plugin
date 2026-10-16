# =============================================================================
# LuaPanda-IDEA-Plugin 一键打包脚本
# -----------------------------------------------------------------------------
# 用法（在项目根目录或任意位置均可）：
#
#   .\scripts\pack.ps1                      # 仅打包（增量）
#   .\scripts\pack.ps1 -Clean               # 先 clean 再打包
#   .\scripts\pack.ps1 -Deploy              # 打包后自动部署到本机 IDEA
#   .\scripts\pack.ps1 -Clean -Deploy       # 全量打包并部署
#   .\scripts\pack.ps1 -OpenFolder          # 打包完后打开产物目录
#   .\scripts\pack.ps1 -SkipTests           # 跳过测试任务（默认就是不跑 test）
#
# 产物：
#   build\distributions\<pluginName>-<pluginVersion>.zip
#   并复制一份到项目根目录方便分发： .\<pluginName>-<pluginVersion>.zip
#
# 部署位置（-Deploy）：
#   %APPDATA%\JetBrains\IntelliJIdea<最新版本>\plugins\<pluginName>\
#   注意：部署前请关闭 IDEA，否则 jar 被锁会失败。
# =============================================================================

[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$Deploy,
    [switch]$OpenFolder,
    [switch]$SkipTests = $true   # 默认跳过测试，加速打包；如需跑测试用 -SkipTests:$false
)

$ErrorActionPreference = 'Stop'

# ---- 1. 切到项目根目录 -------------------------------------------------------
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectRoot = Split-Path -Parent $ScriptDir
Set-Location $ProjectRoot
Write-Host "==> 项目根目录: $ProjectRoot" -ForegroundColor Cyan

# ---- 2. 校验 JAVA_HOME -------------------------------------------------------
# 只认外部环境变量，不硬编码任何本机路径（避免随仓库公开）
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    Write-Error "JAVA_HOME 未设置或路径不存在（当前值: '$env:JAVA_HOME'）— 请安装 JDK 17 并设置 JAVA_HOME 环境变量"
    exit 1
}
Write-Host "==> JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan

# ---- 3. 读取 pluginName / pluginVersion --------------------------------------
$gradleProps = Join-Path $ProjectRoot 'gradle.properties'
if (-not (Test-Path $gradleProps)) {
    Write-Error "未找到 gradle.properties: $gradleProps"
    exit 1
}
$props = Get-Content $gradleProps | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    [PSCustomObject]@{ Key = $kv[0].Trim(); Value = $kv[1].Trim() }
}
$pluginName    = ($props | Where-Object Key -eq 'pluginName').Value
$pluginVersion = ($props | Where-Object Key -eq 'pluginVersion').Value
if (-not $pluginName -or -not $pluginVersion) {
    Write-Error "解析 gradle.properties 失败：pluginName=$pluginName, pluginVersion=$pluginVersion"
    exit 1
}
Write-Host "==> 插件: $pluginName  版本: $pluginVersion" -ForegroundColor Cyan

# ---- 4. 组装 Gradle 任务 ------------------------------------------------------
$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += 'buildPlugin'
if ($SkipTests) {
    $extraArgs = @('-x', 'test')
} else {
    $extraArgs = @()
}

# ---- 5. 执行构建 -------------------------------------------------------------
$gradlew = Join-Path $ProjectRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    Write-Error "未找到 gradlew.bat: $gradlew"
    exit 1
}

$startTime = Get-Date
Write-Host ""
Write-Host "==> 执行: gradlew $($tasks -join ' ') $($extraArgs -join ' ') --console=plain" -ForegroundColor Yellow
& $gradlew @tasks @extraArgs --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle 构建失败，退出码: $LASTEXITCODE"
    exit $LASTEXITCODE
}
$duration = (Get-Date) - $startTime
Write-Host "==> Gradle 任务完成，用时 $([int]$duration.TotalSeconds) 秒" -ForegroundColor Green

# ---- 6. 定位产物 zip ---------------------------------------------------------
# IntelliJ Platform Gradle Plugin 生成的 zip 命名为 ${rootProject.name}-${version}.zip，
# 与 pluginName 不一定相同（本项目 rootProject.name=LuaPanda-IDEA-Plugin, pluginName=LuaPanda）。
# 直接挑 distributions 目录里 version 匹配的那个 zip，更鲁棒。
$distDir = Join-Path $ProjectRoot 'build\distributions'
$zipFile = Get-ChildItem -Path $distDir -Filter "*-$pluginVersion.zip" -ErrorAction SilentlyContinue `
    | Sort-Object LastWriteTime -Descending `
    | Select-Object -First 1
if (-not $zipFile) {
    Write-Error "未在 $distDir 下找到 *-$pluginVersion.zip"
    exit 1
}
$zipPath = $zipFile.FullName
$zipName = $zipFile.Name
$zipSize = [math]::Round($zipFile.Length / 1KB, 1)
Write-Host "==> 产物: $zipPath  ($zipSize KB)" -ForegroundColor Green

# ---- 7. 复制一份到项目根目录方便分发 -----------------------------------------
$rootZip = Join-Path $ProjectRoot $zipName
Copy-Item -Path $zipPath -Destination $rootZip -Force
Write-Host "==> 已复制到: $rootZip" -ForegroundColor Green

# ---- 8. 可选：部署到本机 IDEA -----------------------------------------------
if ($Deploy) {
    Write-Host ""
    Write-Host "==> 开始部署到本机 IntelliJ IDEA ..." -ForegroundColor Yellow

    $jetbrainsDir = Join-Path $env:APPDATA 'JetBrains'
    if (-not (Test-Path $jetbrainsDir)) {
        Write-Warning "未发现 JetBrains 配置目录: $jetbrainsDir，跳过部署"
    } else {
        $ideaConfig = Get-ChildItem -Path $jetbrainsDir -Directory `
            | Where-Object { $_.Name -like 'IntelliJIdea*' } `
            | Sort-Object Name -Descending `
            | Select-Object -First 1
        if (-not $ideaConfig) {
            Write-Warning "未发现 IntelliJIdea* 配置目录，跳过部署"
        } else {
            $pluginsDir = Join-Path $ideaConfig.FullName 'plugins'
            $targetDir  = Join-Path $pluginsDir $pluginName
            Write-Host "    目标目录: $targetDir" -ForegroundColor Cyan

            # 检测 IDEA 是否在运行（jar 锁问题）
            $ideaRunning = Get-Process -Name 'idea64', 'idea' -ErrorAction SilentlyContinue
            if ($ideaRunning) {
                Write-Warning "检测到 IDEA 正在运行 (PID: $($ideaRunning.Id -join ',')) ，部署可能失败。建议先关闭 IDEA。"
                $resp = Read-Host "仍然继续部署？(y/N)"
                if ($resp -notin @('y', 'Y')) {
                    Write-Host "==> 已取消部署。" -ForegroundColor Yellow
                    exit 0
                }
            }

            if (Test-Path $targetDir) {
                Remove-Item -Path $targetDir -Recurse -Force
            }
            if (-not (Test-Path $pluginsDir)) {
                New-Item -Path $pluginsDir -ItemType Directory -Force | Out-Null
            }

            # 解压 zip 到 plugins 目录（zip 内顶层就是 <pluginName>/）
            Expand-Archive -Path $zipPath -DestinationPath $pluginsDir -Force
            if (Test-Path $targetDir) {
                Write-Host "==> 部署成功: $targetDir" -ForegroundColor Green
                Write-Host "    请启动 / 重启 IntelliJ IDEA 加载插件。" -ForegroundColor Green
            } else {
                Write-Warning "部署后未发现目标目录: $targetDir — 请检查 zip 结构"
            }
        }
    }
}

# ---- 9. 可选：打开产物目录 ---------------------------------------------------
if ($OpenFolder) {
    Start-Process explorer.exe $distDir
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host " 打包完成: $rootZip" -ForegroundColor Green
Write-Host " 安装方式 1（推荐）: IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk → 选择上面的 zip" -ForegroundColor Green
if (-not $Deploy) {
    Write-Host " 安装方式 2（自动）: 关闭 IDEA 后重新执行 .\scripts\pack.ps1 -Deploy" -ForegroundColor Green
}
Write-Host "============================================================" -ForegroundColor Green
