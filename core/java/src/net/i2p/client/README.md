# Client (`client/`)

Public I2P client SDK: the stable API applications use to communicate through the router. The implementation lives in the `impl/` subpackage (moved there in 0.9.21).

## How It Works

An application obtains an `I2PClient` from `I2PClientFactory`, generates a `Destination` if it doesn't already have one, then opens an `I2PSession` — the bridge to the I2P network. The session sends and receives messages over I2CP and reports activity asynchronously via `I2PSessionListener`. Three subpackages extend the base SDK: `datagram/` (authenticated, repliable messages), `naming/` (name-to-Destination resolution), and the separately-packaged streaming library.

## Key Classes

| Class                 | Purpose                                                                  |
| --------------------- | ------------------------------------------------------------------------ |
| `I2PClientFactory`    | Entry point; returns an `I2PClient` instance                             |
| `I2PClient`           | SDK root: creates destinations and sessions                              |
| `I2PSession`          | The session bridge to the router, with send/receive APIs                 |
| `I2PSessionListener`  | Asynchronous notification of session activity                            |
| `I2PSessionMuxedImpl` | Multiplexed session sharing one I2CP connection across subsessions       |
| `I2PSimpleClient`     | Minimal client for simple message sending                                |

## Packages

- [`datagram/`](datagram/) — authenticated, repliable datagram API
- [`naming/`](naming/) — naming services resolving readable names to `Destination`s
- [`impl/`](impl/) — I2CP session and message-handler implementations

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
