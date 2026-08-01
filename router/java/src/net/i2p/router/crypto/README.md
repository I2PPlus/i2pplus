# Crypto (`router/crypto`)

Router-specific cryptographic operations: garlic encryption, family key crypto, and transient session key management. Separated from `net.i2p.crypto` (the client-facing API) so the router can evolve these internals freely.

## How It Works

`ElGamalAESEngine` performs the layered encryption/decryption of garlic messages using ElGamal and AES; `TransientSessionKeyManager` caches session keys and session tags so repeated communication with the same peer does not renegotiate keys; `FamilyKeyCrypto` handles router-family shared keys. The `ratchet/` subpackage implements the post-quantum-capable session key ratchet (ECIES/AEAD with session tags), and `pqc/` holds the ML-KEM key exchange.

## Key Classes

| Class                           | Purpose                                                             |
| ------------------------------- | ------------------------------------------------------------------- |
| `ElGamalAESEngine`              | Layered ElGamal + AES encryption for garlic messages                |
| `TransientSessionKeyManager`    | Session key and tag caching for repeated peer communication         |
| `FamilyKeyCrypto`               | Shared keys for router families                                     |
| `ratchet/ECIESAEADEngine`       | Ratchet-based session encryption with AEAD                          |
| `ratchet/MuxedEngine`           | Multiplexes ratchet and legacy session keys                         |
| `pqc/MLKEM` / `MLKEMKeyFactory` | Post-quantum ML-KEM key exchange                                    |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/router/README.md`](../README.md) — router subsystem map
