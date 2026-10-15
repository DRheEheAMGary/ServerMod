param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Password = 'hubsuite-dev',
    [int]$TimeoutMs = 2000
)

# 无人值守测试用的小工具：往运行中的开发服务端发一条 RCON 命令。
# 用法： pwsh -File scripts/rcon.ps1 -Command "give Player123 hubsuite:nether_string 3"
# 说明： 26.1.2 的 RCON 回包时序很挑（见 AGENT.md），所以这里只保证"命令发出去"：
#        认证包发完直接发命令，回包能读到就打印、读不到也不报错。
#        刻意不定义函数（实测在这个环境里同文件内定义的函数会找不到），全部平铺。

$client = New-Object System.Net.Sockets.TcpClient
try {
    $client.Connect($ServerHost, $Port)
} catch {
    Write-Error ("连不上 RCON " + $ServerHost + ":" + $Port + " —— 服务端起了吗？（enable-rcon=true）")
    exit 2
}
$stream = $client.GetStream()
$stream.ReadTimeout = $TimeoutMs

# ---- 认证包（type 3）----
$authBytes = [System.Text.Encoding]::UTF8.GetBytes($Password)
$authLength = 4 + 4 + $authBytes.Length + 2
$authMs = New-Object System.IO.MemoryStream
$authBw = New-Object System.IO.BinaryWriter($authMs)
$authBw.Write([int]$authLength)
$authBw.Write([int]1)
$authBw.Write([int]3)
$authBw.Write($authBytes)
$authBw.Write([byte]0)
$authBw.Write([byte]0)
$authBw.Flush()
$authPacket = $authMs.ToArray()
$stream.Write($authPacket, 0, $authPacket.Length)
$stream.Flush()
Start-Sleep -Milliseconds 120

# ---- 命令包（type 2）----
$cmdBytes = [System.Text.Encoding]::UTF8.GetBytes($Command)
$cmdLength = 4 + 4 + $cmdBytes.Length + 2
$cmdMs = New-Object System.IO.MemoryStream
$cmdBw = New-Object System.IO.BinaryWriter($cmdMs)
$cmdBw.Write([int]$cmdLength)
$cmdBw.Write([int]2)
$cmdBw.Write([int]2)
$cmdBw.Write($cmdBytes)
$cmdBw.Write([byte]0)
$cmdBw.Write([byte]0)
$cmdBw.Flush()
$cmdPacket = $cmdMs.ToArray()
$stream.Write($cmdPacket, 0, $cmdPacket.Length)
$stream.Flush()
Start-Sleep -Milliseconds 200

# ---- 尽力读回包（读不到就算了）----
try {
    $lenBytes = New-Object byte[] 4
    if ($stream.Read($lenBytes, 0, 4) -eq 4) {
        $length = [System.BitConverter]::ToInt32($lenBytes, 0)
        $payload = New-Object byte[] $length
        $read = 0
        while ($read -lt $length) {
            $n = $stream.Read($payload, $read, $length - $read)
            if ($n -le 0) { break }
            $read += $n
        }
        $text = [System.Text.Encoding]::UTF8.GetString($payload, 8, $length - 10)
        if ($text) { Write-Output $text }
    }
} catch {
    # 回包读不到是常态，不算失败
}
$client.Close()
exit 0
