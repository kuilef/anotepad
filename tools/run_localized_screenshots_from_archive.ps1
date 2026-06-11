param(
    [string]$DeviceSerial = "",
    [string[]]$Locales = @("all"),
    [string]$TargetFolderName = "anotepad",
    [string]$ZipPath = "",
    [string]$AdbPath = "",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr",
    [switch]$ResetAppEachLocale,
    [switch]$ReuseInstalledAppFromFirstLocale,
    [int]$InstrumentationSettleSeconds = 3,
    [int]$InstrumentationMaxAttempts = 3
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
    "en-US" = "values-en"
    "bn-BD" = "values-bn"
    "de-DE" = "values-de"
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

function Invoke-AdbChecked {
    param(
        [string[]]$CommandArgs
    )

    & $adb @adbArgs @CommandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed with exit code ${LASTEXITCODE}: adb $($CommandArgs -join ' ')"
    }
}

function Invoke-AdbBestEffort {
    param(
        [string[]]$CommandArgs
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb @adbArgs @CommandArgs *> $null
    } catch {
        # Process cleanup is best effort; the following instrumentation run is authoritative.
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Get-DeviceApiVersion {
    $apiText = (& $adb @adbArgs shell getprop ro.build.version.sdk).Trim()
    $apiVersion = 0
    if ([int]::TryParse($apiText, [ref]$apiVersion)) {
        return $apiVersion
    }

    return 0
}

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

function Clear-ExpectedLocalScreenshots {
    param(
        [string]$Locale
    )

    $screenshotDir = Join-Path $repoRoot "metadata\$Locale\images\phoneScreenshots"
    New-Item -ItemType Directory -Force -Path $screenshotDir | Out-Null

    foreach ($name in $expectedScreenshots) {
        $path = Join-Path $screenshotDir $name
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

function Test-IsRecoverableUiAutomationFailure {
    param(
        [object[]]$Output
    )

    $text = ($Output | Out-String)
    return $text -match "UiAutomation not connected" -or
        $text -match "Error while connecting UiAutomation"
}

function Test-IsInstrumentationFailure {
    param(
        [object[]]$Output
    )

    $text = ($Output | Out-String)
    return $text -match "FAILURES!!!" -or
        $text -match "Error in captureLocalizedStoreScreenshots" -or
        $text -match "Process crashed" -or
        $text -match "INSTRUMENTATION_RESULT: shortMsg="
}

function Invoke-ReusedInstallScreenshotRun {
    param(
        [string]$Locale,
        [int]$DeviceApiVersion
    )

    Clear-ExpectedLocalScreenshots -Locale $Locale

    $deviceScreenshotDir = "/sdcard/Android/data/com.anotepad/files/screengrab/$Locale/images/screenshots"

    $instrumentArgs = @(
        "shell",
        "am",
        "instrument",
        "--no-window-animation",
        "-w",
        "-e",
        "testLocale",
        $Locale
    )
    if ($DeviceApiVersion -ge 28) {
        $instrumentArgs += "--no-hidden-api-checks"
    }
    $instrumentArgs += @(
        "-e",
        "appendTimestamp",
        "false",
        "-e",
        "class",
        "com.anotepad.storecaptures.PlayStoreScreenshotTest",
        "-e",
        "targetFolderName",
        $TargetFolderName,
        "com.anotepad.test/androidx.test.runner.AndroidJUnitRunner"
    )

    for ($attempt = 1; $attempt -le $InstrumentationMaxAttempts; $attempt++) {
        if ($InstrumentationMaxAttempts -gt 1) {
            Write-Host "Instrumentation attempt $attempt/$InstrumentationMaxAttempts for $Locale"
        }

        Invoke-AdbBestEffort @("shell", "am", "force-stop", "com.anotepad.test")
        Invoke-AdbBestEffort @("shell", "am", "force-stop", "com.anotepad")
        if ($InstrumentationSettleSeconds -gt 0) {
            Start-Sleep -Seconds ($InstrumentationSettleSeconds * $attempt)
        }

        Invoke-AdbChecked @("shell", "rm", "-rf", $deviceScreenshotDir)
        Invoke-AdbChecked @("shell", "mkdir", "-p", $deviceScreenshotDir)

        $testOutput = & $adb @adbArgs @instrumentArgs 2>&1
        $testExitCode = $LASTEXITCODE
        $testOutput | ForEach-Object { Write-Host $_ }

        $hasInstrumentationFailure = Test-IsInstrumentationFailure -Output $testOutput
        if ($testExitCode -eq 0 -and !$hasInstrumentationFailure) {
            $screenshotDir = Join-Path $repoRoot "metadata\$Locale\images\phoneScreenshots"
            Invoke-AdbChecked @("pull", "$deviceScreenshotDir/.", $screenshotDir)
            return
        }

        if ($attempt -lt $InstrumentationMaxAttempts -and (Test-IsRecoverableUiAutomationFailure -Output $testOutput)) {
            Write-Host "Recoverable UiAutomation failure for $Locale; retrying after process cleanup"
            continue
        }

        throw "Instrumentation failed for $Locale."
    }
}

Push-Location $repoRoot
try {
    $deviceApiVersion = Get-DeviceApiVersion
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

        $shouldReuseInstalledApp = !$ResetAppEachLocale -and ($localeIndex -gt 0 -or $ReuseInstalledAppFromFirstLocale)

        if (!$shouldReuseInstalledApp -and $localeIndex -eq 0) {
            Write-Host "Running screenshots for $locale with a fresh debug APK build"
            & powershell @screenshotArgs
        } elseif ($ResetAppEachLocale) {
            Write-Host "Running screenshots for $locale using already built debug APKs with a fresh app install"
            & powershell @screenshotArgs
        } else {
            Write-Host "Running screenshots for $locale by reusing the existing app install and folder permission"
            Invoke-ReusedInstallScreenshotRun -Locale $locale -DeviceApiVersion $deviceApiVersion
        }

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
