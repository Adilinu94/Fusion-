param(
    [double]$FontScale = 2.0,
    [switch]$DumpOnly,
    [switch]$Restore
)

$ErrorActionPreference = "Stop"
$package = "com.dropsync.app.debug"
$activity = "com.dropsync.app.MainActivity"
$outputDirectory = Join-Path $PSScriptRoot "..\build\accessibility"
$scaleFile = Join-Path $outputDirectory "original-font-scale.txt"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb wurde nicht gefunden. Android Platform Tools zum PATH hinzufuegen."
}

$devices = @(adb devices | Select-String "\tdevice$")
if ($devices.Count -ne 1) {
    throw "Genau ein entsperrtes Android-Geraet verbinden (gefunden: $($devices.Count))."
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

if ($Restore) {
    if (-not (Test-Path $scaleFile)) {
        throw "Keine gespeicherte Font-Skalierung unter $scaleFile."
    }
    $originalScale = (Get-Content $scaleFile -Raw).Trim()
    adb shell settings put system font_scale $originalScale
    adb shell am force-stop $package
    adb shell am start -n "$package/$activity" | Out-Null
    Write-Host "Font-Skalierung auf $originalScale wiederhergestellt."
    exit 0
}

if (-not $DumpOnly) {
    $originalScale = (adb shell settings get system font_scale).Trim()
    if (-not (Test-Path $scaleFile)) {
        Set-Content -Path $scaleFile -Value $originalScale -NoNewline
    }
    adb shell settings put system font_scale $FontScale
    adb shell am force-stop $package
    adb shell am start -n "$package/$activity" | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "App mit font_scale=$FontScale gestartet. TalkBack jetzt manuell aktivieren."
}

$remoteDump = "/sdcard/flowrep-accessibility-window.xml"
adb shell uiautomator dump $remoteDump | Out-Null
adb pull $remoteDump (Join-Path $outputDirectory "window.xml") | Out-Null
adb shell rm $remoteDump
Write-Host "UI-Hierarchie: $(Join-Path $outputDirectory 'window.xml')"
