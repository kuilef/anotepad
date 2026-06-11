param(
    [string]$DeviceSerial = "",
    [string[]]$Locales = @("all"),
    [string]$TargetFolderName = "anotepad",
    [string]$ZipPath = "",
    [string]$AdbPath = "",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr"
)

$ErrorActionPreference = "Stop"
$env:LC_ALL = "en_US.UTF-8"
$env:LANG = "en_US.UTF-8"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($ZipPath)) {
    $ZipPath = Join-Path $PSScriptRoot "anotepad_translations_all_with_share.zip"
}

if (!(Test-Path -LiteralPath $ZipPath)) {
    throw "Translation archive was not found: $ZipPath"
}

if ([string]::IsNullOrWhiteSpace($TargetFolderName) -or $TargetFolderName.Contains("/") -or $TargetFolderName.Contains("\")) {
    throw "TargetFolderName must be a single folder name, got: $TargetFolderName"
}

if ($JavaHome -and (Test-Path -LiteralPath $JavaHome)) {
    $env:JAVA_HOME = $JavaHome
}

if (!$env:ANDROID_HOME) {
    $env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
}
if (!$env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}

function Resolve-AdbPath {
    if ($AdbPath -and (Test-Path -LiteralPath $AdbPath)) {
        return $AdbPath
    }

    $adbFromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbFromPath) {
        return $adbFromPath.Source
    }

    foreach ($sdkDir in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdkDir) {
            $candidate = Join-Path $sdkDir "platform-tools\adb.exe"
            if (Test-Path -LiteralPath $candidate) {
                return $candidate
            }
        }
    }

    throw "Unable to find adb. Pass -AdbPath or set ANDROID_HOME / ANDROID_SDK_ROOT."
}

$localeToZipDir = [ordered]@{
    "bn-BD" = "values-bn"
    "de-DE" = "values-de"
    "en-US" = "values-en"
    "es-ES" = "values-es"
    "fr-FR" = "values-fr"
    "hi-IN" = "values-hi"
    "id-ID" = "values-in"
    "pt-BR" = "values-pt-rBR"
    "ru-RU" = "values-ru"
    "tr-TR" = "values-tr"
    "vi-VN" = "values-vi"
}

$normalizedLocales = @(
    $Locales |
        ForEach-Object { $_ -split ',' } |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }
)

if ($normalizedLocales.Count -eq 1 -and $normalizedLocales[0].Equals("all", [System.StringComparison]::OrdinalIgnoreCase)) {
    $selectedLocales = @($localeToZipDir.Keys)
} else {
    $selectedLocales = @($normalizedLocales)
}

foreach ($locale in $selectedLocales) {
    if (!$localeToZipDir.Contains($locale)) {
        throw "Locale '$locale' is not mapped to a folder in $ZipPath. Known locales: $($localeToZipDir.Keys -join ', ')"
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$runId = Get-Date -Format "yyyyMMdd_HHmmss"
$stagingRoot = Join-Path (Join-Path $repoRoot "build") "screenshot-payloads_$runId"
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

$adb = Resolve-AdbPath
$adbArgs = @()
if ($DeviceSerial.Trim().Length -gt 0) {
    $adbArgs += @("-s", $DeviceSerial)
}

$expectedScreenshots = @(
    "01_home_files.png",
    "02_feed.png",
    "03_about_anotepad.png",
    "04_settings.png"
)

function New-LocalePayload {
    param(
        [string]$Locale,
        [string]$ZipDir
    )

    $payloadDir = Join-Path (Join-Path $stagingRoot $Locale) $TargetFolderName
    New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $payloadDir "Shared") | Out-Null

    $zip = [IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $prefix = "$ZipDir/"
        $entries = @($zip.Entries | Where-Object { $_.FullName.StartsWith($prefix) -and $_.Name })
        if ($entries.Count -eq 0) {
            throw "No files found under '$ZipDir/' in $ZipPath"
        }

        foreach ($entry in $entries) {
            $destination = Join-Path $payloadDir $entry.Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
        }
    } finally {
        $zip.Dispose()
    }

    return $payloadDir
}

Push-Location $repoRoot
try {
    for ($localeIndex = 0; $localeIndex -lt $selectedLocales.Count; $localeIndex++) {
        $locale = $selectedLocales[$localeIndex]
        $zipDir = $localeToZipDir[$locale]
        Write-Host "Preparing $locale from $zipDir"
        $payloadDir = New-LocalePayload -Locale $locale -ZipDir $zipDir

        Write-Host "Resetting /sdcard/$TargetFolderName and pushing payload"
        & $adb @adbArgs shell "rm -rf /sdcard/$TargetFolderName/*; mkdir -p /sdcard/$TargetFolderName/Shared"
        & $adb @adbArgs push "$payloadDir\." "/sdcard/$TargetFolderName/"

        $screenshotArgs = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            ".\tools\generate_localized_screenshots.ps1",
            "-DeviceSerial",
            $DeviceSerial,
            "-Locales",
            $locale,
            "-TargetFolderName",
            $TargetFolderName
        )
        if ($localeIndex -gt 0) {
            $screenshotArgs += "-SkipBuild"
        }

        if ($localeIndex -eq 0) {
            Write-Host "Running screenshots for $locale with a fresh debug APK build"
        } else {
            Write-Host "Running screenshots for $locale using already built debug APKs"
        }
        & powershell @screenshotArgs

        $screenshotDir = Join-Path $repoRoot "metadata\$locale\images\phoneScreenshots"
        foreach ($name in $expectedScreenshots) {
            $path = Join-Path $screenshotDir $name
            if (!(Test-Path -LiteralPath $path)) {
                throw "Missing expected screenshot after $locale run: $path"
            }
        }
        Write-Host "Completed $locale -> $screenshotDir"
    }
} finally {
    Pop-Location
}
