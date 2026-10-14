# 等 IDEA 退出后：杀掉 Qoder 共享服务端进程（清掉它的内存模型缓存），并校验配置
#
#   powershell -ExecutionPolicy Bypass -File .\_patch\tools\watch-kill-server.ps1
param([int]$TimeoutMinutes = 40)

$ErrorActionPreference = 'Stop'
$Proj = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Write-Host '[watch] 等待 IDEA 完全退出...'
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (Get-Process idea64, idea -ErrorAction SilentlyContinue) {
    if ((Get-Date) -gt $deadline) { Write-Host '[watch] TIMEOUT'; exit 2 }
    Start-Sleep -Seconds 2
}
Write-Host '[watch] IDEA 已退出，等 3 秒...'
Start-Sleep -Seconds 3

Write-Host '[watch] 杀掉 Qoder 共享服务端进程（清内存缓存）...'
$qd = Get-Process -Name 'Qoder', 'QoderCli', 'qodercli' -ErrorAction SilentlyContinue
if ($qd) {
    foreach ($p in $qd) { Write-Host "   kill PID $($p.Id) $($p.ProcessName) (started $($p.StartTime))" }
    $qd | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
} else {
    Write-Host '   （没有运行中的 Qoder 服务端进程）'
}
$left = Get-Process -Name 'Qoder', 'QoderCli', 'qodercli' -ErrorAction SilentlyContinue
Write-Host "[watch] 残留服务端进程: $(if ($left) { ($left.Id -join ',') } else { '无 ✔' })"

Write-Host '[watch] 校验模型配置（应为本地代理）:'
python (Join-Path $Proj '_patch\tools\verify-proxy-prep.py') gpt-5.6-sol

Write-Host '[watch] 校验代理是否在监听:'
$probe = Get-NetTCPConnection -LocalPort 8899 -State Listen -ErrorAction SilentlyContinue
Write-Host "   8899 listening: $(if ($probe) { 'yes ✔' } else { 'NO ✗' })"

Write-Host ''
Write-Host '[watch] 完成：现在启动 IDEA，新建会话，用 gpt-5.6-sol 发一张图。'
