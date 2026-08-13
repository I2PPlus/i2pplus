# Installs the I2P+ Windows service:
#  - points the service config dir at %PROGRAMDATA%\i2p in wrapper.config
#  - stops and removes any existing service (so upgrades and re-installs work)
#  - installs the service with I2Psvc.exe -i
$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$svc = Join-Path $dir 'I2Psvc.exe'
$conf = Join-Path $dir 'wrapper.config'

if (-not (Select-String -LiteralPath $conf -Pattern '^wrapper\.java\.additional\.5=' -Quiet)) {
    Add-Content -LiteralPath $conf -Value 'wrapper.java.additional.5=-Di2p.dir.config="%PROGRAMDATA%\i2p"'
}

& $svc -qs $conf | Out-Null
if ($LASTEXITCODE -ne 0) {
    & $svc -r $conf | Out-Null
}

& $svc -i $conf
exit $LASTEXITCODE