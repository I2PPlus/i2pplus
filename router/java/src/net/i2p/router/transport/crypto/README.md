# Transport Crypto (`transport/crypto`)

Cryptographic operations for the I2P transport protocols: key exchange, session key management, and transport-layer encryption material.

## How It Works

`X25519KeyFactory` implements the X25519 elliptic-curve Diffie-Hellman key exchange used to establish NTCP2 and SSU2 transport sessions, providing the key agreement material for peer authentication and session encryption.

## Key Classes

| Class              | Purpose                                         |
| ------------------ | ----------------------------------------------- |
| `X25519KeyFactory` | X25519 ECDH key generation and agreement        |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/router/transport/README.md`](../README.md) — transport subsystem map
