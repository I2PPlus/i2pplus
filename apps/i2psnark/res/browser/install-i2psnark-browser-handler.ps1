# install-i2psnark-browser-handler.ps1
# Register I2PSnark as the handler for magnet links and .torrent files on
# Windows for the user running this script (no admin rights needed, per-user
# HKCU registry entries only). Covers Firefox-family browsers via
# handlers.json seeding and all other browsers (Chrome, Edge, etc.) via the
# OS-level protocol/file association.
#
# This is the Windows counterpart of install-i2psnark-browser-handler.sh and
# mirrors its behavior exactly:
#   1. Locates i2psnark.jar (--jar, $env:I2PSNARK_JAR, $env:I2P, common
#      install locations).
#   2. Writes a small wrapper %LOCALAPPDATA%\i2psnark\i2psnark-open.cmd that
#      runs java -cp i2psnark.jar org.klomp.snark.MagnetHandler, which POSTs
#      each magnet/torrent to the I2PSnark browser API (POST /_add).
#   3. Seeds handlers.json in all LibreWolf/Firefox profiles (skipped while
#      the browser is running).
#   4. Writes HKCU\Software\Classes registry entries mapping the magnet:
#      scheme and .torrent files to the wrapper for all other browsers.
#
# Usage (ExecutionPolicy may block downloaded scripts):
#   powershell -ExecutionPolicy Bypass -File install-i2psnark-browser-handler.ps1
#   Optional: -Url http://127.0.0.1:7657/i2psnark  -Jar C:\path\to\i2psnark.jar

param(
    [string]$Url = "http://127.0.0.1:7657/i2psnark",
    [string]$Jar = ""
)

$ErrorActionPreference = "Stop"

function Log($msg) { Write-Host "[i2psnark-handler] $msg" -ForegroundColor Cyan }
function Warn($msg) { Write-Host "[i2psnark-handler] WARNING: $msg" -ForegroundColor Yellow }
function Die($msg) { Write-Host "[i2psnark-handler] ERROR: $msg" -ForegroundColor Red; exit 1 }

# --- locate i2psnark.jar ----------------------------------------------------

function Find-Jar {
    if ($Jar -and (Test-Path $Jar -PathType Leaf)) { return $Jar }
    if ($env:I2PSNARK_JAR -and (Test-Path $env:I2PSNARK_JAR -PathType Leaf)) { return $env:I2PSNARK_JAR }
    $candidates = @()
    if ($env:I2P) { $candidates += "$env:I2P\lib\i2psnark.jar" }
    $candidates += @(
        "$env:ProgramFiles\i2p\lib\i2psnark.jar",
        "${env:ProgramFiles(x86)}\i2p\lib\i2psnark.jar",
        "$env:LOCALAPPDATA\i2p\lib\i2psnark.jar",
        "$env:APPDATA\i2p\lib\i2psnark.jar",
        "$env:USERPROFILE\i2p\lib\i2psnark.jar",
        "$env:ProgramData\i2p\lib\i2psnark.jar"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c -PathType Leaf)) { return $c }
    }
    return $null
}

