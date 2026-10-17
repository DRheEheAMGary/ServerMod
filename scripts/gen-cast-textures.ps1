# Generate cast / fitted variants for rod layers.
#
#   line_<id>_cast.png = line_<id>.png  minus mask                 (抛竿时线被挖洞)
#   up_<id>_fit.png    = up_<id>.png    clipped to the rod shape   (升级只贴在竿身上，不溢出)
#   up_<id>_cast.png   = up_<id>.png    clipped to rod shape, then minus mask
#
# Mask: cast_<object>.png if present, else cast_string.png (universal).
# Rod shape: union of the alpha of every rod_*.png (the four rod materials share a silhouette).
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\gen-cast-textures.ps1
# Re-run after changing any line / upgrade / rod texture or the mask.
Add-Type -AssemblyName System.Drawing

$dir = Join-Path $PSScriptRoot "..\client\src\main\resources\assets\hubsuite\textures\item\rod"
$universal = Join-Path $dir "cast_string.png"

function Load-Bitmap($path) { return [System.Drawing.Bitmap]::FromFile((Resolve-Path $path)) }

function Get-Mask($base) {
    $p = Join-Path $dir ("cast_" + $base + ".png")
    if (-not (Test-Path $p)) { $p = $universal }
    if (Test-Path $p) { return (Load-Bitmap $p) }
    return $null
}

# 竿轮廓：所有 rod_*.png 不透明像素的并集
$rodShape = $null
Get-ChildItem $dir -Filter "rod_*.png" | Where-Object { $_.Name -notlike "*_cast.png" } | ForEach-Object {
    $b = Load-Bitmap $_.FullName
    if ($null -eq $rodShape) {
        $rodShape = New-Object bool[] ($b.Width * $b.Height)
    }
    for ($y = 0; $y -lt $b.Height; $y++) {
        for ($x = 0; $x -lt $b.Width; $x++) {
            if ($b.GetPixel($x, $y).A -gt 8) { $rodShape[$y * $b.Width + $x] = $true }
        }
    }
    $b.Dispose()
}

function Emit($srcPath, $outPath, $mask, $clipToRod) {
    $src = Load-Bitmap $srcPath
    $out = New-Object System.Drawing.Bitmap($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $src.Height; $y++) {
        for ($x = 0; $x -lt $src.Width; $x++) {
            $p = $src.GetPixel($x, $y)
            $clear = $false
            if ($clipToRod -and $null -ne $rodShape -and -not $rodShape[$y * $src.Width + $x]) { $clear = $true }
            if (-not $clear -and $null -ne $mask -and $mask.GetPixel($x, $y).A -gt 8) { $clear = $true }
            if ($clear) { $out.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0)) }
            else { $out.SetPixel($x, $y, $p) }
        }
    }
    $out.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose(); $src.Dispose()
}

$done = 0
foreach ($f in (Get-ChildItem $dir -Filter "line_*.png" | Where-Object { $_.Name -notlike "*_cast.png" })) {
    $mask = Get-Mask $f.BaseName
    Emit $f.FullName (Join-Path $dir ($f.BaseName + "_cast.png")) $mask $false
    if ($null -ne $mask) { $mask.Dispose() }
    $done++
}
foreach ($f in (Get-ChildItem $dir -Filter "up_*.png" | Where-Object { $_.Name -notlike "*_cast.png" -and $_.Name -notlike "*_fit.png" })) {
    Emit $f.FullName (Join-Path $dir ($f.BaseName + "_fit.png")) $null $true
    $mask = Get-Mask $f.BaseName
    Emit $f.FullName (Join-Path $dir ($f.BaseName + "_cast.png")) $mask $true
    if ($null -ne $mask) { $mask.Dispose() }
    $done++
}
Write-Host ("generated " + $done + " variants (rod shape from rod_*.png)")
