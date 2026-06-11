param(
    [string]$DeviceSerial = "",
    [string[]]$Locales = @(),
    [string]$TargetFolderName = "anotepad",
    [switch]$SkipBuild,
    [switch]$ReuseInstalledApp
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot
try {
    $env:FASTLANE_SKIP_UPDATE_CHECK = "1"

    function Resolve-AndroidSdkDir {
        foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
            if ($candidate -and (Test-Path -LiteralPath $candidate)) {
                return $candidate
            }
        }

        $localProperties = Join-Path $repoRoot "local.properties"
        if (Test-Path -LiteralPath $localProperties) {
            foreach ($line in Get-Content -LiteralPath $localProperties) {
                if ($line -match "^sdk\.dir=(.+)$") {
                    return ($Matches[1].Trim() -replace "\\:", ":" -replace "\\\\", "\")
                }
            }
        }

        return $null
    }

    function Resolve-AdbPath {
        $adbFromPath = Get-Command adb -ErrorAction SilentlyContinue
        if ($adbFromPath) {
            return $adbFromPath.Source
        }

        $sdkDir = Resolve-AndroidSdkDir
        if ($sdkDir) {
            $adbFromSdk = Join-Path $sdkDir "platform-tools\adb.exe"
            if (Test-Path -LiteralPath $adbFromSdk) {
                return $adbFromSdk
            }
        }

        throw "Unable to find adb. Add Android SDK platform-tools to PATH or set ANDROID_HOME / ANDROID_SDK_ROOT."
    }

    $adb = Resolve-AdbPath

    function Invoke-AdbBestEffort {
        param([string[]]$CommandArgs)

        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $adb @adbArgs @CommandArgs *> $null
        } catch {
            # Some Android builds do not support bmgr wipe, and uninstall returns
            # a non-zero status when the package is not installed.
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }

    $adbArgs = @()
    if ($DeviceSerial.Trim().Length -gt 0) {
        $env:ANDROID_SERIAL = $DeviceSerial
        $adbArgs += @("-s", $DeviceSerial)
    }

    if ($Locales.Count -gt 0) {
        $env:SCREENSHOT_LOCALES = ($Locales -join ",")
    }

    $env:SCREENSHOT_TARGET_FOLDER_NAME = $TargetFolderName
    $env:SCREENSHOT_REINSTALL_APP = if ($ReuseInstalledApp) { "0" } else { "1" }

    if ($ReuseInstalledApp) {
        # Keep the persisted SAF folder grant and DataStore root_tree_uri, but
        # restart the app so it reloads the locale-specific payload.
        Invoke-AdbBestEffort @("shell", "am", "force-stop", "com.anotepad")
    } else {
        # Prevent Android Auto Backup from restoring an obsolete DataStore root_tree_uri
        # without the matching persisted SAF permission.
        Invoke-AdbBestEffort @("shell", "bmgr", "wipe", "com.anotepad")
        Invoke-AdbBestEffort @("uninstall", "com.anotepad")
        Invoke-AdbBestEffort @("uninstall", "com.anotepad.test")
    }

    # The UI test selects this folder through SAF. It does not create the folder itself.
    & $adb @adbArgs shell mkdir -p "/sdcard/$TargetFolderName" | Out-Null

    if ($SkipBuild) {
        bundle exec fastlane android screenshots_capture_only
    } else {
        bundle exec fastlane android screenshots
    }
}
finally {
    Pop-Location
}
