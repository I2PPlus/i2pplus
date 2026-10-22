# Desktop GUI (`desktopgui/`)

Standalone desktop application for I2P router control without a browser.

## What It Does

- System tray icon with status display
- Start / stop / restart the router
- Quick stats (peers, bandwidth, tunnels)
- Desktop notifications for events

## Why Use It

- No browser required for basic control
- Always-available tray icon
- Native notifications
- Lightweight alternative to console

## Key Classes

| Class                  | Purpose                                              |
| ---------------------- | ---------------------------------------------------- |
| `Main`                 | Entry point, runs with full router context           |
| `ExternalMain`         | Entry point needing only `i2p.jar`, no `router.jar`  |
| `TrayManager`          | Base tray manager (menu, status icon)                |
| `InternalTrayManager`  | Tray manager with in-process router access           |
| `ExternalTrayManager`  | Tray manager for the standalone (client-only) build  |
| `RouterManager`        | Router lifecycle commands                            |

## Packages

- `net.i2p.desktopgui` - Tray managers and entry points
- `net.i2p.desktopgui.router` - Router lifecycle integration
- `net.i2p.desktopgui.i18n` - Translation support

## Building

```bash
# Gradle
./gradlew :apps:desktopgui:jar

# Ant (from repo root)
ant buildDesktopGui

# Ant (from desktopgui directory)
ant jar
```

## Manual Installation

The shipped `clients.config` starts the console, SAM, the tunnels, the web
server and the browser launcher (entries 0-4) and does not include the desktop
GUI. To start it with the router, add the next free entry:

1. Place `desktopgui.jar` in the `$I2P/lib/` directory.
2. Add to `clients.config`, using `net.i2p.desktopgui.Main` when `router.jar`
   is on the classpath, or `net.i2p.desktopgui.ExternalMain` for a client-only
   setup:
   ```
   clientApp.5.args=
   clientApp.5.delay=5
   clientApp.5.main=net.i2p.desktopgui.Main
   clientApp.5.name=desktopgui
   clientApp.5.startOnLoad=true
   ```
