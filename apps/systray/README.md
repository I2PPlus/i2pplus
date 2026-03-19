# System Tray (`systray/`)

System-tray integration and URL launcher that opens the I2P router console (or custom URLs) in the platform's default browser, with automatic server-readiness detection.

## Packages

- `net.i2p.apps.systray` - URL launcher, config

## Building

```bash
# Gradle
./gradlew :apps:systray:jar

# Ant (from repo root)
ant buildSystray
```
