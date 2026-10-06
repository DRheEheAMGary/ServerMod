# 无人值守跑一次服务端并执行控制台命令（Windows / PowerShell 版）
#
# 定位：这是 scripts/dev-server.sh 在**没有 bash + python3 的 Windows 上**的替代品。
# 优先用 dev-server.sh —— 它靠 RCON 把命令输出取回来，更省事。本脚本的现状：
#   · 能起服务端、能等就绪、能执行命令（实测 hub selftest 确实跑起来了）；
#   · **但命令输出拿不回来** —— 结果只落在 run/logs/latest.log 里，要自己去翻。
# 别把它当 dev-server.sh 的等价替代。
#
# 已知的坑（都踩过，别再走一遍）：
#   1) RCON 在 26.1.2 上不可靠。com.mojang 的 RconClient 读包是
#      `read(buf,0,1460)` 只读一次，紧接 `if (i > 10) closeSocket()`
#      （javap 反编译实证）—— 关不关连接取决于 TCP 分段。实测**同一个载荷
#      一次成功一次被静默关闭**；dev-server.sh 的 RCON 路子在本机也复现了这个
#      TimeoutError，所以那两次也没能取回结果。
#   2) 别用 .NET 管道（RedirectStandardInput）喂命令 —— 命令到不了服务端。
#      必须用 `< 命令文件` 这种文件重定向。
#   3) 命令文件里除命令本身不能有别的东西：服务端会把每一行都当 Minecraft 命令，
#      批处理注释和 `exit` 会被打成 "Unknown or incomplete command"。
#
# 用法: pwsh -File scripts\run-server-commands.ps1 -Commands "hub selftest"
param(
    [string[]]$Commands = @("hub selftest"),
    [string]$LogFile = "run\logs\console-capture.log",
    [int]$StartupDelaySec = 150,
    [int]$TimeoutSec = 900
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = "$env:USERPROFILE\scoop\apps\openjdk25\current"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 清掉强杀遗留的会话锁，否则服务端拒绝启动
Get-ChildItem run -Recurse -Filter "session.lock" -ErrorAction SilentlyContinue | Remove-Item -Force
$logPath = Join-Path $root $LogFile
New-Item -ItemType Directory -Force -Path (Split-Path $logPath) | Out-Null

# 命令文件：ping 占位延时（cmd 没有 sleep），然后逐条命令，最后 stop
$cmdFile = Join-Path $root "run\console-commands.txt"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("ping -n $($StartupDelaySec + 1) 127.0.0.1 > nul")
foreach ($c in $Commands) {
    $lines.Add($c)
    $lines.Add("ping -n 4 127.0.0.1 > nul")
}
$lines.Add("stop")
# 服务端控制台用平台默认字符集读 stdin（简中 Windows = GBK），
# 写成 UTF-8 会让中文命令变乱码 —— 这条踩过。
[System.IO.File]::WriteAllLines($cmdFile, $lines, [System.Text.Encoding]::GetEncoding(936))

Write-Host "[server] 命令文件：$cmdFile（延时 ${StartupDelaySec}s 后执行 $($Commands -join ' / ')）"

$inner = "gradlew.bat runServer --console=plain > `"$LogFile`" 2>&1 < `"$cmdFile`""
Write-Host "[server] 启动：cmd /c $inner"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $inner -WorkingDirectory $root -NoNewWindow -PassThru
$exited = $p.WaitForExit($TimeoutSec * 1000)
$sw.Stop()

if (-not $exited) {
    Write-Host "[server] $TimeoutSec 秒未退出，强制结束"
    try { $p.Kill() } catch { }
} else {
    Write-Host "[server] 已退出（exit=$($p.ExitCode)，耗时 $([math]::Round($sw.Elapsed.TotalSeconds)) 秒）"
}

Write-Host "[server] 日志：$logPath"
if (Test-Path $logPath) {
    $raw = [System.IO.File]::ReadAllText($logPath)
    foreach ($pat in @("RCON running on", "\[自检\]", "通过 \d+ 项，失败 \d+ 项")) {
        $n = ([regex]::Matches($raw, $pat)).Count
        Write-Host ("         标记 {0,-32} 出现 {1} 次" -f $pat, $n)
    }
}
