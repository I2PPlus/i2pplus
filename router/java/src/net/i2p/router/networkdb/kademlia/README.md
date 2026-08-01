# Network Database Kademlia (`networkdb/kademlia`)

The Kademlia DHT implementation of the I2P network database: storing, looking up, and flooding RouterInfos and LeaseSets. This is the bulk of the netdb — the `networkdb/` parent package holds only the coordination jobs.

## How It Works

`KademliaNetworkDatabaseFacade` (standard routers) and `FloodfillNetworkDatabaseFacade` (floodfill "super-nodes") implement the DHT. Lookups run as search jobs (`IterativeSearchJob`, `SingleSearchJob`, `SearchJob`) that query progressively closer peers; stores (`StoreJob`) publish entries to nearby routers. Floodfill routers flood stores and lookups network-wide (`FloodOnlySearchJob`, `FloodfillRouterInfoFloodJob`). `DataStore` / `PersistentDataStore` / `TransientDataStore` back the storage, and `PeerSelector` picks peers by XOR distance from the `kademlia/` library in core.

## Key Classes

| Class                                        | Purpose                                                      |
| -------------------------------------------- | ------------------------------------------------------------ |
| `KademliaNetworkDatabaseFacade`              | Standard DHT facade: store, lookup, explore                  |
| `FloodfillNetworkDatabaseFacade`             | Floodfill router facade with network-wide flooding           |
| `IterativeSearchJob`                         | Multi-hop lookup toward a target key                         |
| `StoreJob`                                   | Stores entries in the network database                       |
| `ExploreJob` / `StartExplorersJob`           | Network exploration to refresh peer knowledge                |
| `PersistentDataStore` / `TransientDataStore` | Persistent and in-memory storage backends                    |
| `PeerSelector` / `FloodfillPeerSelector`     | Peer selection by XOR distance                               |
| `LookupThrottler` / `FloodThrottler`         | Rate limiting for lookups and floods                         |
| `NegativeLookupCache`                        | Caches failed lookups to avoid repeats                       |
| `ExpireRoutersJob` / `ExpireLeasesJob`       | Periodic cleanup of stale entries                            |
| `RefreshRoutersJob`                          | Periodic bucket refresh                                      |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/router/networkdb/README.md`](../README.md) — networkdb coordination package
- Generic DHT library: `net.i2p.kademlia` in core
