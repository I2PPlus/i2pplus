# Client Implementation (`client/impl`)

Internal implementation of the I2P client SDK: the client side of the I2CP protocol that applications use to talk to the router. Split from `net.i2p.client` in 0.9.21 to separate the interface from the implementation.

## How It Works

`I2PSessionImpl` (and the session variants) drive the I2CP state machine against the router over a socket connection, managing message encryption, lease-set publication, and inbound message delivery. `I2CPMessageHandler` and its per-message-type handlers process protocol messages; `ClientWriterRunner` serializes outbound I2CP; `MessageState` tracks the lifecycle of each outbound message from accepted to acknowledged or failed.

## Key Classes

| Class                                | Purpose                                                        |
| ------------------------------------ | -------------------------------------------------------------- |
| `I2PSessionImpl` / `I2PSessionImpl2` | Primary I2CP session implementations                           |
| `I2PSessionMuxedImpl`                | Session multiplexing multiple concurrent I2CP sessions         |
| `SubSession`                         | Lightweight session sharing a connection with a parent         |
| `I2PClientImpl`                      | Client factory: creates sessions from configuration            |
| `I2CPMessageHandler`                 | Dispatches incoming I2CP messages to handlers                  |
| `ClientWriterRunner`                 | Serializes and sends outbound I2CP messages                    |
| `MessageState`                       | Lifecycle tracking for outbound messages                       |
| `SessionIdleTimer`                   | Detects idle/stalled sessions                                  |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../../../README.md) — core source map
- Public API: `net.i2p.client` in `core/java/src/net/i2p/client`
