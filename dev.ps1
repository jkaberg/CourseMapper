#Requires -Version 5.1
<#
.SYNOPSIS
    CourseMapper developer script - build, deploy, and debug helpers.

.USAGE
    .\dev.ps1 <command> [options]

.COMMANDS
    connect   Connect ADB to the device
    build     Build the debug APK
    install   Install the debug APK on the device
    deploy    connect + build + install (full pipeline)
    clean     Clean build artifacts
    logs      Stream logcat filtered to com.coursemapper
    help      Show this help message
#>

param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Arguments,

    # Override the default device if needed
    [string]$Device = "10.0.30.188.41047",

    # Use a USB-connected device instead of the default TCP/IP device
    [switch]$Usb,

    # Accept GNU-style flags such as --usb on Windows PowerShell.
    [string[]]$RemainingArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$AppId   = "com.coursemapper"
$ApkPath = "app\build\outputs\apk\debug\app-debug.apk"
$UsbDevice = $null

if (@($Arguments).Count -gt 0) {
    $Command = $Arguments[0]
    $RemainingArguments += @($Arguments | Select-Object -Skip 1)
} else {
    $Command = "help"
}

if ($RemainingArguments) {
    foreach ($argument in $RemainingArguments) {
        if ($argument -ieq '--usb') {
            $Usb = $true
            continue
        }
        throw "Unknown option: $argument"
    }
}

# ---------------------------------------------------------------------------
# ADB auto-detection
# ---------------------------------------------------------------------------
function Find-Adb {
    # 1. System PATH
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    # Candidate SDK roots
    $roots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk"
    )

    foreach ($root in $roots) {
        if ([string]::IsNullOrEmpty($root)) { continue }
        $candidate = Join-Path $root "platform-tools\adb.exe"
        if (Test-Path $candidate) { return $candidate }
    }

    Write-Error @"
adb.exe not found. Install the Android SDK platform-tools and either:
  - Add platform-tools to your PATH, or
  - Set the ANDROID_HOME or ANDROID_SDK_ROOT environment variable.
"@
}

$adb = Find-Adb
Write-Host "Using adb: $adb" -ForegroundColor DarkGray

# Return the single authorized USB-connected device.
function Get-UsbDevice {
    $devices = @(& $adb devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$' -and $Matches[1] -notmatch ':') {
            $Matches[1]
        }
    })

    if ($devices.Count -eq 0) {
        throw "No authorized USB device found. Connect the device, enable USB debugging, and accept the RSA prompt."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple USB devices found: $($devices -join ', '). Disconnect extras or use -Device <serial>."
    }

    return $devices[0]
}

if ($Usb) {
    $UsbDevice = if ($PSBoundParameters.ContainsKey('Device')) { $Device } else { Get-UsbDevice }
    $Device = $UsbDevice
    Write-Host "Using USB device: $Device" -ForegroundColor DarkGray
}

# Run adb targeting the selected device.
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Args)
    & $adb -s $Device @Args
    if ($LASTEXITCODE -ne 0) { throw "adb exited with code $LASTEXITCODE" }
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

function Cmd-Connect {
    if ($Usb) {
        Write-Host "Using USB device $Device." -ForegroundColor Green
        return
    }

    Write-Host "Connecting to $Device ..." -ForegroundColor Cyan
    $result = & $adb connect $Device 2>&1
    Write-Host $result

    if ($result -notmatch "connected to|already connected") {
        throw "Failed to connect to $Device. Is the device reachable?"
    }
    Write-Host "Connected." -ForegroundColor Green
}

function Cmd-Build {
    Write-Host "Building debug APK ..." -ForegroundColor Cyan
    & ".\gradlew.bat" assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }
    Write-Host "Build successful: $ApkPath" -ForegroundColor Green
}

function Cmd-Install {
    Cmd-Connect
    if (-not (Test-Path $ApkPath)) {
        throw "APK not found at '$ApkPath'. Run '.\dev.ps1 build' first."
    }
    Write-Host "Installing $ApkPath ..." -ForegroundColor Cyan
    Invoke-Adb install -r $ApkPath
    Write-Host "Installed successfully." -ForegroundColor Green
}

function Cmd-Deploy {
    Cmd-Connect
    Cmd-Build
    Cmd-Install
    Write-Host "Deployed successfully." -ForegroundColor Green
}

function Cmd-Clean {
    Write-Host "Cleaning build artifacts ..." -ForegroundColor Cyan
    & ".\gradlew.bat" clean
    if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed (exit $LASTEXITCODE)." }
    Write-Host "Clean complete." -ForegroundColor Green
}

function Cmd-Logs {
    Cmd-Connect
    Write-Host "Streaming logcat for $AppId (Ctrl+C to stop) ..." -ForegroundColor Cyan

    # Get PID; fall back to full logcat if app is not running
    $appPid = & $adb -s $Device shell pidof $AppId 2>$null
    if ($appPid) {
        & $adb -s $Device logcat --pid=$($appPid.Trim())
    } else {
        Write-Host "App not running - showing all logs (launch the app first for filtered output)." -ForegroundColor Yellow
        & $adb -s $Device logcat
    }
}

function Cmd-Help {
    Write-Host ""
    Write-Host "CourseMapper dev script" -ForegroundColor Cyan
    Write-Host "  Device : $Device"
    Write-Host "  AppId  : $AppId"
    Write-Host ""
    Write-Host "Usage: .\dev.ps1 [command] [-Device host:port] [-Usb|--usb]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  connect   Connect ADB to the device (TCP/IP by default; USB with -Usb or --usb)"
    Write-Host "  build     Build the debug APK with Gradle"
    Write-Host "  install   Install the debug APK on the device"
    Write-Host "  deploy    connect + build + install (full pipeline)"
    Write-Host "  clean     Clean Gradle build artifacts"
    Write-Host "  logs      Stream logcat filtered to $AppId"
    Write-Host "  help      Show this message"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\dev.ps1 deploy                 # deploy over TCP/IP"
    Write-Host "  .\dev.ps1 deploy --usb           # deploy to the connected USB device"
    Write-Host "  .\dev.ps1 logs -Usb              # stream logs from the USB device"
    Write-Host ""
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------
switch ($Command.ToLower()) {
    "connect" { Cmd-Connect }
    "build"   { Cmd-Build   }
    "install" { Cmd-Install  }
    "deploy"  { Cmd-Deploy   }
    "clean"   { Cmd-Clean    }
    "logs"    { Cmd-Logs     }
    "help"    { Cmd-Help     }
    default {
        Write-Warning "Unknown command: $Command"
        Cmd-Help
        exit 1
    }
}
