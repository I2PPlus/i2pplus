# System Tray (`systray/`)

Browser launcher for the router console. The module name is historical: it no
longer puts an icon in the system tray, that lives in `desktopgui/`.

## What It Does

- Waits for the router console to accept connections
- Opens the console URL in the platform's default browser, falling back to
  popular third-party browsers
- Honours the `routerconsole.browser` advanced config property for the browser
  command

It ships as client app 4 in `clients.config`, with the console URL as its
argument.

## When To Use

- Desktop installations that want the console opened at startup
- Replace it with `desktopgui/` for a tray icon and router controls

## Key Classes

| Class         | Purpose                                                         |
| ------------- | --------------------------------------------------------------- |
| `UrlLauncher` | Entry point, waits for the console, then runs a browser command |

## Packages

- `net.i2p.apps.systray` - URL launcher, browser detection

## Building

```bash
# Gradle
./gradlew :apps:systray:jar

# Ant (from repo root)
ant buildSystray
```
