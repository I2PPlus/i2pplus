# I2P+ Windows service management.
# Usage: service.ps1 -Action <Install|Uninstall|Start|Stop|Enable|Disable|Shortcuts|FixPerms>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('Install','Uninstall','Start','Stop','Enable','Disable','Shortcuts','FixPerms')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
$svcName = 'I2P+'
$dir = $PSScriptRoot
$svc = Join-Path $dir 'I2Psvc.exe'
$conf = Join-Path $dir 'wrapper.config'

function Invoke-Wrapper {
    & $svc @Args 2>&1 | Out-Null
}

function New-Shortcut {
    param($path, $target, $arguments, $workingDir, $icon, $iconIndex)
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $target
    if ($arguments) { $shortcut.Arguments = $arguments }
    $shortcut.WorkingDirectory = $workingDir
    if ($icon) { $shortcut.IconLocation = "$icon,$iconIndex" }
    $shortcut.Save()
}

switch ($Action) {
    'Install' {
        # Ensure the config points at %PROGRAMDATA%\i2p for the service
        if (Test-Path -LiteralPath $conf) {
            if (-not (Select-String -LiteralPath $conf -Pattern '^wrapper\.java\.additional\.5=' -Quiet)) {
                Add-Content -LiteralPath $conf -Value 'wrapper.java.additional.5=-Di2p.dir.config="%PROGRAMDATA%\i2p"'
            }
        }
        # Stop and remove any existing service (upgrade / reinstall)
        # -qs exits nonzero (bit 0 = installed) when a service exists
        Invoke-Wrapper "-qs", $conf
        if ($LASTEXITCODE -ne 0) {
            Invoke-Wrapper "-r", $conf
        }
        # Install
        Invoke-Wrapper "-i", $conf
        if ($LASTEXITCODE -ne 0) { throw "I2Psvc.exe -i failed with exit code $LASTEXITCODE" }
    }
    'Uninstall' {
        # Strip the config-dir line so a future install starts clean
        if (Test-Path -LiteralPath $conf) {
            $lines = Get-Content -LiteralPath $conf | Where-Object { $_ -notmatch '^wrapper\.java\.additional\.5=' }
            Set-Content -LiteralPath $conf -Value $lines
        }
        # -qs exits 0 when no service is installed; nothing to do
        Invoke-Wrapper "-qs", $conf
        if ($LASTEXITCODE -eq 0) { exit 0 }
        Invoke-Wrapper "-r", $conf
        if ($LASTEXITCODE -ne 0) { throw "I2Psvc.exe -r failed with exit code $LASTEXITCODE" }
    }
    'Start'   { net start $svcName }
    'Stop'    { net stop $svcName }
    'Enable'  { sc config $svcName start= auto }
    'Disable' { sc config $svcName start= demand }
    'Shortcuts' {
        $shell = New-Object -ComObject WScript.Shell
        $group = Join-Path ([Environment]::GetFolderPath('Programs')) 'I2P+'
        $desktop = [Environment]::GetFolderPath('Desktop')
        New-Item -ItemType Directory -Force -Path $group | Out-Null

        $psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $psArgs = '-NoProfile -ExecutionPolicy Bypass -File'
        $svcScript = Join-Path $dir 'service.ps1'

        New-Shortcut (Join-Path $group 'Open I2P+ Profile Folder.lnk') "$env:SystemRoot\System32\explorer.exe" '"%programdata%\i2p"' $dir "$env:SystemRoot\System32\shell32.dll" 3
        New-Shortcut (Join-Path $group 'I2P+ Router Console.lnk') (Join-Path $dir 'docs\startconsole.html') '' $dir (Join-Path $dir 'docs\console.ico') 0
        New-Shortcut (Join-Path $group 'Start I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Start" $dir (Join-Path $dir 'docs\start.ico') 0
        New-Shortcut (Join-Path $group 'Stop I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Stop" $dir (Join-Path $dir 'docs\stop.ico') 0
        New-Shortcut (Join-Path $desktop 'I2P+ Router Console.lnk') (Join-Path $dir 'docs\startconsole.html') '' $dir (Join-Path $dir 'docs\console.ico') 0
        New-Shortcut (Join-Path $desktop 'Start I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Start" $dir (Join-Path $dir 'docs\start.ico') 0
        New-Shortcut (Join-Path $desktop 'Stop I2P+ Service.lnk') $psExe "$psArgs `"$svcScript`" -Action Stop" $dir (Join-Path $dir 'docs\stop.ico') 0
    }
    'FixPerms' {
        $target = $args[0]
        if (-not $target) { $target = $PSScriptRoot }
        $logPath = Join-Path $target 'fixperms.log'
        # 'echo Y' answers icacls' "are you sure?" prompt (harmless
        # elsewhere); F=full control /c=continue /q=quiet /t=recursive
        'Y' | & icacls $target /grant "$($env:USERNAME):F" /c /t /q 2>&1 > $logPath
    }
}
