# 等 IDEA 完全退出后，把指定模型的 Base URL 临时指向本地抓包代理，并打印结果
#
#   powershell -ExecutionPolicy Bypass -File .\_patch\tools\watch-and-prep.ps1
#   powershell -ExecutionPolicy Bypass -File .\_patch\tools\watch-and-prep.ps1 -Models gpt-5.6-sol
param(
    [string[]]$Models = @('gpt-5.6-sol'),
    [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
$Proj = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 项目根目录

Write-Host '[watch] 等待 IDEA 完全退出（最多 ' -NoNewline
Write-Host "$TimeoutMinutes 分钟）..."
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (Get-Process idea64, idea -ErrorAction SilentlyContinue) {
    if ((Get-Date) -gt $deadline) {
        Write-Host '[watch] TIMEOUT：IDEA 一直没退出，未做任何修改。'
        exit 2
    }
    Start-Sleep -Seconds 2
}

Write-Host '[watch] IDEA 已退出，等 3 秒让设置写完 ...'
Start-Sleep -Seconds 3

python (Join-Path $Proj '_patch\tools\prep-proxy-test.py') @Models
Write-Host "[watch] prep 退出码: $LASTEXITCODE"

Write-Host '[watch] === 修改后校验 ==='
python (Join-Path $Proj '_patch\tools\verify-proxy-prep.py') @Models

Write-Host ''
Write-Host '[watch] 完成：现在可以启动 IDEA，用 gpt-5.6-sol 发一张图。'
Write-Host ('[watch] 还原命令: python ' + (Join-Path $Proj '_patch\tools\prep-proxy-test.py') + ' --revert')
