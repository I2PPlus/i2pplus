# Transport (`transport/`)

Transport layer implementations and management: moving I2NP messages between routers over NTCP (TCP) and SSU (UDP), with connection management, bandwidth limiting, NAT traversal (UPnP), and GeoIP lookups.

## How It Works

The transport layer sits below the routing layer and above the network stack. `TransportManager` coordinates all transport protocols; each transport bids for message delivery via `TransportBid`, and a `FIFOBandwidthLimiter` per connection throttles traffic.

## Key Classes

| Class                    | Purpose                                        |
| ------------------------ | ---------------------------------------------- |
| `TransportManager`       | Coordinates all transport protocols            |
| `Transport`              | Base interface for transport implementations   |
| `CommSystemFacadeImpl`   | High-level communication system implementation |
| `TransportImpl`          | Base implementation for transports             |
| `TransportEventListener` | Handles transport events                       |
| `TransportBid`           | Transport selection bid mechanism              |
| `FIFOBandwidthLimiter`   | Per-connection bandwidth management            |
| `GeoIP` / `GeoIPv6`      | Country lookup for peers                       |
| `UPnP` / `UPnPManager`   | NAT traversal via UPnP                         |

## Subpackages

- `ntcp/` — NTCP2 transport over TCP
- `udp/` — SSU transport over UDP: handshakes, peer testing, NAT traversal, connection migration
- `crypto/` — transport-level crypto (DH, X25519, ChaCha/Poly1305)

## Docs

- [package.html](package.html) — canonical package description
