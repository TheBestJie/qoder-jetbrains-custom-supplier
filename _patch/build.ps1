# 一键重建脚本：编译 CustomEndpointProvider -> 打补丁 -> 校验 -> 行为测试 -> 部署
#
# 用法（在 PowerShell 里）：
#   cd <项目根>\qoder-jetbrains-2026.924.75731882
#   powershell -ExecutionPolicy Bypass -File .\_patch\build.ps1
#
# 说明：
#   - 永远基于 _patch\backup\*.bak（原始 jar）生成，可反复执行
#   - 最后一步会覆盖 qoder-jetbrains\lib 下的同名 jar；不想部署就注释掉最后两行

$ErrorActionPreference = "Stop"

$Proj   = Split-Path -Parent $PSScriptRoot          # qoder-jetbrains-2026.924.75731882
$Patch  = $PSScriptRoot
$Plugin = Join-Path $Proj "qoder-jetbrains"
$Lib    = Join-Path $Plugin "lib"

$Idea   = "D:\01Applications\JetBrains\IntelliJ IDEA 2026.2.3"
$Jdk    = "D:\02Environment\jdks\openjdk-27"

$OrigJar = Join-Path $Lib "instrumented-qoder-core-2026.924.1.jar"
$BakJar  = Join-Path $Patch "backup\instrumented-qoder-core-2026.924.1.jar.bak"
$OutJar  = Join-Path $Patch "dist\instrumented-qoder-core-2026.924.1.jar"

$AsmJar    = Join-Path $Idea "lib\intellij.libraries.asm.jar"
$AsmTools  = Join-Path $Idea "lib\intellij.libraries.asm.tools.jar"
$KotlinLib = Join-Path $Idea "plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar"

New-Item -ItemType Directory -Force -Path "$Patch\classes", "$Patch\tools-classes", "$Patch\dist" | Out-Null

if (-not (Test-Path $BakJar)) {
    throw "缺少原始 jar: $BakJar`n把已安装插件里的 lib\instrumented-qoder-core-2026.924.1.jar 复制成该文件即可（插件目录形如 %APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\qoder-jetbrains\lib\）。"
}

Write-Host "[1/6] 编译 CustomEndpointProvider ..." -ForegroundColor Cyan
$pluginCp = "$BakJar;$(Join-Path $Lib 'annotations-24.0.1.jar');$Idea\lib\*;$KotlinLib"
& "$Jdk\bin\javac.exe" -encoding UTF-8 --release 11 -nowarn -cp $pluginCp -d "$Patch\classes" "$Patch\src\CustomEndpointProvider.java"
if ($LASTEXITCODE -ne 0) { throw "CustomEndpointProvider 编译失败" }

Write-Host "[2/6] 编译 Patcher / VerifyClass ..." -ForegroundColor Cyan
& "$Jdk\bin\javac.exe" -encoding UTF-8 --release 11 -nowarn -cp $AsmJar -d "$Patch\tools-classes" "$Patch\tools\Patcher.java"
if ($LASTEXITCODE -ne 0) { throw "Patcher 编译失败" }
& "$Jdk\bin\javac.exe" -encoding UTF-8 --release 11 -nowarn -cp "$AsmTools;$AsmJar" -d "$Patch\tools-classes" "$Patch\tools\VerifyClass.java"
if ($LASTEXITCODE -ne 0) { throw "VerifyClass 编译失败" }

Write-Host "[3/6] 应用字节码补丁 ..." -ForegroundColor Cyan
& "$Jdk\bin\java.exe" -cp "$AsmJar;$Patch\tools-classes" Patcher $BakJar $OutJar "$Patch\classes"
if ($LASTEXITCODE -ne 0) { throw "打补丁失败" }

Write-Host "[4/6] 编译 MetaTest（行为级测试）..." -ForegroundColor Cyan
$runCp = "$OutJar;$(Join-Path $Lib 'annotations-24.0.1.jar');$Idea\lib\*;$KotlinLib"
& "$Jdk\bin\javac.exe" -encoding UTF-8 --release 11 -nowarn -cp $runCp -d "$Patch\tools-classes" "$Patch\tools\MetaTest.java"
if ($LASTEXITCODE -ne 0) { throw "MetaTest 编译失败" }

Write-Host "[5/6] 全量数据流校验 + 行为测试 ..." -ForegroundColor Cyan
$verifyDir = "$Patch\work\all-verify"
if (Test-Path $verifyDir) { Remove-Item $verifyDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $verifyDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($OutJar)
try {
    foreach ($e in $zip.Entries) {
        if ($e.FullName.EndsWith(".class")) {
            $dst = Join-Path $verifyDir $e.FullName
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
        }
    }
} finally { $zip.Dispose() }

& "$Jdk\bin\java.exe" -cp "$AsmTools;$AsmJar;$Patch\tools-classes" VerifyClass $verifyDir | Select-Object -Last 1
if ($LASTEXITCODE -ne 0) { throw "字节码校验失败，未部署" }

# Windows PowerShell 5.1 会把子进程写往 stderr 的内容当成错误记录，这里临时放宽
$prevPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& "$Jdk\bin\java.exe" -cp "$runCp;$Patch\tools-classes" MetaTest 1> "$Patch\work\metatest.txt" 2> "$Patch\work\metatest.err.txt"
$metaTestExit = $LASTEXITCODE
$ErrorActionPreference = $prevPreference
if ($metaTestExit -ne 0) {
    Get-Content "$Patch\work\metatest.txt" | Select-String -Pattern "^\[FAIL\]" | ForEach-Object { $_.Line }
    throw "MetaTest failed, not deployed (see _patch\work\metatest.txt)"
}
Get-Content "$Patch\work\metatest.txt" | Select-String -Pattern "META TEST"

Write-Host "[6/6] 部署到 qoder-jetbrains\lib ..." -ForegroundColor Cyan
Copy-Item $OutJar $OrigJar -Force
Write-Host "完成: $OrigJar" -ForegroundColor Green
Write-Host "回滚: 把 $BakJar 复制回 $OrigJar" -ForegroundColor Yellow
