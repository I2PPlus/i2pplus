<#
.SYNOPSIS
  Runs the I2P+ router in this console window, without the Windows service.

.DESCRIPTION
  Starts the router through the bundled Tanuki wrapper (I2Psvc.exe -c
  wrapper.config), so the wrapper still watches the JVM and restarts it if it
  dies, but nothing is registered with the Service Control Manager and no
  Administrator rights are needed.

  The router's configuration folder (its profile: router.config, netDb,
  peerProfiles, eepsites, ...) is chosen once and remembered in portable.config
  next to this script, so a later run of ".\portable.ps1" with no arguments
  starts the router on the same profile.

  This is what a portable installation needs: the whole I2P+ directory, plus a
  "configuration" folder inside it, can sit on a USB stick and travel between
  machines. Nothing is written to %PROGRAMDATA% or %USERPROFILE%.

.PARAMETER ConfigDir
  Use this folder as the configuration folder. It is created if it does not
  exist, and remembered for later runs.

.PARAMETER Portable
  Use "<install directory>\configuration" as the configuration folder, and
  remember it. This is the default when nothing has been remembered yet.

.PARAMETER Forget
  Drop the remembered configuration folder and exit. The next run starts the
  choice over.

.PARAMETER ShowProfile
  Open the configuration folder in Explorer and exit without starting the
  router: the folder an earlier run remembered, or "<install>\configuration"
  if none was. Nothing is remembered or rewritten, so asking where the profile
  is cannot change where it is.

.EXAMPLE
  .\portable.ps1
  First run: creates "<install>\configuration" and starts the router on it.
  Later runs: start the router on whatever was chosen before.

.EXAMPLE
  .\portable.ps1 -ShowProfile
  Open the folder the router keeps its profile in, without starting it.

.EXAMPLE
  .\portable.ps1 -Portable
  Force the profile into "<install>\configuration", even if a different folder
  was remembered.

.EXAMPLE
  .\portable.ps1 -ConfigDir 'E:\i2p-profile'
  Keep the profile on another drive.

.NOTES
  Stop the router with Ctrl+C (or close the window): the wrapper shuts the JVM
  down gracefully. wrapper.config decides what happens to a JVM that exits on
  its own (wrapper.on_exit.*), so a crash is restarted and a clean exit is not.
  The wrapper's own log is wrapper.log in the install directory; the router's
  logs are in the "logs" folder there.

  If wrapper.config still contains the $INSTALL_PATH placeholders (a zip
  package, not an installed one), the script writes wrapper.portable.config
  next to itself with those placeholders set to this directory, and runs the
  wrapper from that copy. It is rewritten on every run and safe to delete.
#>
[CmdletBinding()]
param(
    [string]$ConfigDir,
    [switch]$Portable,
    [switch]$Forget,
    [switch]$ShowProfile
)

$ErrorActionPreference = 'Stop'

# Everything is resolved relative to this script: the install directory is
# wherever the script was copied to, which is what makes a stick work.
$dir = $PSScriptRoot
$state = Join-Path $dir 'portable.config'
$portableDefault = Join-Path $dir 'configuration'

# The folder the router keeps its profile in, and why that one. Precedence: an
# explicit -ConfigDir, then -Portable, then whatever an earlier run remembered,
# then the portable default. Both starting the router and just looking for the
# folder go through here, so asking where the profile is cannot change where it
# is: only -ConfigDir, -Portable and the fallback create a folder, and nothing
# here reads or writes portable.config.
function Get-ProfileDir {
    if ($ConfigDir) {
        $made = New-Item -ItemType Directory -Force -Path $ConfigDir
        return [pscustomobject]@{ Path = $made.FullName; Why = '-ConfigDir' }
    }
    if ($Portable) {
        New-Item -ItemType Directory -Force -Path $portableDefault | Out-Null
        return [pscustomobject]@{ Path = $portableDefault; Why = '-Portable' }
    }
    if (Test-Path -LiteralPath $state) {
        $saved = (Get-Content -LiteralPath $state | Where-Object { $_ -match '^configDir=' } |
                  Select-Object -First 1)
        if ($saved) {
            $savedDir = $saved.Substring('configDir='.Length)
            if (Test-Path -LiteralPath $savedDir) {
                return [pscustomobject]@{ Path = $savedDir; Why = "remembered in $(Split-Path -Leaf $state)" }
            }
            Write-Host "The remembered folder is gone: $savedDir" -ForegroundColor Yellow
        }
    }
    New-Item -ItemType Directory -Force -Path $portableDefault | Out-Null
    return [pscustomobject]@{ Path = $portableDefault; Why = 'default for a portable install' }
}

