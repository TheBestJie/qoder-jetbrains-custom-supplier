# 把补丁后的核心 jar 装进已安装的 Qoder JetBrains 插件（需先完全关闭 IDEA）
#
#   powershell -ExecutionPolicy Bypass -File .\_patch\install-jar.ps1
#   powershell -ExecutionPolicy Bypass -File .\_patch\install-jar.ps1 -PluginDir "C:\path\to\plugins\qoder-jetbrains"
#
# 只替换 lib\instrumented-qoder-core-2026.924.1.jar 一个文件（其余文件不动），
# 覆盖前会把原文件备份成 <文件名>.orig。
param(
    [string]$PluginDir = ''
)

$ErrorActionPreference = 'Stop'

$Proj = Split-Path -Parent $PSScriptRoot
$Src  = Join-Path $Proj 'qoder-jetbrains\lib\instrumented-qoder-core-2026.924.1.jar'
$JarName = 'instrumented-qoder-core-2026.924.1.jar'

if (-not (Test-Path $Src)) {
    throw "找不到补丁产物: $Src`n请先在项目根目录运行 .\_patch\build.ps1 构建补丁 jar（需要原始 jar：把已安装插件里的 lib\instrumented-qoder-core-2026.924.1.jar 复制成 _patch\backup\instrumented-qoder-core-2026.924.1.jar.bak）。"
}

# 目标插件目录：优先用参数，其次在 JetBrains 各版本配置目录里找
if (-not $PluginDir) {
    $roots = Get-ChildItem (Join-Path $env:APPDATA 'JetBrains') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'IntelliJIdea*' -or $_.Name -like 'IdeaIC*' } |
        Sort-Object Name -Descending
    foreach ($r in $roots) {
        $candidate = Join-Path $r.FullName 'plugins\qoder-jetbrains'
        if (Test-Path (Join-Path $candidate "lib\$JarName")) { $PluginDir = $candidate; break }
    }
}
if (-not $PluginDir) { throw "没找到已安装的 qoder-jetbrains 插件目录，请用 -PluginDir 指定。" }

$Dst = Join-Path $PluginDir "lib\$JarName"
if (-not (Test-Path $Dst)) { throw "目标插件里没有 $JarName : $Dst" }

$running = Get-Process -Name 'idea64', 'idea' -ErrorAction SilentlyContinue
if ($running) { throw "IntelliJ IDEA 正在运行，请先完全退出再执行。" }

$before = (Get-FileHash $Dst -Algorithm MD5).Hash.ToLower()
$want   = (Get-FileHash $Src -Algorithm MD5).Hash.ToLower()
if ($before -eq $want) { Write-Host "已经是最新补丁版，无需替换。" -ForegroundColor Green; exit 0 }

if (-not (Test-Path "$Dst.orig")) { Copy-Item $Dst "$Dst.orig" -Force }
Copy-Item $Src $Dst -Force
$after = (Get-FileHash $Dst -Algorithm MD5).Hash.ToLower()
if ($after -ne $want) { throw "复制后校验不一致: $after != $want" }

Write-Host "已更新插件核心 jar" -ForegroundColor Green
Write-Host ("  插件目录: " + $PluginDir)
Write-Host ("  旧 md5  : " + $before)
Write-Host ("  新 md5  : " + $after)
Write-Host ""
Write-Host "下一步：启动 IDEA（请关闭该插件的自动更新），设置 → Qoder → 模型设置 → 添加模型 → 选「自定义供应商」。" -ForegroundColor Cyan
Write-Host "回滚：把 $Dst.orig 改回 $JarName 即可。" -ForegroundColor Yellow
