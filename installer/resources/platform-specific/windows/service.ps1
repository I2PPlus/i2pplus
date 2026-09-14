<#
.SYNOPSIS
  Manages the I2P+ Windows service.

.DESCRIPTION
  Installs, uninstalls, starts, stops, enables or disables the I2P+
  service through the bundled Tanuki wrapper (I2Psvc.exe), creates
  Start Menu / Desktop shortcuts, or repairs filesystem permissions.

  All actions except 'Shortcuts' require Administrator rights; the
  script self-elevates when run from a non-elevated session. Every
  operation is logged to install.log next to this script.

.PARAMETER Action
  The operation to perform. One of:
    Install    - install or upgrade the service
    Uninstall  - remove the service and strip the config override
    Start      - start the service (no-op if already running)
    Stop       - stop the service (no-op if already stopped)
    Enable     - set the service to auto-start
    Disable    - set the service to manual start
    Shortcuts  - (re)create Start Menu and Desktop shortcuts
    FixPerms   - grant the current user full control over -Target

.PARAMETER Target
  Directory to grant permissions to. Used by the FixPerms action only.
  Defaults to the directory containing this script.

.EXAMPLE
  .\service.ps1 -Action Install

.EXAMPLE
  .\service.ps1 -Action FixPerms -Target 'C:\Program Files\I2P+'

.NOTES
  Uses the Tanuki wrapper command-line interface (I2Psvc.exe).
  Wrapper streams are redirected with *> (never piped) because a pipe
  makes I2Psvc.exe crash with 0xC0000005.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Uninstall', 'Start', 'Stop', 'Enable', 'Disable', 'Shortcuts', 'FixPerms')]
    [string]$Action,
    [string]$Target
)

$ErrorActionPreference = 'Stop'
$svcName = 'I2P+'
$dir = $PSScriptRoot
$svc = Join-Path $dir 'I2Psvc.exe'
$conf = Join-Path $dir 'wrapper.config'
$log = Join-Path $dir 'install.log'

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-Admin {
    if (-not (Test-IsAdmin)) {
        throw "Administrator privileges are required for -Action $Action."
    }
}

