# I2P Router (`router/`)

The core I2P router application. Handles all network communication: message sending/receiving, tunnel building, encryption/decryption, peer management, network database, and client coordination.

## Packages

| Package | Description |
|---|---|
| `net.i2p.router` | Router bootstrap, context, job queue, message pools, throttling |
| `net.i2p.router.client` | Client session management and I2CP message handling |
| `net.i2p.router.crypto` | Router-level cryptographic operations |
| `net.i2p.router.message` | Message processing, garlic routing, delivery |
| `net.i2p.router.networkdb` | Network database (floodfill, lookup, persistence) |
| `net.i2p.router.peermanager` | Peer profiling, selection, and management |
| `net.i2p.router.startup` | Startup sequence, reseeding, configuration loading |
| `net.i2p.router.sybil` | Sybil attack detection |
| `net.i2p.router.tasks` | Maintenance tasks and scheduled jobs |
| `net.i2p.router.time` | Clock synchronization and NTP |
| `net.i2p.router.transport` | Transport layer (NTCP2, SSU2, UPnP) |
| `net.i2p.router.tunnel` | Tunnel building, pooling, and message routing |
| `net.i2p.router.util` | Router-specific utilities |

## Key Classes

- `Router` - Main router entry point
- `RouterContext` - Dependency injection context holding all router subsystems
- `JobQueue` - Central job scheduling and execution

## Building

```bash
# Gradle
./gradlew :router:jar

# Ant (from repo root)
ant buildRouter
```

Translations are compiled from `.po` files into Java resource bundles automatically during the build via `java/bundle-messages.sh`.
