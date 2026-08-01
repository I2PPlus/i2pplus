# SOCKS (`socks/`)

SOCKS 4/4a/5 protocol implementation: client and server support for routing TCP connections through I2P. Extracted from the i2ptunnel package in 0.9.33 for reuse across I2P applications, including SSLEepGet.

## How It Works

The SOCKS clients (`SOCKS4Client`, `SOCKS5Client`) open a TCP connection to a SOCKS proxy, perform the version-specific handshake and (for SOCKS5) authentication, then relay a requested destination to the proxy for tunneling. `SOCKS5Constants` and `SOCKS4Constants` define the protocol's method, command, reply, and address-type bytes.

## Key Classes

| Class             | Purpose                                          |
| ----------------- | ------------------------------------------------ |
| `SOCKS4Client`    | SOCKS 4/4a client handshake and relay            |
| `SOCKS5Client`    | SOCKS 5 client handshake, auth, and relay        |
| `SOCKS4Constants` | SOCKS 4 protocol constants                       |
| `SOCKS5Constants` | SOCKS 5 protocol constants                       |
| `SOCKSException`  | Base exception for SOCKS protocol errors         |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
- Higher-level tunnel management: `net.i2p.i2ptunnel`
