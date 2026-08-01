# Data (`data/`)

Common data structures shared by the I2P protocols, serialized for I2CP, NTCP2, and SSU2: hashes, destinations, lease sets, certificates, session keys, and the I2CP wire messages themselves.

## How It Works

The wire-format types (`Hash`, `Destination`, `LeaseSet`, `Certificate`, etc.) implement `DataStructure` with `readBytes`/`writeBytes` for network serialization and are immutable where possible. I2CP is implemented as a message hierarchy rooted at `I2CPMessageImpl` with a reader pair (`I2CPMessageReader`) and message handlers, carrying everything from session setup to lease-set requests and message delivery. Session tags (`SessionTag`) provide unidirectional session encryption.

## Key Classes

| Class                           | Purpose                                                            |
| ------------------------------- | ------------------------------------------------------------------ |
| `Hash`                          | 32-byte SHA-256 hash of a router or destination                    |
| `Destination`                   | Complete I2P destination: public keys plus signing public key      |
| `LeaseSet`/`Lease`              | Routing information for delivering to a destination                |
| `Certificate`                   | Router and destination certificate (capabilities, keys, etc.)      |
| `Signature`                     | Digital signature for authentication and integrity                 |
| `SessionKey`/`SessionTag`       | Encryption key and unidirectional session tag                      |
| `DataHelper`                    | Utilities for arrays, streams, formatting                          |
| `I2CPMessage`/`I2CPMessageImpl` | I2CP message interface and base implementation                     |
| `I2CPMessageReader`             | Deserializes I2CP messages from a stream                           |

## Packages

- [`i2cp/`](i2cp/) — I2CP wire messages (session, lease-set, delivery, status)

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
