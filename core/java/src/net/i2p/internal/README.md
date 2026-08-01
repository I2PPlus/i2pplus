# Internal (`internal/`)

Internal communication between a router and client running in the same JVM, using queues instead of socket-based I2CP.

## How It Works

When router and client share a JVM, `InternalClientManager` lets them communicate directly through `I2CPMessageQueue` implementations rather than serializing I2CP messages over socket streams — reduced overhead and improved performance. `PoisonI2CPMessage` signals queue shutdown; `QueuedI2CPMessageReader` reads messages off the queue.

## Key Classes

| Class                     | Purpose                                              |
| ------------------------- | ---------------------------------------------------- |
| `InternalClientManager`   | In-JVM client manager bypassing sockets              |
| `I2CPMessageQueue`        | Queue interface for internal I2CP messaging          |
| `QueuedI2CPMessageReader` | Reads I2CP messages from a queue                     |
| `PoisonI2CPMessage`       | Queue shutdown marker                                |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
