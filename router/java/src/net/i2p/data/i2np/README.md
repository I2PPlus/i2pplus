# I2NP (`data/i2np`)

The Invisible Internet Network Protocol: low-level messages sent between routers. This is the "inner protocol" of I2P, carried by the transports (NTCP, SSU) and built/consumed by the tunnel and garlic layers above it.

## How It Works

Routers exchange typed `I2NPMessage` instances — data, tunnel build/management, database store/lookup, garlic, delivery status — each serialized to a wire format with its own message type ID. Tunnel-building messages (`TunnelBuildMessage` and the compact/short variants, plus the encrypted build records) encode the layered encryption used to construct a tunnel. The `I2NPMessageImpl` base provides the envelope (type, message ID, expiration, payload), and `I2NPMessageHandler` dispatches received messages.

## Key Classes

| Class                           | Purpose                                                          |
| ------------------------------- | ---------------------------------------------------------------- |
| `I2NPMessage`/`I2NPMessageImpl` | Message interface and base envelope implementation               |
| `DataMessage`                   | Application data carried through the network                     |
| `GarlicMessage`/`GarlicClove`   | End-to-end encrypted message batches and their cloves            |
| `TunnelDataMessage`             | Message being forwarded through a tunnel                         |
| `TunnelBuildMessage`            | Tunnel construction request (and build/reply variants)           |
| `EncryptedBuildRecord`          | Per-hop encrypted tunnel-build record                            |
| `DatabaseStoreMessage`          | Store a RouterInfo or LeaseSet in the network database           |
| `DatabaseLookupMessage`         | Look up a RouterInfo or LeaseSet                                 |
| `DeliveryStatusMessage`         | Delivery acknowledgement                                         |
| `I2NPMessageHandler`            | Dispatches received messages to the appropriate handler          |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/README.md`](../README.md) — router source map
