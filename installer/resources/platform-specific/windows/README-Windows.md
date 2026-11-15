# I2P+ on Windows

Two ways to run I2P+ on Windows. Both use the same files; pick one and stay
with it, because each keeps its router profile (keys, netdb, peer profiles) in a
different place, and two routers must not share one profile at the same time.

A 64-bit Java 8 or newer must be installed for either method.

| Method   | How the router runs                               | Router profile                                                        | Needs Administrator       |
| -------- | ------------------------------------------------- | --------------------------------------------------------------------- | ------------------------- |
| Service  | Windows service `I2P+`, started at boot           | `%PROGRAMDATA%\i2p`                                                   | yes, to install and start |
| Portable | `portable.ps1`, in the window you started it from | `configuration` inside the I2P+ folder, or wherever `-ConfigDir` says | no                        |

## 1. Service installation (the usual one)

Run `i2pinstall.exe`, take the defaults, and let it install the service. The
router then starts at boot and the Start Menu gets *Start I2P+ Service* and
*Stop I2P+ Service* shortcuts.

Manage it from an elevated PowerShell instead of the shortcuts:

```powershell
cd 'C:\Program Files\I2P+'
.\service.ps1 -Action Install     # install or upgrade the service
.\service.ps1 -Action Start
.\service.ps1 -Action Stop
.\service.ps1 -Action Enable      # start at boot
.\service.ps1 -Action Disable     # start by hand
.\service.ps1 -Action Uninstall
.\service.ps1 -Action Shortcuts   # recreate the Start Menu and Desktop links
.\service.ps1 -Action FixPerms    # grant your user write access to the folders
```

`service.ps1` asks for elevation itself when it needs it. The profile lives in
`%PROGRAMDATA%\i2p`, which the installer arranges through
`wrapper.java.additional.5` in `wrapper.config`.

Open the router console at <http://127.0.0.1:7657/> once the router has started
(the first start takes a minute or two while it builds tunnels).

## 2. Portable run (`portable.ps1`)

No service, no Administrator rights: the router runs in the window you started
it from, still under the Tanuki wrapper, so it is watched and restarted if it
crashes. This is the method for a USB stick or a folder you carry around. It
works from an installed folder or from an unpacked `i2p-windows.zip` - the
script finds the wrapper either way.

```powershell
cd D:\i2p
.\portable.ps1
```

On the first run it creates `D:\i2p\configuration`, uses it as the router
profile, and records that choice in `portable.config` next to the script. Every
later run of `.\portable.ps1` with no arguments starts the router on the same
folder, so the whole thing - program plus profile - stays together and moves
with the folder.

```powershell
.\portable.ps1                          # remembered profile, or configuration\ on first run
.\portable.ps1 -Portable                # force configuration\ inside the I2P+ folder
.\portable.ps1 -ConfigDir 'E:\i2p-keys' # keep the profile somewhere else
.\portable.ps1 -Forget                  # drop the remembered folder and exit
```

If your Windows blocks the script, run it once with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\portable.ps1
```

### What the script checks first

- **The wrapper.** `I2Psvc.exe` must be in the I2P+ folder (the installer puts
  it there) or in `lib\wrapper\win64` (where the zip package keeps it). The
  script says which one it is missing.
- **Java.** It looks in `JAVA_HOME` and then on `PATH`, runs the JVM it finds to
  read its version, and prints exactly what it found - path and version - or
  why nothing was usable. A JVM older than 8 is reported and not used. The JVM
  it accepts is handed to the wrapper as `wrapper.java.command`, so the router
  runs on the Java that was checked, not on whatever `PATH` resolves to later.
- **The wrapper configuration.** An installed `wrapper.config` already names real
  paths. The one in the zip package still holds the installer's `$INSTALL_PATH`
  placeholders, which the wrapper cannot resolve, so the script writes
  `wrapper.portable.config` next to itself with those placeholders set to the
  I2P+ folder and starts the wrapper from that copy. `wrapper.config` is never
  edited, the copy is rewritten on every run, and deleting it changes nothing.

### Stopping and restarting

- `Ctrl+C` in the window stops the router gracefully. Closing the window does
  the same.
- If the router's JVM dies on its own, the wrapper restarts it - `wrapper.config`
  decides per exit code (`wrapper.on_exit.*`), with a 5 second gap between the
  death and the restart.
- Start it again with `.\portable.ps1`. Do not start a second one on the same
  profile while the first is still running.

### Logs

- Router logs: `<I2P+ folder>\logs\log-router-*.txt`
- Wrapper log (start, stop, crash, restart): `<I2P+ folder>\wrapper.log`

### How it differs from the service

The script passes three settings to the wrapper on the command line, where they
override `wrapper.config` without editing it:

| Setting                     | Value                                                           |
| --------------------------- | --------------------------------------------------------------- |
| `wrapper.java.command`      | the JVM the script found and checked                            |
| `wrapper.working.dir`       | the I2P+ folder, so relative paths resolve inside it            |
| `wrapper.java.additional.N` | `-Di2p.dir.base=<I2P+ folder>` and `-Di2p.dir.config=<profile>` |

The `N` indices continue past the last one already in `wrapper.config`, so the
memory limits and the logger override in that file stay in force.

## Which profile am I using?

- Service: `%PROGRAMDATA%\i2p`.
- Portable: the `configDir=` line in `portable.config` next to `portable.ps1`.

Moving a profile between the two means copying that folder while the router is
stopped, then pointing the other method at it (`-Action FixPerms` for the
service, `-ConfigDir` for the portable run).
