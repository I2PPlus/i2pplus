# I2PTunnel (`i2ptunnel/`)

Configurable tunnels for clients and servers (HTTP, SOCKS, IRC, streamr, etc.) with a comprehensive web UI for tunnel management and configuration.

## Packages

- `net.i2p.i2ptunnel` - Core tunnel framework, controller, and configuration
- `net.i2p.i2ptunnel.sockets` - Protocol-specific tunnel implementations (HTTP, SOCKS, IRC)

## Building

```bash
# Gradle
./gradlew :apps:i2ptunnel:jar

# Ant (from repo root)
ant buildI2PTunnel
```
