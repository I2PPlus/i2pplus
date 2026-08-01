# I2P Router Java Sources (`net.i2p`)

Router-side Java sources. The router application lives in `router/`; router-specific data structures (I2NP wire messages, RouterInfo) in `data/`. Client libraries shared with applications live in `core/java/src/net/i2p`.

## Packages

| Package              | Purpose                                                                                                                          |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| [`data/`](data/)     | Router-side data structures: I2NP wire messages (`data/i2np`) and router objects such as RouterInfo and LeaseSet (`data/router`) |
| [`router/`](router/) | The router application: tunnels, transports, netdb, peer management, I2CP server, startup and maintenance tasks                  |

See [`router/README.md`](router/README.md) for the subsystem breakdown.