function Find-Java {
    if ($env:JAVA_HOME) {
        $j = "$env:JAVA_HOME\bin\java.exe"
        if (Test-Path $j) { return $j }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$jar = Find-Jar
if (-not $jar) { Die "cannot locate i2psnark.jar; pass -Jar PATH" }
$java = Find-Java
if (-not $java) { Die "cannot locate java.exe; set JAVA_HOME or add java to PATH" }

Log "using jar: $jar"
Log "using url: $Url"

# --- wrapper ----------------------------------------------------------------

$wrapperDir = "$env:LOCALAPPDATA\i2psnark"
$wrapper = "$wrapperDir\i2psnark-open.cmd"
New-Item -ItemType Directory -Path $wrapperDir -Force | Out-Null
$wrapperContent = "@echo off`r`n" +
                  "`"$java`" -cp `"$jar`" org.klomp.snark.MagnetHandler --url `"$Url`" %*`r`n"
[System.IO.File]::WriteAllText($wrapper, $wrapperContent, (New-Object System.Text.UTF8Encoding($false)))
Log "wrapper: $wrapper"

# --- HKCU registry (all non-Firefox browsers) -------------------------------

$cmd = "`"$wrapper`" `"%1`""
$keys = @{
    "HKCU:\Software\Classes\magnet\shell\open\command" = $cmd
    "HKCU:\Software\Classes\I2PSnarkTorrent\shell\open\command" = $cmd
}
foreach ($k in $keys.Keys) {
    New-Item -Path $k -Force | Out-Null
    New-ItemProperty -Path $k -Name "(default)" -Value $keys[$k] -PropertyType String -Force | Out-Null
}
New-Item -Path "HKCU:\Software\Classes\.torrent" -Force | Out-Null
New-ItemProperty -Path "HKCU:\Software\Classes\.torrent" -Name "(default)" -Value "I2PSnarkTorrent" -PropertyType String -Force | Out-Null
Log "registry entries written (HKCU)"

# --- Firefox-family handlers.json seeding -----------------------------------

$running = @()
foreach ($p in @("librewolf", "firefox")) {
    if (Get-Process -Name $p -ErrorAction SilentlyContinue) { $running += $p }
}
if ($running.Count -gt 0) { Warn "running browsers (profiles skipped): $($running -join ' ')" }

function ConvertTo-Hashtable([object]$obj) {
    if ($null -eq $obj) { return $null }
    if ($obj -is [System.Management.Automation.PSCustomObject]) {
        $h = New-Object 'System.Collections.Specialized.OrderedDictionary'
        foreach ($p in $obj.PSObject.Properties) {
            if ($p.Value -is [System.Collections.IEnumerable] -and -not ($p.Value -is [string])) {
                # Function output enumerates collections, so re-collect into an
                # array to keep single-element JSON arrays as arrays.
                $h[$p.Name] = @(ConvertTo-Hashtable $p.Value)
            } else {
                $h[$p.Name] = ConvertTo-Hashtable $p.Value
            }
        }
        return $h
    }
    if ($obj -is [System.Collections.IEnumerable] -and -not ($obj -is [string])) {
        $a = New-Object System.Collections.ArrayList
        foreach ($e in $obj) { [void]$a.Add((ConvertTo-Hashtable $e)) }
        return $a
    }
    return $obj
}

function Write-JsonScalar([string]$s) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    foreach ($ch in $s.ToCharArray()) {
        switch ([int][char]$ch) {
            34 { [void]$sb.Append('\"') }   # double quote
            92 { [void]$sb.Append('\\') }   # backslash
            8  { [void]$sb.Append('\b') }
            12 { [void]$sb.Append('\f') }
            10 { [void]$sb.Append('\n') }
            13 { [void]$sb.Append('\r') }
            9  { [void]$sb.Append('\t') }
            default {
                if ([int][char]$ch -lt 32) {
                    [void]$sb.AppendFormat('\u{0:x4}', [int][char]$ch)
                } else {
                    [void]$sb.Append($ch)
                }
            }
        }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function ConvertTo-JsonCompat([object]$obj) {
    if ($null -eq $obj) { return "null" }
    if ($obj -is [string]) { return Write-JsonScalar $obj }
    if ($obj -is [bool]) { return $obj.ToString().ToLowerInvariant() }
    if ($obj -is [int] -or $obj -is [long] -or $obj -is [double] -or $obj -is [decimal]) {
        return $obj.ToString()
    }
    if ($obj -is [System.Collections.IDictionary]) {
        $parts = @()
        foreach ($k in $obj.Keys) {
            $parts += (Write-JsonScalar ([string]$k)) + ":" + (ConvertTo-JsonCompat $obj[$k])
        }
        return "{" + ($parts -join ",") + "}"
    }
    if ($obj -is [System.Collections.IEnumerable]) {
        $items = @()
        foreach ($e in $obj) { $items += ConvertTo-JsonCompat $e }
        return "[" + ($items -join ",") + "]"
    }
    return Write-JsonScalar ([string]$obj)
}

function Get-ProfileDirs([string]$root) {
    $ini = Join-Path $root "profiles.ini"
    if (-not (Test-Path $ini)) { return @() }
    $dirs = New-Object System.Collections.ArrayList
    $section = ""
    $secPath = $null
    $secRel = $false
    foreach ($line in (Get-Content $ini -Encoding UTF8)) {
        $s = $line.Trim()
        if ($s -match "^\[(.+)\]$") {
            if ($secPath) {
                $p = if ($secRel) { Join-Path $root $secPath } else { $secPath }
                [void]$dirs.Add($p)
            }
            $section = $Matches[1]
            $secPath = $null
            $secRel = $false
            continue
        }
        if ($section -match "^Profile") {
            if ($s -match "^Path=(.*)$") {
                $secPath = $Matches[1].Trim()
            } elseif ($s -match "^IsRelative=(.*)$") {
                $secRel = ($Matches[1].Trim() -eq "1")
            }
        }
    }
    if ($secPath) {
        $p = if ($secRel) { Join-Path $root $secPath } else { $secPath }
        [void]$dirs.Add($p)
    }
    return $dirs
}

function Seed-HandlersJson([string]$path) {
    $data = @{}
    if (Test-Path $path) {
        try {
            $data = ConvertTo-Hashtable (Get-Content $path -Raw | ConvertFrom-Json)
        } catch {
            $data = @{}
        }
    }
    if ($null -eq $data) { $data = @{} }
    if (-not $data.Contains("mimeTypes")) { $data["mimeTypes"] = @{} }
    if (-not $data.Contains("schemes")) { $data["schemes"] = @{} }
    $schemes = $data["schemes"]
    $mime = $data["mimeTypes"]

    # Old Firefox format used x-scheme-handler/magnet; migrate to magnet.
    if ($schemes.Contains("x-scheme-handler/magnet") -and -not $schemes.Contains("magnet")) {
        $schemes["magnet"] = $schemes["x-scheme-handler/magnet"]
        $schemes.Remove("x-scheme-handler/magnet")
    }

    $handler = @{ name = "I2PSnark Browser API"; path = $wrapper }
    $changed = $false
    foreach ($pair in @(@("magnet", $schemes), @("application/x-bittorrent", $mime))) {
        $typ = $pair[0]; $store = $pair[1]
        $entry = @{}
        if ($store.Contains($typ)) { $entry = $store[$typ] }
        if ($null -eq $entry) { $entry = @{} }
        $existing = @()
        if ($entry.Contains("handlers") -and $null -ne $entry["handlers"]) { $existing = @($entry["handlers"]) }

        $cleaned = New-Object System.Collections.ArrayList
        $seen = @{}
        foreach ($h in $existing) {
            if ($null -eq $h) { continue }
            if ($h -isnot [System.Collections.IDictionary]) { [void]$cleaned.Add($h); continue }
            $key = [string]$h.GetType().FullName
            if ($h.Contains("path")) { $key += $h["path"] }
            if ($h.Contains("command")) { $key += $h["command"] }
            if ($h.Contains("uriTemplate")) { $key += $h["uriTemplate"] }
            if ($seen.ContainsKey($key)) { continue }
            $seen[$key] = $true
            if ($h.Contains("path") -and $h["path"] -and -not (Test-Path $h["path"])) { continue }
            [void]$cleaned.Add($h)
        }

        $first = $null
        if ($cleaned.Count -gt 0) { $first = $cleaned[0] }
        $sameFirst = $first -is [System.Collections.IDictionary] -and $first.Contains("path") -and $first["path"] -eq $wrapper
        $cleanedChanged = $cleaned.Count -ne $existing.Count
        if ($cleanedChanged) { $changed = $true }
        if ($sameFirst) {
            if ($cleanedChanged) {
                $entry["handlers"] = $cleaned
                $store[$typ] = $entry
            }
            continue
        }
        $entry["handlers"] = @($handler) + $cleaned
        $entry["action"] = 2
        $entry.Remove("ask")
        if ($typ -eq "application/x-bittorrent") {
            $rawExt = $entry["extensions"]
            if ($null -eq $rawExt) {
                $exts = @()
                $changed = $true
            } elseif ($rawExt -is [string] -or $rawExt -isnot [System.Collections.IEnumerable]) {
                $exts = @($rawExt)
                $changed = $true
            } else {
                $exts = @($rawExt)
            }
            if ($exts -notcontains "torrent") {
                $exts = @($exts) + @("torrent")
                $changed = $true
            }
            $entry["extensions"] = $exts
        }
        $store[$typ] = $entry
        $changed = $true
    }
    if ($changed) {
        $json = ConvertTo-JsonCompat $data
        $json += "`n"
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($path, $json, $utf8)
        Log "seeded: $path"
    }
}

$profileRoots = @(
    @{ Dir = "$env:APPDATA\LibreWolf\Profiles"; Browser = "librewolf" },
    @{ Dir = "$env:APPDATA\Mozilla\Firefox\Profiles"; Browser = "firefox" }
)
foreach ($root in $profileRoots) {
    if (-not (Test-Path $root.Dir)) { continue }
    if ($running -contains $root.Browser) {
        Warn "SKIP (browser running): $($root.Dir)"
        continue
    }
    foreach ($pdir in Get-ProfileDirs $root.Dir) {
        $hp = Join-Path $pdir "handlers.json"
        $prefs = Join-Path $pdir "prefs.js"
        if (-not (Test-Path $hp) -and -not (Test-Path $prefs)) { continue }
        Seed-HandlersJson $hp
    }
}

Log "done. Re-run after closing any running LibreWolf/Firefox to seed their profiles."