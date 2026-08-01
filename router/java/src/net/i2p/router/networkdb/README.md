# Network Database (`networkdb/`)

Network database management and coordination: the distributed hash table (DHT) that stores RouterInfos and LeaseSets.

## How It Works

Most of the DHT implementation is in `kademlia/`; this package contains the high-level coordination jobs. Lookups and publications run as scheduled jobs on the router's job queue.

## Key Classes

| Class                            | Purpose                                  |
| -------------------------------- | ---------------------------------------- |
| `HandleDatabaseLookupMessageJob` | Processes database lookup requests       |
| `PublishLocalRouterInfoJob`      | Manages publication of local router info |

## Subpackages

- `kademlia/` — Kademlia DHT implementation
- `reseed/` — Reseeding (initial bootstrap) support

## Docs

- [package.html](package.html) — canonical package description
