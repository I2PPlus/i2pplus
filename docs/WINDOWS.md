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

All actions except `Shortcuts` require Administrator rights. If run from
a non-elevated prompt the script self-elevates (UAC prompt) and relaunches
itself with the same arguments, forwarding the exit code:

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "service.ps1" -Action <Action>
```

| Action       | Description |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Install`    | Installs the I2P+ Windows service via `I2Psvc.exe -i`. Stops and removes any pre-existing service first (upgrade / reinstall) and appends `wrapper.java.additional.5=-Di2p.dir.config=%PROGRAMDATA%/i2p` to `wrapper.config` if that key is missing. The config is backed up to `wrapper.config.bak` and any read-only attribute is cleared before editing. |
| `Uninstall`  | Removes the service via `I2Psvc.exe -r` and strips the `wrapper.java.additional.5` line from `wrapper.config` (backed up to `wrapper.config.bak` first) so a future install starts clean. No-op if the service is not installed. |
| `Start`      | Starts the service (`net start I2P+`). No-op if already running.                                                                                                                                                                                                    |
| `Stop`       | Stops the service (`net stop I2P+`). No-op if already stopped.                                                                                                                                                                                                       |
| `Enable`     | Sets the service start type to `auto` (boot-time start).                                                                                                                                                                                                             |
| `Disable`    | Sets the service start type to `demand` (manual start).                                                                                                                                                                                                              |
| `Shortcuts`  | Creates the I2P+ Start Menu program group and desktop shortcuts (Router Console, Start/Stop service, Profile Folder). Skips shortcuts whose target or icon does not exist. The only action that does not require elevation. |
| `FixPerms`   | Grants the current user full control over the install directory (`icacls ... /grant <user>:F /c /t /q`), logging to `fixperms.log` next to this script. Defaults to the script's directory; pass another path with `-Target` (e.g. `-Action FixPerms -Target $INSTALL_PATH`). |

The service name is `I2P+`. After installation the Windows Service pack
runs `service.ps1 -Action Install` then `-Action Start`.

### FixPerms

The installer invokes `FixPerms` as a postinstall step so that the
installed user owns the entire tree (required for updates). It runs:

```
'Y' | icacls <target> /grant "<user>:F" /c /t /q *><script dir>\fixperms.log
```

The piped `Y` satisfies icacls' confirmation prompt when `<target>`
contains wildcards; without wildcards it is ignored. All output streams
are redirected to `fixperms.log` located next to this script, not inside
`<target>`, so the log itself is not affected by the permission fix.
`<user>` is the full current account name (`domain\user` or `user@domain`),
so it also works for Microsoft accounts and domain logons.

## Launcher scripts

| File            | Purpose                                              |
| --------------- | ---------------------------------------------------- |
| `i2prouter.bat` | Router launcher (console mode). Not service-related. |
| `eepget.bat`    | Fetch a URL through I2P from the command line.       |
| `eephead.bat`   | Fetch URL headers through I2P from the command line. |
