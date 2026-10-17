# Generate "<line>_cast.png" = "<line>.png" minus the shape in "cast_line.png".
# Opaque pixels of cast_line.png are punched out (alpha = 0) from every line texture.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\gen-cast-textures.ps1
# Re-run after changing any line texture or the cast_line shape.
Add-Type -AssemblyName System.Drawing

$dir = Join-Path $PSScriptRoot "..\client\src\main\resources\assets\hubsuite\textures\item\rod"
$maskPath = Join-Path $dir "cast_line.png"
if (-not (Test-Path $maskPath)) { Write-Error "missing mask: $maskPath"; exit 1 }

$mask = [System.Drawing.Bitmap]::FromFile((Resolve-Path $maskPath))
$lines = Get-ChildItem $dir -Filter "line_*.png" | Where-Object { $_.Name -notlike "*_cast.png" }
$done = 0
foreach ($f in $lines) {
    $src = [System.Drawing.Bitmap]::FromFile($f.FullName)
    if ($src.Width -ne $mask.Width -or $src.Height -ne $mask.Height) {
        Write-Warning ("size mismatch, skipped: " + $f.Name); $src.Dispose(); continue
    }
    $out = New-Object System.Drawing.Bitmap($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $src.Height; $y++) {
        for ($x = 0; $x -lt $src.Width; $x++) {
            $p = $src.GetPixel($x, $y)
            $m = $mask.GetPixel($x, $y)
            if ($m.A -gt 8) { $out.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0)) }
            else { $out.SetPixel($x, $y, $p) }
        }
    }
    $target = Join-Path $dir ($f.BaseName + "_cast.png")
    $out.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose(); $src.Dispose()
    $done++
}
$mask.Dispose()
Write-Host ("generated " + $done + " cast textures")
