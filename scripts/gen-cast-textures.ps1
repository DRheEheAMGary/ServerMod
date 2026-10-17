# Generate derived textures for rod layers (never touches the source art).
#
#   line_<id>_cast.png  = line_<id>.png  minus mask
#   up_<id>_cover.png   = up_<id>.png with binarised alpha (any pixel -> fully opaque)
#                         "先减掉被覆盖的像素再加"：升级覆盖处直接顶掉竿的像素，不和竿色混合
#   up_<id>_cast.png    = up_<id>_cover.png minus mask
#
# Mask lookup: cast_<object>.png if present, else cast_string.png (universal).
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\gen-cast-textures.ps1
Add-Type -AssemblyName System.Drawing

$dir = Join-Path $PSScriptRoot "..\client\src\main\resources\assets\hubsuite\textures\item\rod"
$universal = Join-Path $dir "cast_string.png"
$THRESHOLD = 8

function Get-Mask($base) {
    $p = Join-Path $dir ("cast_" + $base + ".png")
    if (-not (Test-Path $p)) { $p = $universal }
    if (Test-Path $p) { return [System.Drawing.Bitmap]::FromFile((Resolve-Path $p)) }
    return $null
}

function Write-Out($src, $outPath, $binarize, $mask) {
    $b = [System.Drawing.Bitmap]::FromFile($src.FullName)
    $out = New-Object System.Drawing.Bitmap($b.Width, $b.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $b.Height; $y++) {
        for ($x = 0; $x -lt $b.Width; $x++) {
            $p = $b.GetPixel($x, $y)
            $px = $p
            if ($binarize -and $p.A -gt $THRESHOLD) {
                $px = [System.Drawing.Color]::FromArgb(255, $p.R, $p.G, $p.B)
            } elseif ($binarize) {
                $px = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
            }
            if ($null -ne $mask -and $mask.GetPixel($x, $y).A -gt $THRESHOLD) {
                $px = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
            }
            $out.SetPixel($x, $y, $px)
        }
    }
    $out.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose(); $b.Dispose()
}

foreach ($f in (Get-ChildItem $dir -Filter "line_*.png" | Where-Object { $_.Name -notlike "*_cast.png" })) {
    $m = Get-Mask $f.BaseName
    Write-Out $f (Join-Path $dir ($f.BaseName + "_cast.png")) $false $m
    if ($null -ne $m) { $m.Dispose() }
}
foreach ($f in (Get-ChildItem $dir -Filter "up_*.png" | Where-Object { $_.Name -notlike "*_cast.png" -and $_.Name -notlike "*_cover.png" })) {
    Write-Out $f (Join-Path $dir ($f.BaseName + "_cover.png")) $true $null
    $m = Get-Mask $f.BaseName
    Write-Out $f (Join-Path $dir ($f.BaseName + "_cast.png")) $true $m
    if ($null -ne $m) { $m.Dispose() }
}
Write-Host "generated derived textures (lines: cut / upgrades: cover+cut)"
