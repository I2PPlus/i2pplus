# I2P Router (`router/`)

The router module implements the core I2P router: tunnel management, transport protocols, peer selection, network database, garlic routing, and client session management. It is the central engine that all applications and services depend on.

## Structure

| Directory                              | Description                                                              |
| -------------------------------------- | ------------------------------------------------------------------------ |
| `java/src/net/i2p/router/`             | Router core: lifecycle, job queue, statistics, configuration, throttling |
| `java/src/net/i2p/router/client/`      | I2CP client session management (sessions, message routing, lease sets)   |
| `java/src/net/i2p/router/crypto/`      | Router-level crypto: ElGamal/AES engine, ratchet, transient session keys |
| `java/src/net/i2p/router/message/`     | Garlic message building, parsing, clove sets, outbound delivery          |
| `java/src/net/i2p/router/networkdb/`   | Kademlia DHT, floodfill, iterative lookups, reseed, data stores          |
| `java/src/net/i2p/router/peermanager/` | Peer profiling, capacity/speed calculators, profile organizer            |
| `java/src/net/i2p/router/startup/`     | Router initialization, client app loading, RouterInfo creation           |
| `java/src/net/i2p/router/sybil/`       | Sybil attack detection and mitigation                                    |
| `java/src/net/i2p/router/tasks/`       | Background tasks: watchdog, stats coalescing, graceful shutdown          |
| `java/src/net/i2p/router/time/`        | NTP client for clock synchronization                                     |
| `java/src/net/i2p/router/transport/`   | NTCP2 and SSU2 transport protocols, bandwidth limiter, GeoIP             |
| `java/src/net/i2p/router/tunnel/`      | Tunnel building, dispatch, encryption/decryption at each hop             |
| `java/src/net/i2p/router/util/`        | Router-specific utilities                                                |
| `java/src/net/i2p/router/dummy/`       | Stub facades for testing and embedded builds                             |
| `doc/`                                 | Router documentation                                                     |
| `locale/`                              | Translation `.po` files                                                  |
| `peerProfiles/`                        | Persistent peer profile storage                                          |

## Key Packages

- **`net.i2p.router`** - `Router` lifecycle, `RouterContext`, job queue, `Tuner` (automatic tunnel parameter tuning), `BanLogger`, statistics
- **`net.i2p.router.client`** - I2CP protocol implementation: `ClientManager`, `ClientWriterRunner`, session creation, lease set requests
- **`net.i2p.router.crypto`** - `ElGamalAESEngine` (garlic encryption), `TransientSessionKeyManager`, `FamilyKeyCrypto`, PQ ratchet
- **`net.i2p.router.message`** - Garlic message construction (`GarlicMessageBuilder`), parsing, clove routing, outbound client message jobs
- **`net.i2p.router.networkdb`** - `KademliaNetworkDatabaseFacade`, `FloodOnlySearchJob`, `IterativeSearchJob`, `StoreJob`, `PersistentDataStore`
- **`net.i2p.router.networkdb.reseed`** - Bootstrap reseed from trusted routers (`Reseeder`)
- **`net.i2p.router.peermanager`** - `PeerProfile`, `ProfileOrganizer`, `CapacityCalculator`, `SpeedCalculator`, peer test
- **`net.i2p.router.transport`** - NTCP2 (`NTCPTransport`) and SSU2 (`UDPTransport`) outbound/inbound connections, `CommSystemFacadeImpl`, bandwidth limiting
- **`net.i2p.router.tunnel`** - `TunnelDispatcher`, `HopProcessor`, `FragmentHandler`, `TunnelGateway` (per-hop encryption and message forwarding)
- **`net.i2p.router.tunnel.pool`** - `TunnelPool`, `BuildExecutor`, `TestJob`, `ExpireJob`, `BuildRequestor`, `TunnelPeerSelector` (pool lifecycle, build storms, collapse detection)
- **`net.i2p.router.sybil`** - Sybil attack detection (`SybilRenderer`)

## Key Classes

| Class                              | Purpose                                                        |
| ---------------------------------- | -------------------------------------------------------------- |
| `Router`                           | Main router class: startup, shutdown, configuration, lifecycle |
| `RouterContext`                    | Shared context: config, stats, crypto, peer management         |
| `Tuner`                            | Automatic tunnel parameter tuning based on observed metrics    |
| `JobQueue` / `JobQueueRunner`      | Job scheduling and execution framework                         |
| `TunnelManagerFacade`              | Interface for tunnel pool management                           |
| `BuildExecutor`                    | Orchestrates concurrent tunnel builds across all pools         |
| `TunnelPool`                       | Per-destination tunnel pool: build, test, expire, select       |
| `TunnelDispatcher`                 | Per-hop message forwarding, encryption/decryption              |
| `CommSystemFacadeImpl`             | Transport management: NTCP2/SSU2 connections, GeoIP            |
| `PeerManager` / `ProfileOrganizer` | Peer selection, profiling, capacity/speed scoring              |
| `FloodfillNetworkDatabaseFacade`   | Kademlia DHT with floodfill: store, lookup, explore            |
| `GarlicMessageBuilder`             | Garlic message construction and encryption                     |
| `ClientManager`                    | I2CP client session lifecycle and message routing              |
| `BanLogger` / `Banlist`            | Peer banning and abuse tracking                                |

## Building

```bash
# Ant (from repo root)
ant updaterCompact

# Clean build
ant distclean && ant updaterCompact
```

## Configuration

Router configuration lives in `router.config` and is managed through the console web UI (`/configadvanced`). Key settings include bandwidth limits, transport protocols, tunnel parameters, and peer selection policies. The `Tuner` class automatically adjusts tunnel parameters based on observed network conditions.
