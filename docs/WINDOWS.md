# I2P+ on Windows

This document covers the Windows installer package: the single PowerShell
entry point for all service management, shortcut creation, and permission
fixup, and the remaining launcher scripts shipped in the installed
application folder.

## service.ps1

Single entry point for all I2P+ Windows service management, shortcut
creation, and permission fixup. The legacy `.bat` shims and separate
PowerShell scripts (`install_service.ps1`, `uninstall_service.ps1`,
`create_shortcuts.ps1`) were consolidated here.

Run from an elevated (Administrator) PowerShell prompt:

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "service.ps1" -Action <Action>
```

| Action       | Description |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Install`    | Installs the I2P+ Windows service via `I2Psvc.exe -i`. Stops and removes any pre-existing service first (upgrade / reinstall) and appends `wrapper.java.additional.5=-Di2p.dir.config="%PROGRAMDATA%\i2p"` to `wrapper.config` if missing. |
| `Uninstall`  | Removes the service via `I2Psvc.exe -r` and strips the `wrapper.java.additional.5` line from `wrapper.config` so a future install starts clean.                                                                                            |
| `Start`      | Starts the service (`net start I2P+`).                                                                                                                                                                                                     |
| `Stop`       | Stops the service (`net stop I2P+`).                                                                                                                                                                                                       |
| `Enable`     | Sets the service start type to `auto` (boot-time start).                                                                                                                                                                                   |
| `Disable`    | Sets the service start type to `demand` (manual start).                                                                                                                                                                                    |
| `Shortcuts`  | Creates the I2P+ Start Menu program group and desktop shortcuts (Router Console, Start/Stop service, Profile Folder).                                                                                                                      |
| `FixPerms`   | Grants the current user full control over the install directory (`icacls ... /grant <user>:F /c /t /q`) and logs the result to `fixperms.log`. Pass the install path as a positional argument (e.g. `-Action FixPerms $INSTALL_PATH`).     |

The service name is `I2P+`. After installation the Windows Service pack
runs `service.ps1 -Action Install` then `-Action Start`.

### FixPerms

The installer invokes `FixPerms` as a postinstall step so that the
installed user owns the entire tree (required for updates). It runs:

```
icacls <target> /grant "<username>:F" /c /t /q > <target>\fixperms.log
```

## Launcher scripts

| File            | Purpose                                              |
| --------------- | ---------------------------------------------------- |
| `i2prouter.bat` | Router launcher (console mode). Not service-related. |
| `eepget.bat`    | Fetch a URL through I2P from the command line.       |
| `eephead.bat`   | Fetch URL headers through I2P from the command line. |
