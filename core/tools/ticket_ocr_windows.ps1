# AirZelo - offline ticket text recognition for Windows 10/11.
#
# Uses only what ships with Windows: Windows.Data.Pdf renders PDF pages and Windows.Media.Ocr reads the text
# on this computer (no network, no API key). The core (server/pdftext.py) calls this script for scanned PDF pages
# and images, and rebuilds reading-order lines from the word boxes printed here as JSON on stdout.
#
# Usage (Windows PowerShell 5.1, which has the WinRT projection; PowerShell 7 does not):
#   powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ticket_ocr_windows.ps1 `
#       -Path <file> [-Preview <png>] [-Pages 1,3]
# -Pages lists the PDF pages to recognise (1-based); omit it to recognise every page. Images are always recognised.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Preview = "",
    [string]$Pages = ""
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Emit($obj) { [Console]::Out.WriteLine((ConvertTo-Json -InputObject $obj -Depth 8 -Compress)) }

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
    $null = [Windows.Data.Pdf.PdfDocument, Windows.Data.Pdf, ContentType = WindowsRuntime]
    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
    $null = [Windows.Media.Ocr.OcrEngine, Windows.Media.Ocr, ContentType = WindowsRuntime]
    $null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
} catch {
    Emit @{ ok = $false; error = "Windows text recognition is not available on this computer (Windows 10 or 11 is needed)." }
    exit 0
}

$extensions = [System.WindowsRuntimeSystemExtensions].GetMethods()
$asTaskOp = $extensions | Where-Object { $_.Name -eq "AsTask" -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
$asTaskAction = $extensions | Where-Object { $_.Name -eq "AsTask" -and -not $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq "IAsyncAction" } | Select-Object -First 1

function Await($op, [Type]$type) {
    $task = $asTaskOp.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(-1)
    return $task.Result
}
function AwaitAction($op) {
    $task = $asTaskAction.Invoke($null, @($op))
    $null = $task.Wait(-1)
}

function New-OcrEngine {
    $engine = $null
    try { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language("en-US"))) } catch { }
    if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
    return $engine
}

# OCR one image stream; returns the page size and every word with its box (pixels, y grows downwards).
function Read-Stream($stream, $engine, [int]$pageNo) {
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $w = [int]$decoder.PixelWidth
    $h = [int]$decoder.PixelHeight
    $max = [int][Windows.Media.Ocr.OcrEngine]::MaxImageDimension
    $scale = 1.0
    if ($w -lt 1600) { $scale = 2.0 }                                  # small screenshots: upscale so small text reads well
    if ([Math]::Max($w, $h) * $scale -gt $max) { $scale = $max / [double][Math]::Max($w, $h) }
    $transform = New-Object Windows.Graphics.Imaging.BitmapTransform
    $transform.ScaledWidth = [uint32][Math]::Floor($w * $scale)
    $transform.ScaledHeight = [uint32][Math]::Floor($h * $scale)
    $transform.InterpolationMode = [Windows.Graphics.Imaging.BitmapInterpolationMode]::Fant
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync([Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8, [Windows.Graphics.Imaging.BitmapAlphaMode]::Premultiplied,
        $transform, [Windows.Graphics.Imaging.ExifOrientationMode]::RespectExifOrientation, [Windows.Graphics.Imaging.ColorManagementMode]::DoNotColorManage)) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    $words = New-Object System.Collections.ArrayList
    foreach ($line in $result.Lines) {
        foreach ($word in $line.Words) {
            $r = $word.BoundingRect
            $null = $words.Add(@{ x = [double]$r.X; y = [double]$r.Y; w = [double]$r.Width; h = [double]$r.Height; t = [string]$word.Text })
        }
    }
    return @{ page = $pageNo; width = [int]$bitmap.PixelWidth; height = [int]$bitmap.PixelHeight; words = @($words) }
}

function Save-Stream($stream, [string]$target) {
    $stream.Seek(0)
    $src = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
    $out = [System.IO.File]::Create($target)
    try { $src.CopyTo($out) } finally { $out.Dispose() }
}

try {
    $full = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { Emit @{ ok = $false; error = "The file was not found" }; exit 0 }
    $engine = New-OcrEngine
    if ($null -eq $engine) {
        Emit @{ ok = $false; error = "Windows text recognition needs an OCR language. Open Settings > Time & language > Language & region and add English (United States)." }
        exit 0
    }
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($full)) ([Windows.Storage.StorageFile])
    $ext = [System.IO.Path]::GetExtension($full).ToLowerInvariant()
    $results = New-Object System.Collections.ArrayList
    $previewDone = $false

    if ($ext -eq ".pdf") {
        try {
            $doc = Await ([Windows.Data.Pdf.PdfDocument]::LoadFromFileAsync($file)) ([Windows.Data.Pdf.PdfDocument])
        } catch {
            Emit @{ ok = $false; kind = "pdf"; error = "The PDF could not be opened (it may be damaged or password protected)" }
            exit 0
        }
        $count = [int]$doc.PageCount
        $wanted = @()
        if ($Pages) { $wanted = @($Pages.Split(",") | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ }) } else { $wanted = 1..$count }
        for ($i = 0; $i -lt $count; $i++) {
            $needOcr = $wanted -contains ($i + 1)
            $needPreview = ($i -eq 0 -and $Preview)
            if (-not $needOcr -and -not $needPreview) { continue }
            $page = $doc.GetPage([uint32]$i)
            try {
                if ($needPreview) {
                    $ps = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
                    $po = New-Object Windows.Data.Pdf.PdfPageRenderOptions
                    $po.DestinationWidth = [uint32]900
                    $po.BitmapEncoderId = [Windows.Graphics.Imaging.BitmapEncoder]::PngEncoderId
                    AwaitAction ($page.RenderToStreamAsync($ps, $po))
                    Save-Stream $ps $Preview
                    $previewDone = $true
                }
                if ($needOcr) {
                    $os = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
                    $oo = New-Object Windows.Data.Pdf.PdfPageRenderOptions
                    $oo.DestinationWidth = [uint32][Math]::Min(2400, [Math]::Max(1600, [int]($page.Size.Width * 3)))
                    $oo.BitmapEncoderId = [Windows.Graphics.Imaging.BitmapEncoder]::PngEncoderId
                    AwaitAction ($page.RenderToStreamAsync($os, $oo))
                    $os.Seek(0)
                    $null = $results.Add((Read-Stream $os $engine ($i + 1)))
                }
            } finally { $page.Dispose() }
        }
        Emit @{ ok = $true; kind = "pdf"; page_count = $count; preview = $previewDone; pages = @($results) }
    } else {
        $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        try {
            $null = $results.Add((Read-Stream $stream $engine 1))
            if ($Preview) {
                Add-Type -AssemblyName System.Drawing
                $img = [System.Drawing.Image]::FromFile($full)
                try {
                    $s = [Math]::Min(1.0, 900.0 / $img.Width)
                    $bmp = New-Object System.Drawing.Bitmap($img, [int]($img.Width * $s), [int]($img.Height * $s))
                    try { $bmp.Save($Preview, [System.Drawing.Imaging.ImageFormat]::Png); $previewDone = $true } finally { $bmp.Dispose() }
                } finally { $img.Dispose() }
            }
        } finally { $stream.Dispose() }
        Emit @{ ok = $true; kind = "image"; page_count = 1; preview = $previewDone; pages = @($results) }
    }
} catch {
    Emit @{ ok = $false; error = ("Windows text recognition failed: " + $_.Exception.Message) }
}
