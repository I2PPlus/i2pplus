# I2P Router (`router/`)

The router application: sending and receiving I2NP messages, building tunnels, and multi-layer encryption, plus the subsystems that support them.

> Classes in this package and its sub-packages are not for use by apps, clients, or plugins (except routerconsole). Subject to change — not a stable API. Applications bundling the router should instantiate `Router` and call `runRouter()`, or use `Router.main()` / `RouterLaunch`; most public methods on `Router` are maintained as a stable API for that use.

## Packages

| Package                                 | Purpose                                                                             |
| --------------------------------------- | ----------------------------------------------------------------------------------- |
| [`app/`](app/)                          | `RouterApp` — lifecycle interface for bundled applications                          |
| [`client/`](client/README.md)           | I2CP server side: client sessions, lease requests, message routing for applications |
| [`crypto/`](crypto/)                    | Crypto engines: ElGamal/AES, family key crypto, transient session keys              |
| [`message/`](message/README.md)         | Garlic message building, parsing, and source routing                                |
| [`networkdb/`](networkdb/README.md)     | NetDB coordination, plus `kademlia/` (DHT) and `reseed/` subpackages                |
| [`peermanager/`](peermanager/README.md) | Peer profiling, capacity/speed calculation, peer selection                          |
| [`startup/`](startup/README.md)         | Boot sequence jobs: config load, RouterInfo, comm system, clients                   |
| [`sybil/`](sybil/)                      | Sybil attack detection                                                              |
| [`tasks/`](tasks/README.md)             | Periodic maintenance jobs and background tasks                                      |
| [`time/`](time/)                        | NTP client and router timestamper                                                   |
| [`transport/`](transport/README.md)     | NTCP and SSU transports, UPnP, GeoIP, bandwidth limiting                            |
| [`tunnel/`](tunnel/README.md)           | Tunnel message processing; [`pool/`](tunnel/pool/) builds tunnels                   |
| [`util/`](util/)                        | Data structures and utilities (queues, bloom filters, event log)                    |

## Top-level classes

`Router` (lifecycle), `RouterContext` (per-router context), the message pools (`InNetMessagePool` / `OutNetMessagePool`), the job queue (`JobQueue`, `JobImpl`), `StatisticsManager`, `KeyManager`, and the banlist / blocklist facades.

## Docs

- [package.html](package.html) — canonical package description, including the message-flow diagram
