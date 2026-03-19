# Desktop GUI (`desktopgui/`)

System-tray interface for managing the I2P router lifecycle (start/stop/restart), status monitoring, and notifications without requiring a browser.

## Packages

- `net.i2p.desktopgui` - Tray manager, router lifecycle integration

## Building

```bash
# Gradle
./gradlew :apps:desktopgui:jar

# Ant (from repo root)
ant buildDesktopGui
```
