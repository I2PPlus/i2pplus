<#
.SYNOPSIS
  Installs the service wrapper's own files into an I2P+ install.

.DESCRIPTION
  The wrapper cannot update itself. I2Psvc.exe is the process the router runs
  under, wrapper.dll is loaded by that process, and the JVM holds lib\wrapper.jar
  open - Windows locks all three for the whole life of the process. A restart
  from the console keeps the native wrapper alive and re-launches only the JVM,
  so an updater payload stages the new files and stops there. This script is the
  part that stops the wrapper, replaces the files, and starts it again.

  Nothing is written until every file it is about to replace has been copied
  aside, and a failed install puts them back and starts the wrapper again.

  Run it once the rest of an update is in place, from an elevated prompt when
  the router runs as a service.

.PARAMETER InstallDir
  I2P+ install to update. Defaults to the directory holding this script, when
  that is an install.

.PARAMETER Source
  Directory holding the new wrapper files, laid out as win64\ plus all\wrapper.jar.
  Defaults, in order: the build tree this script sits in, then lib\wrapper inside
  the install, which is where an updater payload stages them.

.PARAMETER DryRun
  Report what would be done; write nothing.

.PARAMETER NoRestart
  Stop, install, and leave the wrapper stopped.

.PARAMETER Start
  Start the wrapper afterwards even if it was not running. Otherwise it is
  started back only if it was running, and by the mechanism that was running it.

.EXAMPLE
  .\install-wrapper.ps1

.EXAMPLE
  .\install-wrapper.ps1 -DryRun

.NOTES
  A service install is stopped through the I2P+ service. A portable or console
  run is not a service, and is stopped by its own process.
#>
[CmdletBinding()]
param(
    [string]$InstallDir,
    [string]$Source,
    [switch]$DryRun,
    [switch]$NoRestart,
    [switch]$Start
)

$ErrorActionPreference = 'Stop'

function Say($msg)  { Write-Host $msg }
function Die($msg)  { Write-Host "install-wrapper.ps1: $msg" -ForegroundColor Red; exit 1 }

# Same check service.ps1 uses to decide whether to self-elevate.
function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Run([scriptblock]$what) {
    if ($DryRun) { Write-Host "DRY: $what"; return $null }
    & $what
}

# Which files Windows currently holds open, so a locked file is named before
# anything is replaced rather than part-way through.
function Test-Locked([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    try {
        $h = [System.IO.File]::Open($path, 'Open', 'ReadWrite', 'None')
        $h.Close()
        return $false
    } catch {
        return $true
    }
}

# ------------------------------------------------------------------ layout ---

$here = $PSScriptRoot
if (-not $InstallDir) {
    if (Test-Path -LiteralPath (Join-Path $here 'wrapper.config')) {
        $InstallDir = $here
    } else {
        Die "no wrapper.config in $here - name the install with -InstallDir"
    }
}
if (-not (Test-Path -LiteralPath $InstallDir -PathType Container)) {
    Die "no such install directory: $InstallDir"
}
$InstallDir = (Resolve-Path -LiteralPath $InstallDir).Path

$candidates = @()
if ($Source) { $candidates += $Source }
# From the build tree this script sits in (installer\resources\platform-specific\
# windows), the artifacts are three levels up under installer\lib\wrapper.
$candidates += (Join-Path $here '..\..\..\lib\wrapper')
$candidates += (Join-Path $InstallDir 'lib\wrapper')
foreach ($c in $candidates) {
    if (Test-Path -LiteralPath (Join-Path $c 'win64\I2Psvc.exe') -PathType Leaf) {
        $Source = (Resolve-Path -LiteralPath $c).Path
        break
    }
}
if (-not $Source) { Die "no wrapper files to install - name them with -Source" }

$srcExe  = Join-Path $Source 'win64\I2Psvc.exe'
$srcDll  = Join-Path $Source 'win64\wrapper.dll'
$srcJar  = Join-Path $Source 'all\wrapper.jar'
foreach ($f in @($srcExe, $srcDll, $srcJar)) {
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { Die "missing $f" }
}

# I2Psvc.exe and wrapper.dll sit together: the installer puts them in the install
# root, the zip package leaves them in lib\wrapper\win64. Replace whichever pair
# this install actually has.
$dstExe = Join-Path $InstallDir 'I2Psvc.exe'
$dstDll = Join-Path $InstallDir 'wrapper.dll'
if (-not (Test-Path -LiteralPath $dstExe)) {
    $staged = Join-Path $InstallDir 'lib\wrapper\win64\I2Psvc.exe'
    if (Test-Path -LiteralPath $staged) {
        $dstExe = $staged
        $dstDll = Join-Path $InstallDir 'lib\wrapper\win64\wrapper.dll'
    }
}
$dstJar = Join-Path $InstallDir 'lib\wrapper.jar'

$version = ''
foreach ($m in @((Join-Path $Source 'version.txt'), (Join-Path $Source 'win64\BUILT-FROM'))) {
    if (Test-Path -LiteralPath $m -PathType Leaf) {
        $version = (Get-Content -LiteralPath $m |
            ForEach-Object { ($_ -replace '^WRAPPER_VERSION=', '') } |
            Where-Object { $_ -match '^[0-9]+(\.[0-9]+)+$' } |
            Select-Object -First 1)
        if ($version) { break }
    }
}

Say '=== Wrapper update ==='
Say "Release:  $(if ($version) { $version } else { 'unknown' })"
Say "Platform: win64"
Say "Install:  $InstallDir"
Say "Source:   $Source"
Say ''
Say "  I2Psvc.exe  -> $dstExe"
Say "  wrapper.dll -> $dstDll"
Say "  wrapper.jar -> $dstJar"
Say ''

# ----------------------------------------------------------------- service ---

# Which mechanism controls this router is not the same question as which one is
# installed. The I2P+ service may be present while the router is running as a
# portable or console process instead, and stopping the service then changes
# nothing while the files stay locked. So the choice comes from what is actually
# running, and whatever stopped it is what starts it again.
$svcName = 'I2P+'
$svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue

# A second I2P+ install on the same machine runs its own I2Psvc.exe, so the
# process is matched to this install by the path it was started from.
function Get-OurWrapperProcess {
    Get-Process -Name I2Psvc -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($InstallDir, 'OrdinalIgnoreCase') }
}

