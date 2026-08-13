# Creates the I2P+ Start Menu (program group) and desktop shortcuts.
$ErrorActionPreference = 'Stop'
$shell = New-Object -ComObject WScript.Shell
$dir = $PSScriptRoot

$group = Join-Path ([Environment]::GetFolderPath('Programs')) 'I2P+'
$desktop = [Environment]::GetFolderPath('Desktop')
New-Item -ItemType Directory -Force -Path $group | Out-Null

function New-Shortcut {
    param($path, $target, $arguments, $workingDir, $icon, $iconIndex)
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $target
    if ($arguments) { $shortcut.Arguments = $arguments }
    $shortcut.WorkingDirectory = $workingDir
    if ($icon) { $shortcut.IconLocation = "$icon,$iconIndex" }
    $shortcut.Save()
}

New-Shortcut (Join-Path $group 'Open I2P+ Profile Folder.lnk') "$env:SystemRoot\System32\explorer.exe" '"%programdata%\i2p"' $dir "$env:SystemRoot\System32\shell32.dll" 3
New-Shortcut (Join-Path $group 'I2P+ Router Console.lnk') (Join-Path $dir 'docs\startconsole.html') '' $dir (Join-Path $dir 'docs\console.ico') 0
New-Shortcut (Join-Path $group 'Start I2P+ Service.lnk') (Join-Path $dir 'StartI2P+Service.bat') '' $dir (Join-Path $dir 'docs\start.ico') 0
New-Shortcut (Join-Path $group 'Stop I2P+ Service.lnk') (Join-Path $dir 'StopI2P+Service.bat') '' $dir (Join-Path $dir 'docs\stop.ico') 0
New-Shortcut (Join-Path $desktop 'I2P+ Router Console.lnk') (Join-Path $dir 'docs\startconsole.html') '' $dir (Join-Path $dir 'docs\console.ico') 0
New-Shortcut (Join-Path $desktop 'Start I2P+ Service.lnk') (Join-Path $dir 'StartI2P+Service.bat') '' $dir (Join-Path $dir 'docs\start.ico') 0
New-Shortcut (Join-Path $desktop 'Stop I2P+ Service.lnk') (Join-Path $dir 'StopI2P+Service.bat') '' $dir (Join-Path $dir 'docs\stop.ico') 0