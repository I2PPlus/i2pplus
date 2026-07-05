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

| Class           | Purpose                 |
| --------------- | ----------------------- |
| `DesktopGUI`    | Main application        |
| `TrayManager`   | System tray integration |
| `RouterControl` | Lifecycle commands      |

## Packages

- `net.i2p.desktopgui` - Tray manager, router lifecycle integration

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

1. Place `desktopgui.jar` in the `$I2P/lib/` directory.
2. Add to `.i2p/clients.config`:
   ```
   clientApp.6.args=
   clientApp.6.delay=5
   clientApp.6.main=net.i2p.desktopgui.Main
   clientApp.6.name=desktopgui
   clientApp.6.startOnLoad=true
   ```