# Generate "<line>_cast.png" = "<line>.png" minus the mask shape.
#
# Mask lookup order for every line texture line_<id>.png:
#   1. cast_<id>.png      (per-line override, optional)
#   2. cast_string.png    (the universal mask used by all lines)
# Opaque pixels of the mask are punched out (alpha = 0); everything else is copied as-is.
# No mask at all -> the cast variant is just a copy (nothing is cut).
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\gen-cast-textures.ps1
# Re-run after changing any line texture or the mask.
Add-Type -AssemblyName System.Drawing

$dir = Join-Path $PSScriptRoot "..\client\src\main\resources\assets\hubsuite\textures\item\rod"
$universal = Join-Path $dir "cast_string.png"
$lines = Get-ChildItem $dir -Filter "line_*.png" | Where-Object { $_.Name -notlike "*_cast.png" }
$done = 0
$cut = 0
foreach ($f in $lines) {
    $id = $f.BaseName.Substring(5)                       # line_<id> -> <id>
    $maskPath = Join-Path $dir ("cast_" + $id + ".png")
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
    $target = Join-Path $dir ($f.BaseName + "_cast.png")
    $out.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose(); $src.Dispose()
    if ($null -ne $mask) { $mask.Dispose(); $cut++ }
    $done++
}
Write-Host ("generated " + $done + " cast textures (" + $cut + " used a mask)")
