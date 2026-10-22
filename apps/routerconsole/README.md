# Router Console (`routerconsole/`)

Web-based control panel for the I2P router. The primary user interface for configuration and monitoring.

## What It Does

- **Status** - Peers, bandwidth, tunnels, uptime
- **Configuration** - Network, bandwidth, tunnels, plugins
- **Logs** - Runtime and archive logs
- **Plugins** - Plugin management
- **Address Book** - Destination management
- **I2PSnark** - BitTorrent management
- **Tunnels** - Client and server tunnel status

## Sections

| Section   | Features                                |
| --------- | --------------------------------------- |
| Home      | Summary dashboard                       |
| Tunnels   | Client/server status                    |
| Peers     | Peer list and profiles                  |
| Console   | Router console logs                     |
| Config    | Network, bandwidth, advanced            |
| Plugins   | Plugin list and updates                 |
| Debug     | Port mapper, session keys, DHT, streams |

## Packages

- `net.i2p.router.web` - Console runner, servlets, form handlers
- `net.i2p.router.web.helpers` - Status pages, configuration helpers, summary rendering

## Building

```bash
# Gradle
./gradlew :apps:routerconsole:jar

# Ant (from repo root)
ant buildRouterConsole
```
