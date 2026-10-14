# 可选：把打了补丁的插件装进 IntelliJ IDEA（会把现有插件目录改名备份）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\_patch\install-to-ide.ps1
#
# 注意：
#   1. 本脚本会把 IDE 里已安装的 qoder-jetbrains 重命名为 qoder-jetbrains.bak-<时间戳>，
#      然后把补丁后的插件目录整份复制过去（含 product jar，因此版本会回到 2026.924）。
#   2. 装完请在 IDE 里关闭该插件的自动更新，否则可能被市场的新版本覆盖。
#   3. 想恢复：删掉新目录，把备份目录改回 qoder-jetbrains。

$ErrorActionPreference = "Stop"

$Proj       = Split-Path -Parent $PSScriptRoot
$SrcPlugin  = Join-Path $Proj "qoder-jetbrains"
$IdePlugins = Join-Path $env:APPDATA "JetBrains\IntelliJIdea2026.2\plugins"
$DstPlugin  = Join-Path $IdePlugins "qoder-jetbrains"

if (-not (Test-Path $IdePlugins)) { throw "找不到 IDE 插件目录: $IdePlugins" }
if (-not (Test-Path (Join-Path $SrcPlugin "lib\instrumented-qoder-core-2026.924.1.jar"))) { throw "补丁后的插件目录不完整: $SrcPlugin" }

$ProductJar = Get-ChildItem (Join-Path $SrcPlugin "lib") -Filter "instrumented-qoder-product-*.jar" -ErrorAction SilentlyContinue
if (-not $ProductJar) {
    throw "源目录缺少 instrumented-qoder-product-*.jar（仓库因单文件 100MB 上限不含它）。请改用 .\_patch\install-jar.ps1 只替换核心 jar。"
}

$running = Get-Process -Name "idea64", "idea" -ErrorAction SilentlyContinue
if ($running) { throw "检测到 IntelliJ IDEA 正在运行，请先完全退出再执行。" }

Write-Host "源目录  : $SrcPlugin"
Write-Host "目标目录: $DstPlugin"
if (Test-Path $DstPlugin) {
    Write-Host "现有插件会被备份为 qoder-jetbrains.bak-<时间戳>" -ForegroundColor Yellow
}
$answer = Read-Host "确认继续？(yes/no)"
if ($answer -ne "yes") { Write-Host "已取消"; exit 1 }

if (Test-Path $DstPlugin) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    Rename-Item $DstPlugin "qoder-jetbrains.bak-$stamp"
    Write-Host "已备份为 qoder-jetbrains.bak-$stamp" -ForegroundColor Green
}

Copy-Item $SrcPlugin $DstPlugin -Recurse -Force
Write-Host "安装完成。启动 IDEA 后请在 设置 → Qoder → 个人设置 → 模型设置 → 添加模型 里验证。" -ForegroundColor Green
