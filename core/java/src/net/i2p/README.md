# I2P Core Java Sources (`net.i2p`)

Core library sources packaged in `i2p.jar`. This is the shared foundation: data structures, cryptographic primitives, the client SDK, utilities, and support packages. Both the router and the bundled applications depend on it.

## Packages

| Package                  | Purpose                                                                                                            |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| [`client/`](client/)     | Public I2P client SDK (`I2PClient`, `I2PSession`) used by applications to talk to the router over I2CP             |
| [`crypto/`](crypto/)     | Cryptographic engines: AES/ChaCha20, ElGamal, EdDSA, X25519, hashes, HMAC, key handling                            |
| [`data/`](data/)         | Wire-format data structures: Hash, Destination, LeaseSet, RouterInfo, I2CP messages                                |
| [`util/`](util/)         | General utilities: logging, EepGet, byte/collection helpers, native BigInteger wrappers                            |
| [`app/`](app/)           | `ClientApp` lifecycle interface for apps started and stopped via `clients.config`                                  |
| [`kademlia/`](kademlia/) | Generic Kademlia DHT (KBucket, KBucketSet) used by the router's network database                                   |
| [`stat/`](stat/)         | Rate and frequency statistics (RateStat) for adaptive router behavior                                              |
| [`update/`](update/)     | Update-checking interfaces and version comparison usable without a router context                                  |
| [`time/`](time/)         | Time synchronization interfaces and utilities                                                                      |
| [`internal/`](internal/) | In-JVM router/client communication over queues instead of socket-based I2CP                                        |
| [`socks/`](socks/)       | SOCKS 4/4a/5 client and server implementations                                                                     |
| [`apache/`](apache/)     | Vendored Apache HttpComponents helpers (hostname verifier, public suffix matching)                                 |

## Top-level classes

- `I2PAppContext` — central access point for I2P services and configuration
- `I2PException` — base exception for I2P code
- `CoreVersion` — version constants

With a few exceptions, this package and everything else in `i2p.jar` is maintained as a stable API for apps, clients, and plugins — unlike `router.jar`, which is internal to the router.

## Docs

- [package.html](package.html) — canonical package description
- [`core/README.md`](../../../../../README.md) — module overview
- [`router/java/src/net/i2p/README.md`](../../../../../router/java/src/net/i2p/README.md) — router source tree (depends on this module)
