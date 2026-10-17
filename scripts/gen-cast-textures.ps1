# Generate cast variants (mask subtraction only — no clipping of artwork).
#
#   line_<id>_cast.png = line_<id>.png minus mask
#   up_<id>_cast.png   = up_<id>.png   minus mask   (升级有些像素压在线上，抛竿时也要挖)
#
# Mask lookup: cast_<object>.png if present, else cast_string.png (universal).
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\gen-cast-textures.ps1
# Re-run after changing any line/upgrade texture or the mask.
Add-Type -AssemblyName System.Drawing

$dir = Join-Path $PSScriptRoot "..\client\src\main\resources\assets\hubsuite\textures\item\rod"
$universal = Join-Path $dir "cast_string.png"
$sources = @()
$sources += (Get-ChildItem $dir -Filter "line_*.png" | Where-Object { $_.Name -notlike "*_cast.png" })
$sources += (Get-ChildItem $dir -Filter "up_*.png"   | Where-Object { $_.Name -notlike "*_cast.png" -and $_.Name -notlike "*_fit.png" })
$done = 0
$cut = 0
foreach ($f in $sources) {
    $maskPath = Join-Path $dir ("cast_" + $f.BaseName + ".png")
    if (-not (Test-Path $maskPath)) { $maskPath = $universal }
    $src = [System.Drawing.Bitmap]::FromFile($f.FullName)
    $mask = $null
    if (Test-Path $maskPath) {
        $mask = [System.Drawing.Bitmap]::FromFile((Resolve-Path $maskPath))
        if ($mask.Width -ne $src.Width -or $mask.Height -ne $src.Height) {
            Write-Warning ("size mismatch, mask ignored: " + $maskPath); $mask.Dispose(); $mask = $null
        }
    }
    $out = New-Object System.Drawing.Bitmap($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $src.Height; $y++) {
        for ($x = 0; $x -lt $src.Width; $x++) {
            $p = $src.GetPixel($x, $y)
            if ($null -ne $mask -and $mask.GetPixel($x, $y).A -gt 8) {
                $out.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
            } else { $out.SetPixel($x, $y, $p) }
        }
    }
    $out.Save((Join-Path $dir ($f.BaseName + "_cast.png")), [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose(); $src.Dispose()
    if ($null -ne $mask) { $mask.Dispose(); $cut++ }
    $done++
}
Write-Host ("generated " + $done + " cast textures (" + $cut + " used a mask) — no clipping")