# Self-elevate when required. All actions except Shortcuts modify the
# system service store or ACLs, so they need an elevated process.
$needsAdmin = $Action -ne 'Shortcuts'
if ($needsAdmin -and -not (Test-IsAdmin)) {
    # Pin to the legacy Windows PowerShell host: all callers (izpack,
    # ConfigServiceHandler, shortcuts) launch this script with
    # powershell.exe, and $PSHOME points somewhere without powershell.exe
    # when the script is run under PowerShell 7.
    $psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $elevArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Action $Action"
    if ($Target) { $elevArgs += " -Target `"$Target`"" }
    if ($VerbosePreference -eq 'Continue') { $elevArgs += ' -Verbose' }
    try {
        $p = Start-Process -FilePath $psExe -Verb RunAs -ArgumentList $elevArgs -Wait -PassThru
        exit $p.ExitCode
    } catch {
        Write-Host "Elevation was declined or failed: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

function Write-Log {
    param([string]$msg)
    $ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $line = "[$ts] $msg"
    Add-Content -LiteralPath $log -Value $line -ErrorAction SilentlyContinue
    Write-Verbose $msg
}

function Invoke-Wrapper {
    # Redirect all streams to nothing without creating a pipeline.
    # The old 2>&1 | Out-Null connected the process handles to a pipe,
    # which the Tanuki wrapper's SCM calls do not expect, causing
    # I2Psvc.exe to crash with 0xC0000005 (STATUS_ACCESS_VIOLATION).
    & $svc @args *>$null
}

function New-Shortcut {
    param($shell, $path, $target, $arguments, $workingDir, $icon, $iconIndex)
    if (-not (Test-Path -LiteralPath $target)) {
        Write-Log "WARNING: skipping shortcut '$path' (target not found: $target)"
        return
    }
    if ($icon -and -not (Test-Path -LiteralPath $icon)) {
        Write-Log "WARNING: icon not found, creating '$path' without one: $icon"
        $icon = $null
    }
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $target
    if ($arguments) { $shortcut.Arguments = $arguments }
    $shortcut.WorkingDirectory = $workingDir
    if ($icon) { $shortcut.IconLocation = "$icon,$iconIndex" }
    $shortcut.Save()
}

function Backup-Config {
    # Copy before any in-place edit so a botched rewrite can be reverted.
    Copy-Item -LiteralPath $conf -Destination "$conf.bak" -Force
    Write-Log "Backed up $conf to $conf.bak"
}

function Clear-ReadOnly {
    if ((Get-Item -LiteralPath $conf).IsReadOnly) {
        Set-ItemProperty -LiteralPath $conf -Name IsReadOnly -Value $false
        Write-Log "Cleared read-only attribute on $conf"
    }
}

function Get-I2PService {
    return Get-Service -Name $svcName -ErrorAction SilentlyContinue
}

function Get-ServiceState {
    # Returns 'running'/'stopped' or throws if the service is absent.
    $s = Get-I2PService
    if (-not $s) { throw "Service '$svcName' is not installed. Run -Action Install first." }
    return $s.Status
}

Write-Log "=== service.ps1 -Action $Action ==="

switch ($Action) {
    'Install' {
        Assert-Admin
        if (-not (Test-Path -LiteralPath $svc)) { throw "I2Psvc.exe not found at $svc" }
        if (-not (Test-Path -LiteralPath $conf)) { throw "wrapper.config not found at $conf" }

        # Ensure the config points at %PROGRAMDATA%\i2p for the service
        if (-not (Select-String -LiteralPath $conf -Pattern '^wrapper\.java\.additional\.5=' -Quiet)) {
            Backup-Config
            Clear-ReadOnly
            Add-Content -LiteralPath $conf -Value 'wrapper.java.additional.5=-Di2p.dir.config=%PROGRAMDATA%/i2p'
            Write-Log "Added wrapper.java.additional.5 to $conf"
        }
        # Stop and remove any existing service (upgrade / reinstall)
        # -qs exits nonzero (bit 0 = installed) when a service exists
        Write-Log "Querying service status ($svc -qs $conf)"
        Invoke-Wrapper "-qs", $conf
        Write-Log "Exit code: $LASTEXITCODE"
        if ($LASTEXITCODE -ne 0) {
            Write-Log "Removing existing service ($svc -r $conf)"
            Invoke-Wrapper "-r", $conf
            Write-Log "Exit code: $LASTEXITCODE"
            if ($LASTEXITCODE -ne 0) { throw "I2Psvc.exe -r failed with exit code $LASTEXITCODE" }
        }
        # Install
        Write-Log "Installing service ($svc -i $conf)"
        Invoke-Wrapper "-i", $conf
        Write-Log "Exit code: $LASTEXITCODE"
        if ($LASTEXITCODE -ne 0) { throw "I2Psvc.exe -i failed with exit code $LASTEXITCODE" }
        Write-Host "I2P+ service installed." -ForegroundColor Green
    }
    'Uninstall' {
        Assert-Admin
        if (-not (Test-Path -LiteralPath $svc)) { throw "I2Psvc.exe not found at $svc" }
        # Strip the config-dir line so a future install starts clean
        if (Test-Path -LiteralPath $conf) {
            Backup-Config
            Clear-ReadOnly
            $lines = Get-Content -LiteralPath $conf | Where-Object { $_ -notmatch '^wrapper\.java\.additional\.5=' }
            Set-Content -LiteralPath $conf -Value $lines
            Write-Log "Removed wrapper.java.additional.5 from $conf"
        }
        # -qs exits 0 when no service is installed; nothing to do
        Invoke-Wrapper "-qs", $conf
        if ($LASTEXITCODE -eq 0) {
            Write-Log "No service installed; nothing to uninstall"
            exit 0
        }
        Invoke-Wrapper "-r", $conf
        if ($LASTEXITCODE -ne 0) { throw "I2Psvc.exe -r failed with exit code $LASTEXITCODE" }
        Write-Host "I2P+ service uninstalled." -ForegroundColor Green
    }
    'Start' {
        Assert-Admin
        $state = Get-ServiceState
        if ($state -eq 'Running') { Write-Host "Service '$svcName' is already running."; exit 0 }
        net start $svcName
        if ($LASTEXITCODE -ne 0) { throw "net start failed with exit code $LASTEXITCODE" }
        Write-Host "Service '$svcName' started." -ForegroundColor Green
    }
    'Stop' {
        Assert-Admin
        $state = Get-ServiceState
        if ($state -eq 'Stopped' -or $state -eq 'StopPending') { Write-Host "Service '$svcName' is already stopped."; exit 0 }
        net stop $svcName
        if ($LASTEXITCODE -ne 0) { throw "net stop failed with exit code $LASTEXITCODE" }
        Write-Host "Service '$svcName' stopped." -ForegroundColor Green
    }
    'Enable' {
        Assert-Admin
        Get-ServiceState | Out-Null
        & "$env:SystemRoot\System32\sc.exe" config $svcName start= auto
        if ($LASTEXITCODE -ne 0) { throw "sc.exe config failed with exit code $LASTEXITCODE" }
        Write-Host "Service '$svcName' set to start automatically." -ForegroundColor Green
    }
    'Disable' {
        Assert-Admin
        Get-ServiceState | Out-Null
        & "$env:SystemRoot\System32\sc.exe" config $svcName start= demand
        if ($LASTEXITCODE -ne 0) { throw "sc.exe config failed with exit code $LASTEXITCODE" }
        Write-Host "Service '$svcName' set to manual start." -ForegroundColor Green
    }
    'Shortcuts' {
        $shell = $null
        try {
            $shell = New-Object -ComObject WScript.Shell
            $group = Join-Path ([Environment]::GetFolderPath('Programs')) 'I2P+'
            $desktop = [Environment]::GetFolderPath('Desktop')
            New-Item -ItemType Directory -Force -Path $group | Out-Null

            $psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
            $psArgs = '-NoProfile -ExecutionPolicy Bypass -File'
            $svcScript = Join-Path $dir 'service.ps1'

            New-Shortcut $shell (Join-Path $group 'Open I2P+ Profile Folder.lnk') "$env:SystemRoot\System32\explorer.exe" '"%programdata%\i2p"' $dir "$env:SystemRoot\System32\shell32.dll" 3
            New-Shortcut $shell (Join-Path $group 'I2P+ Router Console.lnk') (Join-Path $dir 'docs\startconsole.html') '' $dir (Join-Path $dir 'docs\console.ico') 0
            New-Shortcut $shell (Join-Path $group 'Start I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Start" $dir (Join-Path $dir 'docs\start.ico') 0
            New-Shortcut $shell (Join-Path $group 'Stop I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Stop" $dir (Join-Path $dir 'docs\stop.ico') 0
            New-Shortcut $shell (Join-Path $desktop 'I2P+ Router Console.lnk') (Join-Path $dir 'docs\startconsole.html') '' $dir (Join-Path $dir 'docs\console.ico') 0
            New-Shortcut $shell (Join-Path $desktop 'Start I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Start" $dir (Join-Path $dir 'docs\start.ico') 0
            New-Shortcut $shell (Join-Path $desktop 'Stop I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Stop" $dir (Join-Path $dir 'docs\stop.ico') 0
            Write-Host "Shortcuts created." -ForegroundColor Green
        } finally {
            if ($shell -ne $null) {
                [System.Runtime.InteropServices.Marshal]::ReleaseComObject($shell) | Out-Null
            }
        }
    }
    'FixPerms' {
        Assert-Admin
        if (-not $Target) { $Target = $PSScriptRoot }
        if (-not (Test-Path -LiteralPath $Target)) { throw "Target directory not found: $Target" }
        $logPath = Join-Path $dir 'fixperms.log'
        $account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        # F=full control (required so the service and user can read/write
        # the profile and logs), /c=continue /q=quiet /t=recursive.
        # 'Y' answers icacls' confirmation prompt when $Target contains
        # wildcards; harmless otherwise. *> captures all output to the log
        # without wiring a pipe.
        # ${account} (not $account:) — '$account:F' would be parsed as a
        # scope-qualified variable (drive 'account', var 'F') and expand
        # to empty, making icacls fail with 'Invalid parameter /grant'.
        'Y' | & icacls $Target /grant "${account}:F" /c /t /q *>$logPath
        if ($LASTEXITCODE -ne 0) { throw "icacls failed with exit code $LASTEXITCODE (see $logPath)" }
        Write-Host "Granted full control over $Target to $account." -ForegroundColor Green
    }
}
