# Removes the I2P+ Windows service and its config-dir line from wrapper.config.
$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$svc = Join-Path $dir 'I2Psvc.exe'
$conf = Join-Path $dir 'wrapper.config'

if (Test-Path -LiteralPath $conf) {
    $lines = Get-Content -LiteralPath $conf | Where-Object { $_ -notmatch '^wrapper\.java\.additional\.5=' }
    Set-Content -LiteralPath $conf -Value $lines
}

& $svc -qs $conf | Out-Null
if ($LASTEXITCODE -eq 0) { exit 0 }

& $svc -r $conf | Out-Null
exit $LASTEXITCODE