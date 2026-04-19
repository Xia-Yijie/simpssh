$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$desktopRoot = Split-Path -Parent $PSScriptRoot
$iconDir = Join-Path $desktopRoot "src-tauri\icons"
$pngPath = Join-Path $iconDir "icon.png"
$icoPath = Join-Path $iconDir "icon.ico"
$png32Path = Join-Path $iconDir "32x32.png"
$png128Path = Join-Path $iconDir "128x128.png"
$png256Path = Join-Path $iconDir "128x128@2x.png"

New-Item -ItemType Directory -Path $iconDir -Force | Out-Null

function New-IconBitmap([int]$size) {
  $scale = $size / 108.0

  function S([double]$value) {
    return [int][Math]::Round($value * $scale)
  }

  $bitmap = New-Object System.Drawing.Bitmap($size, $size)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml("#0F1A2E"))

  $bodyColor = [System.Drawing.ColorTranslator]::FromHtml("#1A2535")
  $titleColor = [System.Drawing.ColorTranslator]::FromHtml("#2A3548")
  $red = [System.Drawing.ColorTranslator]::FromHtml("#FF5F56")
  $yellow = [System.Drawing.ColorTranslator]::FromHtml("#FFBD2E")
  $green = [System.Drawing.ColorTranslator]::FromHtml("#27C93F")

  $rectX = S 14
  $rectY = S 26
  $rectW = S (94 - 14)
  $rectH = S (86 - 26)
  $radius = [single](S 4)

  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $diameter = $radius * 2
  $path.AddArc($rectX, $rectY, $diameter, $diameter, 180, 90)
  $path.AddArc($rectX + $rectW - $diameter, $rectY, $diameter, $diameter, 270, 90)
  $path.AddArc($rectX + $rectW - $diameter, $rectY + $rectH - $diameter, $diameter, $diameter, 0, 90)
  $path.AddArc($rectX, $rectY + $rectH - $diameter, $diameter, $diameter, 90, 90)
  $path.CloseFigure()
  $bodyBrush = New-Object System.Drawing.SolidBrush($bodyColor)
  $graphics.FillPath($bodyBrush, $path)

  $titleRect = New-Object System.Drawing.Rectangle($rectX, $rectY, $rectW, (S (38 - 26)))
  $titlePath = New-Object System.Drawing.Drawing2D.GraphicsPath
  $titlePath.AddArc($rectX, $rectY, $diameter, $diameter, 180, 90)
  $titlePath.AddArc($rectX + $rectW - $diameter, $rectY, $diameter, $diameter, 270, 90)
  $titlePath.AddLine($rectX + $rectW, $titleRect.Bottom, $rectX, $titleRect.Bottom)
  $titlePath.CloseFigure()
  $titleBrush = New-Object System.Drawing.SolidBrush($titleColor)
  $graphics.FillPath($titleBrush, $titlePath)

  foreach ($dot in @(
    @{ X = 22; Color = $red },
    @{ X = 29; Color = $yellow },
    @{ X = 36; Color = $green }
  )) {
    $r = [Math]::Max(1, (S 2.6))
    $cx = S $dot.X
    $cy = S 34
    $graphics.FillEllipse(
      (New-Object System.Drawing.SolidBrush($dot.Color)),
      [int]($cx - $r),
      [int]($cy - $r),
      [int]($r * 2),
      [int]($r * 2)
    )
  }

  $greenBrush = New-Object System.Drawing.SolidBrush($green)

  if ($size -le 32) {
    $promptPoints = @(
      (New-Object System.Drawing.Point((S 28), (S 58))),
      (New-Object System.Drawing.Point((S 40), (S 64))),
      (New-Object System.Drawing.Point((S 28), (S 70)))
    )
    $graphics.DrawLines((New-Object System.Drawing.Pen($green, [Math]::Max(1, (S 3)))), $promptPoints)
    $graphics.FillRectangle($greenBrush, (S 45), (S 66), [Math]::Max(2, (S 13)), [Math]::Max(2, (S 3)))
  } else {
    $pixels = @(
      "M22,60 h2 v1", "M23,61 h2 v1", "M24,62 h2 v1", "M25,63 h2 v1",
      "M24,64 h2 v1", "M23,65 h2 v1", "M22,66 h2 v1",
      "M37,59 h5 v1", "M36,60 h1 v2", "M42,60 h1 v1", "M37,62 h5 v1",
      "M42,63 h1 v2", "M36,65 h1 v1", "M42,65 h1 v1", "M37,66 h5 v1",
      "M44,59 h3 v1", "M45,60 h1 v6", "M44,66 h3 v1",
      "M48,59 h1 v8", "M54,59 h1 v8", "M49,60 h1 v1", "M53,60 h1 v1",
      "M50,61 h1 v1", "M52,61 h1 v1", "M51,62 h1 v1",
      "M56,59 h1 v8", "M57,59 h5 v1", "M62,60 h1 v2", "M57,62 h5 v1",
      "M65,59 h5 v1", "M64,60 h1 v2", "M70,60 h1 v1", "M65,62 h5 v1",
      "M70,63 h1 v2", "M64,65 h1 v1", "M70,65 h1 v1", "M65,66 h5 v1",
      "M73,59 h5 v1", "M72,60 h1 v2", "M78,60 h1 v1", "M73,62 h5 v1",
      "M78,63 h1 v2", "M72,65 h1 v1", "M78,65 h1 v1", "M73,66 h5 v1",
      "M80,59 h1 v8", "M86,59 h1 v8", "M81,62 h5 v1"
    )

    foreach ($item in $pixels) {
      if ($item -match '^M(?<x>\d+),(?<y>\d+)\s+h(?<w>\d+)\s+v(?<h>\d+)$') {
        $graphics.FillRectangle(
          $greenBrush,
          (S ([double]$Matches.x)),
          (S ([double]$Matches.y)),
          [Math]::Max(1, (S ([double]$Matches.w))),
          [Math]::Max(1, (S ([double]$Matches.h)))
        )
      }
    }
  }

  $bodyBrush.Dispose()
  $titleBrush.Dispose()
  $greenBrush.Dispose()
  $titlePath.Dispose()
  $path.Dispose()
  $graphics.Dispose()
  return $bitmap
}

