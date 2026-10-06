# 跑一次服务端 + 自检，**以日志里的结果行为准**（不依赖 RCON 响应）。
#
# 为什么不用 scripts/dev-server.sh：它的 RCON 客户端 60 秒读超时就放弃，
# 而自检（延迟判定 + 世界生成）可能跑好几分钟 —— 收不到响应就误判失败，
# 还会把服务端一起杀掉。26.1.2 的 RcomeClient 本身也只在极窄的时序下才回包。
#
# 用法: pwsh -File scripts\selftest-run.ps1 [-WaitSec 90] [-TimeoutSec 1200] [-Wipe]
param(
    [int]$WaitSec = 90,
    [int]$TimeoutSec = 1200,
    [switch]$Wipe,
    [string]$LogFile = "run\logs\latest.log"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$env:JAVA_HOME = "$env:USERPROFILE\scoop\apps\openjdk25\current"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

if ($Wipe) {
    Write-Host "[wipe] 清档（清数据 = 连地图一起清）"
    # 注意**不要**删 run\mods 与 server.properties —— 那会把依赖 jar 和 rcon 配置
    # 一起清掉，服务端起不来（踩过）。
    foreach ($t in @("run\world","run\hubsuite_lobby","run\hubsuite_survival","run\hubsuite_creative",
                     "run\hubsuite_skyblock","run\hubsuite_skyblock_hub","run\hubsuite_skyblock_classic",
                     "run\hubsuite_skyblock_ocean","run\config","run\logs","run\crash-reports")) {
        if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
Get-ChildItem run -Recurse -Filter "session.lock" -ErrorAction SilentlyContinue | Remove-Item -Force
if (Test-Path $LogFile) { Remove-Item $LogFile -Force -ErrorAction SilentlyContinue }

# 命令文件：先 sleep 占位（cmd 没有 sleep），再发自检，最后停服。
# 服务端控制台按平台默认字符集读 stdin（简中 Windows = GBK）。
$cmdFile = Join-Path $root "run\selftest-commands.txt"
$lines = @(
    "ping -n $($WaitSec + 1) 127.0.0.1 > nul",
    "hub selftest",
    "ping -n 900 127.0.0.1 > nul",
    "stop"
)
[System.IO.File]::WriteAllLines($cmdFile, $lines, [System.Text.Encoding]::GetEncoding(936))

$inner = "gradlew.bat runServer --console=plain > `"$LogFile`" 2>&1 < `"$cmdFile`""
Write-Host "[server] 启动（等待 ${WaitSec}s 后执行 hub selftest，最多等 $TimeoutSec 秒）"
$p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $inner -WorkingDirectory $root -NoNewWindow -PassThru

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$result = $null
$seen = @{}
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    Start-Sleep -Seconds 5
    if (-not (Test-Path $LogFile)) { continue }
    $raw = ""
    try { $raw = [System.IO.File]::ReadAllText($LogFile) } catch { continue }

    # 进度提示：自检步骤
    $steps = ([regex]::Matches($raw, "\[自检\] ->")).Count
    if ($steps -gt 0 -and -not $seen.ContainsKey("steps$steps")) {
        $seen["steps$steps"] = $true
        Write-Host "  [进度] 自检步骤 $steps  看门狗 $(([regex]::Matches($raw, 'A single server tick')).Count) 次"
    }
    $m = [regex]::Match($raw, "通过 (\d+) 项，失败 (\d+) 项")
    if ($m.Success) {
        $result = @{ passed = [int]$m.Groups[1].Value; failed = [int]$m.Groups[2].Value }
        break
    }
    if ($p.HasExited) { Write-Host "[server] 进程提前退出（exit=$($p.ExitCode)）"; break }
}

if (-not $p.HasExited) {
    try { $p.Kill() } catch { }
}
Get-ChildItem run -Recurse -Filter "session.lock" -ErrorAction SilentlyContinue | Remove-Item -Force

Write-Host ""
if ($result) {
    Write-Host "===== 自检结果：通过 $($result.passed) 项，失败 $($result.failed) 项 ====="
    exit $(if ($result.failed -eq 0) { 0 } else { 1 })
}
$raw = if (Test-Path $LogFile) { [System.IO.File]::ReadAllText($LogFile) } else { "" }
Write-Host "===== 未拿到结果行 ====="
Write-Host ("步骤数: " + ([regex]::Matches($raw, "\[自检\] ->")).Count)
Write-Host ("看门狗: " + ([regex]::Matches($raw, "A single server tick")).Count + " 次")
Write-Host "--- 日志最后 12 行 ---"
Get-Content $LogFile -Tail 12 -ErrorAction SilentlyContinue | ForEach-Object { $_ -replace '^\[[^\]]+\]\s*', '' }
exit 2
