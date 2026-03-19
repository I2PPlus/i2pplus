# I2PControl (`i2pcontrol/`)

JSON-RPC 2.0 interface for external applications to control and monitor the I2P router through a secure API.

## Packages

- `net.i2p.i2pcontrol` - Controller, servlets, security, JSON-RPC handlers

## Building

```bash
# Gradle
./gradlew :apps:i2pcontrol:jar

# Ant (from repo root)
ant buildI2PControl
```
