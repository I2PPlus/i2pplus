# Time (`time/`)

Time synchronization interfaces and utilities available to all I2P applications. The full router-specific implementation lives in `net.i2p.router.time`.

## How It Works

`Timestamper` provides the current time (including offset adjustment), and `BuildTime` tracks build timestamps. These interfaces let applications coordinate timing with network peers and manage clock skew without depending on a router context.

## Key Classes

| Class         | Purpose                                    |
| ------------- | ------------------------------------------ |
| `Timestamper` | Provides current time with offset handling |
| `BuildTime`   | Tracks the build timestamp                 |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
- Router time implementation: `net.i2p.router.time`