$proc = Get-OurWrapperProcess
$stoppedBy = ''
if ($svc -and $svc.Status -eq 'Running') {
    $stoppedBy = 'service'
} elseif ($proc) {
    $stoppedBy = 'process'
}

if ($stoppedBy -eq 'service' -and -not (Test-IsAdmin)) {
    Die "the $svcName service is running and stopping it needs Administrator - rerun from an elevated prompt"
}

function Stop-Wrapper() {
    switch ($stoppedBy) {
        'service' {
            Say "Stopping the $svcName service..."
            Run { Stop-Service -Name $svcName -Force }
        }
        'process' {
            Say "Stopping the running I2Psvc.exe process..."
            Run { $proc | Stop-Process }
        }
    }
    if ($DryRun) { return }
    # The three files are held by the process that was just asked to die, and
    # Windows will not release them until it is gone, so wait for it rather than
    # for a delay.
    $waited = 0
    while ($true) {
        $still = $false
        if ($svc -and (Get-Service -Name $svcName -ErrorAction SilentlyContinue).Status -eq 'Running') {
            $still = $true
        }
        if (Get-OurWrapperProcess) { $still = $true }
        if (-not $still) { break }
        Start-Sleep -Seconds 1
        $waited++
        if ($waited -ge 60) { Die 'the wrapper is still running 60s after the stop request - refusing to copy over it' }
    }
    Say 'Stopped.'
}

function Start-Wrapper() {
    if ($NoRestart) { Say '-NoRestart: leaving the wrapper stopped.'; return }
    if (-not $stoppedBy -and -not $Start) {
        Say 'The wrapper was not running, so nothing was started. Start it as you normally do, or pass -Start.'
        return
    }
    $how = $stoppedBy
    if (-not $how) { $how = $(if ($svc) { 'service' } else { 'process' }) }
    if ($how -eq 'service') {
        Say "Starting the $svcName service..."
        Run { Start-Service -Name $svcName }
    } else {
        Say "The wrapper was not running as a service - start it with $dstExe or service.ps1 -Action Start."
    }
}

# ------------------------------------------------------------------ install ---

switch ($stoppedBy) {
    ''       { Say 'The wrapper is not running.' }
    'service' { Say "The wrapper is running as the $svcName service."; Stop-Wrapper }
    'process' { Say 'The wrapper is running as a console process.'; Stop-Wrapper }
}

if (-not $DryRun) {
    foreach ($f in @($dstExe, $dstDll, $dstJar)) {
        if (Test-Locked $f) { Die "$f is still held open - stop whatever is running it and try again" }
    }
}

$backup = Join-Path $InstallDir 'lib\wrapper-backup'
Run { Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue }
if (-not $DryRun) { New-Item -ItemType Directory -Path $backup -Force | Out-Null }
$saved = @()
foreach ($f in @($dstExe, $dstDll, $dstJar)) {
    if (Test-Path -LiteralPath $f -PathType Leaf) {
        Run { Copy-Item -LiteralPath $f -Destination $backup -Force }
        $saved += $f
    }
}

function Restore-Backup() {
    foreach ($f in $saved) {
        $b = Join-Path $backup (Split-Path -Leaf $f)
        if (Test-Path -LiteralPath $b -PathType Leaf) {
            Copy-Item -LiteralPath $b -Destination $f -Force
        }
    }
    Say 'Rolled back to the previous wrapper.'
}

$failed = @()
foreach ($pair in @(@($srcExe, $dstExe), @($srcDll, $dstDll), @($srcJar, $dstJar))) {
    $src = $pair[0]; $dst = $pair[1]
    Run { Copy-Item -LiteralPath $src -Destination $dst -Force }
    if (-not $DryRun) {
        if ((Get-FileHash -LiteralPath $src).Hash -ne (Get-FileHash -LiteralPath $dst).Hash) {
            $failed += (Split-Path -Leaf $dst)
        }
    }
}

if ($failed) {
    Say "Failed to install: $($failed -join ', ')"
    if (-not $DryRun) { Restore-Backup; Start-Wrapper }
    exit 1
}

# The staged copy has done its job, and leaving it behind means the next run
# cannot tell an installed release from one still waiting to be installed.
if ($Source -eq (Join-Path $InstallDir 'lib\wrapper')) {
    Run { Remove-Item -LiteralPath $Source -Recurse -Force }
}
Run { Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue }

Start-Wrapper

Say ''
Say "Wrapper updated$(if ($version) { " to $version" })."