$iconBitmaps = @(
  @{ Size = 32; Path = $png32Path },
  @{ Size = 128; Path = $png128Path },
  @{ Size = 256; Path = $pngPath },
  @{ Size = 256; Path = $png256Path }
)

$pngEntries = @()
foreach ($entry in $iconBitmaps) {
  $bitmap = New-IconBitmap $entry.Size
  try {
    $bitmap.Save($entry.Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngEntries += @{
      Size = $entry.Size
      Bytes = [System.IO.File]::ReadAllBytes($entry.Path)
    }
  } finally {
    $bitmap.Dispose()
  }
}

$icoEntries = @(
  @($pngEntries | Where-Object { $_.Size -eq 32 })[0],
  @($pngEntries | Where-Object { $_.Size -eq 128 })[0],
  @($pngEntries | Where-Object { $_.Size -eq 256 })[0]
) | Where-Object { $_ -ne $null }

$iconStream = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter($iconStream)
$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]$icoEntries.Count)

$offset = 6 + ($icoEntries.Count * 16)
foreach ($entry in $icoEntries) {
  $dimensionByte = if ($entry.Size -ge 256) { [byte]0 } else { [byte]$entry.Size }
  $writer.Write($dimensionByte)
  $writer.Write($dimensionByte)
  $writer.Write([byte]0)
  $writer.Write([byte]0)
  $writer.Write([UInt16]1)
  $writer.Write([UInt16]32)
  $writer.Write([UInt32]$entry.Bytes.Length)
  $writer.Write([UInt32]$offset)
  $offset += $entry.Bytes.Length
}

foreach ($entry in $icoEntries) {
  $writer.Write($entry.Bytes)
}

[System.IO.File]::WriteAllBytes($icoPath, $iconStream.ToArray())

$writer.Dispose()
$iconStream.Dispose()
