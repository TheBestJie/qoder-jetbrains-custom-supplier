# 收尾还原：等 IDEA 退出后，把模型 Base URL 改回真实地址，并停掉代理/监视器
#
#   powershell -ExecutionPolicy Bypass -File .\_patch\tools\restore-and-cleanup.ps1
param([int]$TimeoutMinutes = 240)

$ErrorActionPreference = 'Stop'
$Proj = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Write-Host '[restore] 等待 IDEA 退出（之后自动还原配置并关闭调试用的代理）...'
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (Get-Process idea64, idea -ErrorAction SilentlyContinue) {
    if ((Get-Date) -gt $deadline) { Write-Host '[restore] TIMEOUT：IDEA 一直没退出，未做还原'; exit 2 }
    Start-Sleep -Seconds 5
}
Write-Host '[restore] IDEA 已退出，等 3 秒写盘...'
Start-Sleep -Seconds 3

Write-Host '[restore] 还原模型 Base URL（http://127.0.0.1:8899/v1 -> 原地址）'
python (Join-Path $Proj '_patch\tools\prep-proxy-test.py') --revert

Write-Host '[restore] 校验：'
python (Join-Path $Proj '_patch\tools\verify-proxy-prep.py') gpt-5.6-sol

Write-Host '[restore] 关闭本地代理与连接监视器 ...'
Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -and ($_.CommandLine -like '*proxy.py*' -or $_.CommandLine -like '*conn-monitor.ps1*') } |
    ForEach-Object {
        Write-Host "   kill PID $($_.ProcessId)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Write-Host '[restore] 完成：模型已指回真实地址，代理已关闭。'
