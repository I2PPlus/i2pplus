# Tunnel (`tunnel/`)

Tunnel message processing: forwarding, per-hop encryption, and fragmentation for I2P's anonymous routing backbone. Tunnel *building* lives in [`pool/`](pool/).

## How It Works

Tunnels are bidirectional paths through multiple routers. Each router on a path runs one of five hop types:

| Hop type                 | Role                             |
| ------------------------ | -------------------------------- |
| Inbound Gateway (IBGW)   | Entry point for inbound tunnels  |
| Inbound Endpoint (IBEP)  | Exit point for inbound tunnels   |
| Outbound Endpoint (OBEP) | Entry point for outbound tunnels |
| Outbound Gateway (OBGW)  | Exit point for outbound tunnels  |
| Middle hop               | Intermediate router in the path  |

`TunnelDispatcher` routes messages to the correct tunnel; each hop layer re-encrypts before forwarding via `HopProcessor`.

## Key Classes

| Class                      | Purpose                                        |
| -------------------------- | ---------------------------------------------- |
| `TunnelDispatcher`         | Central coordinator for tunnel message routing |
| `TunnelGateway`            | Tunnel entry and exit points                   |
| `TunnelGatewayPumper`      | Gateway message pumping and scheduling         |
| `TunnelParticipant`        | Participation in a tunnel                      |
| `TunnelCreatorConfig`      | Tunnel creation configuration                  |
| `HopProcessor`             | Per-hop message processing and encryption      |
| `FragmentHandler`          | Message fragmentation and reassembly           |
| `InboundEndpointProcessor` | Inbound endpoint message processing            |
| `OutboundGatewayProcessor` | Outbound gateway message processing            |

## Docs

- [package.html](package.html) — canonical package description
- [pool/](pool/) — tunnel building, peer selection, and testing
