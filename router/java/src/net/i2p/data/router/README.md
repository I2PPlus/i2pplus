# Router Data (`data/router`)

Router-specific data structures: the router identity and the RouterInfo advertised to and exchanged with other routers. Moved out of `net.i2p.data` so router internals can differ from the client-facing API.

## How It Works

A router's public face is its `RouterInfo`: identity, transport addresses (NTCP/SSU with host, port, and options), capabilities, and version. `RouterIdentity` holds the ElGamal encryption and signing keys; `RouterPrivateKeyFile` stores the private keys (with configurable password) that prove identity and authorize updates. `RouterAddress` describes a single reachable transport endpoint.

## Key Classes

| Class                   | Purpose                                                            |
| ----------------------- | ------------------------------------------------------------------ |
| `RouterInfo`            | Complete router descriptor: identity, addresses, capabilities      |
| `RouterIdentity`        | Router's public encryption and signing keys                        |
| `RouterAddress`         | A single transport endpoint (NTCP2, SSU2) with options             |
| `RouterPrivateKeyFile`  | Persistent storage of the router's private keys                    |
| `RouterKeyGenerator`    | Generates the router's key material                                |
| `SortHelper`            | Utilities for sorting router data                                  |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/README.md`](../README.md) — router source map