if ($ConfigDir -and $Portable) {
    Write-Host 'Choose either -ConfigDir or -Portable, not both.' -ForegroundColor Red
    exit 2
}

if ($Forget) {
    if (Test-Path -LiteralPath $state) {
        Remove-Item -LiteralPath $state
        Write-Host "Forgot the remembered configuration folder ($state)." -ForegroundColor Green
    } else {
        Write-Host "Nothing remembered ($state does not exist)." -ForegroundColor Yellow
    }
    exit 0
}

if ($ShowProfile) {
    $profDir = Get-ProfileDir
    Write-Host "Configuration dir : $($profDir.Path)  ($($profDir.Why))"
    # The service keeps its profile somewhere else entirely, and a machine can
    # have both, so say which folder this one is. The check is best effort: a
    # host without a Service Controller still gets its folder.
    $asvc = $null
    try {
        $asvc = Get-Service -Name 'I2P+' -ErrorAction SilentlyContinue
    } catch {
        $asvc = $null
    }
    if ($asvc) {
        Write-Host 'The I2P+ service is installed and keeps its profile in %PROGRAMDATA%\i2p instead.' -ForegroundColor Yellow
    }
    try {
        Start-Process explorer.exe -ArgumentList "`"$($profDir.Path)`"" | Out-Null
    } catch {
        Write-Host "Could not open a window for it ($($_.Exception.Message))." -ForegroundColor Yellow
    }
    exit 0
}

# ---------------------------------------------------------------------------
# 1. The installed wrapper
#
# The installer copies I2Psvc.exe to the install root (see install.xml's
# postinstall copy step). A zip package keeps it under lib\wrapper\win64
# instead, with wrapper.dll beside it, so look in both places.
# ---------------------------------------------------------------------------
$svc = $null
foreach ($cand in @((Join-Path $dir 'I2Psvc.exe'), (Join-Path $dir 'lib\wrapper\win64\I2Psvc.exe'))) {
    if (Test-Path -LiteralPath $cand) { $svc = $cand; break }
}
if (-not $svc) {
    Write-Host 'I2Psvc.exe not found next to this script or in lib\wrapper\win64.' -ForegroundColor Red
    Write-Host 'Run this script from the I2P+ install directory.' -ForegroundColor Red
    exit 1
}

$conf = Join-Path $dir 'wrapper.config'
if (-not (Test-Path -LiteralPath $conf)) {
    Write-Host "wrapper.config not found at $conf" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 2. A usable Java
#
# wrapper.config asks the wrapper for "java" from PATH, which fails with a
# cryptic wrapper error when no JRE is installed. Find the JVM here instead,
# say what was found, and hand it to the wrapper explicitly through
# wrapper.java.command. I2P+ needs a 64-bit Java 8 or newer.
# ---------------------------------------------------------------------------
# JAVA_HOME is very often set with the quotes left in - setx JAVA_HOME
# "C:\Program Files\Java\jdk1.8", or a value pasted out of a GUI field - and
# Join-Path then reads the leading quote as a drive name and dies with
# "Cannot find drive. A drive with the name '"c does not exist". Strip the
# decoration, and anything else that makes the value unusable, before using it.
function Get-EnvPath([string]$value) {
    if (-not $value) { return $null }
    $clean = $value.Trim().Trim('"').Trim().TrimEnd('\')
    if ($clean -eq '') { return $null }
    return $clean
}

function Find-Java {
    $found = @()
    foreach ($check in @('JAVA_HOME', 'PATH')) {
        $exe = $null
        if ($check -eq 'JAVA_HOME') {
            # A JAVA_HOME this broken is reported, not fatal: java on PATH is
            # still tried next, and a machine with both should use the working
            # one rather than losing the router to a typo in an environment
            # variable.
            try {
                $javaHome = Get-EnvPath $env:JAVA_HOME
                if ($javaHome) {
                    $cand = Join-Path $javaHome 'bin\java.exe'
                    if (Test-Path -LiteralPath $cand) { $exe = $cand }
                }
            } catch {
                $found += [pscustomobject]@{ Source = $check; Path = $null; Version = $null; Major = 0;
                                            Usable = $false; Note = "JAVA_HOME=$env:JAVA_HOME is not a usable path." }
                continue
            }
        } elseif ($check -eq 'PATH') {
            # No extension: Windows resolves java.exe through PATHEXT. Several
            # matches are possible (a JDK on PATH plus the System32 stub), so
            # take the first one the shell would run.
            $cmd = Get-Command java -CommandType Application -ErrorAction SilentlyContinue |
                   Select-Object -First 1
            if ($cmd) { $exe = $cmd.Source }
        }
        if (-not $exe) {
            $note = switch ($check) {
                'JAVA_HOME' { if ($env:JAVA_HOME) { "no bin\java.exe under JAVA_HOME=$env:JAVA_HOME" } else { 'JAVA_HOME is not set' } }
                'PATH' { 'no java on PATH' }
            }
            $found += [pscustomobject]@{ Source = $check; Path = $null; Version = $null; Major = 0;
                                        Usable = $false; Note = $note }
            continue
        }
        # java prints its version banner on stderr before 1.8 and on stdout
        # after it, so both streams have to be collected. Windows PowerShell 5.1
        # turns a native command's redirected stderr into an error record, and
        # $ErrorActionPreference = 'Stop' then aborts the script with a
        # NativeCommandError before the router ever starts, so the preference is
        # relaxed for this call alone.
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $banner = (& $exe -version 2>&1 | Out-String)
        } catch {
            # Some PowerShell versions still promote the redirected stderr to a
            # terminating error; a JVM we cannot interrogate is not usable, but
            # it is not a reason to die before the wrapper is even tried.
            $banner = ''
        } finally {
            $ErrorActionPreference = $prevEap
        }
        $version = $null
        if ($banner -match 'version "([^"]+)"') { $version = $Matches[1] }
        # "1.8.0_401" is Java 8; "17.0.9" is Java 17. Normalise to the major.
        $major = 0
        if ($version -match '^1\.(\d+)') { $major = [int]$Matches[1] }
        elseif ($version -match '^(\d+)') { $major = [int]$Matches[1] }
        $usable = ($major -ge 8)
        $note = $null
        if (-not $usable) {
            if ($version) {
                $note = "Java $version found via $check is too old (need 8 or newer): $exe"
            } else {
                $note = "Ran $exe (found through $check) but it reported no version."
            }
        }
        $found += [pscustomobject]@{
            Source  = $check
            Path    = $exe
            Version = $version
            Major   = $major
            Usable  = $usable
            Note    = $note
        }
        if ($major -ge 8) { return $found }
    }
    return $found
}

$report = Find-Java
$java = $null
$javaMajor = 0
foreach ($entry in $report) {
    if ($entry.Usable) {
        Write-Host ("Java {0} found via {1}: {2}" -f $entry.Version, $entry.Source, $entry.Path) -ForegroundColor Green
        if (-not $java) { $java = $entry.Path; $javaMajor = $entry.Major }
    } else {
        Write-Host $entry.Note -ForegroundColor Yellow
    }
}
if (-not $java) {
    Write-Host 'I2P+ needs a 64-bit Java 8 or newer.' -ForegroundColor Red
    Write-Host 'Install one, or point JAVA_HOME at it, then run this script again.' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 3. The configuration folder
#
# Resolved by Get-ProfileDir, above. The choice is written to portable.config
# here, so the next bare run just starts the router.
# ---------------------------------------------------------------------------
$profDir = Get-ProfileDir
$chosen = $profDir.Path
$why = $profDir.Why

# Remember it. A read-only install directory (Program Files without extra
# rights) is not an error: the router still runs, it just has to be told
# where the profile is on the next run.
try {
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    @(
        '# Written by portable.ps1 - the folder the router keeps its profile in.',
        '# Edit or delete this file to change the choice; -Forget rewrites it.',
        "# Last written $stamp",
        "configDir=$chosen"
    ) | Set-Content -LiteralPath $state -Encoding UTF8
} catch {
    Write-Host "Could not write $state ($($_.Exception.Message)); pass -ConfigDir on each run." -ForegroundColor Yellow
}

# Two routers on one machine fight over the console port 7657, and the service
# uses %PROGRAMDATA%\i2p, which is a different profile from this one. The
# service check is best effort: a host without a Service Controller must still
# start the router.
$svcRunning = $null
try {
    $svcRunning = Get-Service -Name 'I2P+' -ErrorAction SilentlyContinue
} catch {
    $svcRunning = $null
}
if ($svcRunning -and $svcRunning.Status -eq 'Running') {
    Write-Host 'Warning: the I2P+ service is running. It uses %PROGRAMDATA%\i2p and holds' -ForegroundColor Yellow
    Write-Host 'ports 7654, 7657 and the eepsite ports, so this router cannot bind them.' -ForegroundColor Yellow
    Write-Host 'Stop it first from an elevated window: .\service.ps1 -Action Stop' -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 4. A configuration file the wrapper can read
#
# The installer rewrites the $INSTALL_PATH placeholders in wrapper.config as it
# unpacks the payload (install.xml's parsable step). A zip package keeps them
# literal, and then the classpath and library paths resolve to nothing and the
# JVM never starts. Substitute this directory and run from the copy:
# wrapper.config itself is never modified, so edits made there are picked up on
# the next run, and the copy is rewritten on every run.
# ---------------------------------------------------------------------------

# Tanuki reads a trailing backslash inside a quoted argument as escaping the
# quote, so a drive root is written as "D:\." instead of "D:\".
function Format-WrapperPath([string]$path) {
    $trimmed = $path.TrimEnd('\')
    if ($trimmed -match '^[A-Za-z]:$') { return "$trimmed\." }
    return $trimmed
}

$base = Format-WrapperPath $dir
$text = Get-Content -LiteralPath $conf -Raw
$runConf = $conf
if ($text.Contains('$INSTALL_PATH')) {
    $runConf = Join-Path $dir 'wrapper.portable.config'
    # Written without a BOM: wrapper.config declares its own encoding on line 1,
    # and a BOM in front of it is a parse error for the wrapper.
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($runConf, $text.Replace('$INSTALL_PATH', $base), $utf8NoBom)
    Write-Host "Wrote $runConf - wrapper.config still names `$INSTALL_PATH."
}

# ---------------------------------------------------------------------------
# 5. Hand the choices to the wrapper
#
# The wrapper lets any configuration property be overridden on the command
# line after the config file, and those values win over the file. The
# java.additional indices continue past the last one the config file already
# defines, so its own settings (memory, logger override) stay intact.
# ---------------------------------------------------------------------------
$last = 0
Select-String -LiteralPath $runConf -Pattern '^wrapper\.java\.additional\.(\d+)=' |
    ForEach-Object { if ([int]$_.Matches[0].Groups[1].Value -gt $last) { $last = [int]$_.Matches[0].Groups[1].Value } }

$wrapperArgs = @(
    '-c', $runConf
    "wrapper.java.command=$(Format-WrapperPath $java)"
    "wrapper.working.dir=$base"
    "wrapper.java.additional.$($last + 1)=-Di2p.dir.base=$base"
    "wrapper.java.additional.$($last + 2)=-Di2p.dir.config=$(Format-WrapperPath $chosen)"
)

# Java 24 and newer print a warning on every start that System.loadLibrary is a
# restricted method a future release will block; the flag silences it. Java 8-23
# treat an unknown option as fatal, so it is only passed when the JVM that was
# just checked is new enough to accept it.
if ($javaMajor -ge 24) {
    $wrapperArgs += "wrapper.java.additional.$($last + 3)=--enable-native-access=ALL-UNNAMED"
}

Write-Host ''
Write-Host "Install directory : $dir"
Write-Host "Configuration dir : $chosen  ($why)"
Write-Host 'Router console    : http://127.0.0.1:7657/'
Write-Host 'Stop with Ctrl+C. Wrapper log: wrapper.log in the install directory.'
Write-Host ''

& $svc @wrapperArgs
exit $LASTEXITCODE
