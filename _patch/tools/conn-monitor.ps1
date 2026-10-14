# 监视 Qoder 服务端进程的新建对外连接（判断它到底连了哪里）
$out = Join-Path $env:TEMP 'qoder-conn-monitor.log'
"=== monitor started $(Get-Date -Format 'HH:mm:ss') ===" | Set-Content -Path $out -Encoding UTF8

$deadline = (Get-Date).AddMinutes(25)
$seen = @{}
while ((Get-Date) -lt $deadline) {
    $procs = Get-Process -Name 'Qoder', 'QoderCli', 'qodercli' -ErrorAction SilentlyContinue
    if ($procs) {
        $pids = $procs.Id
        foreach ($c in (Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue)) {
            if ($pids -contains $c.OwningProcess) {
                $key = '{0}:{1}' -f $c.RemoteAddress, $c.RemotePort
                if (-not $seen.ContainsKey($key)) {
                    $seen[$key] = 1
                    '{0}  -> {1}' -f (Get-Date -Format 'HH:mm:ss'), $key | Add-Content -Path $out -Encoding UTF8
                }
            }
        }
    }
    Start-Sleep -Milliseconds 250
}
"=== monitor ended $(Get-Date -Format 'HH:mm:ss') ===" | Add-Content -Path $out -Encoding UTF8
