# Crypto (`crypto/`)

Core cryptographic primitives and algorithms used throughout I2P: asymmetric encryption (ElGamal, ECIES), digital signatures (DSA, EdDSA, RedDSA), symmetric encryption (AES, ChaCha20), hashing (SHA-256, SHA-384, SHA-512), key derivation (HKDF), and a JCA provider.

## How It Works

The `I2PProvider` registers these engines with the JCA framework, so code uses standard `Cipher`/`Signature`/`MessageDigest`/`KeyPairGenerator` APIs with I2P's algorithm names (e.g. `ElGamal`, `EdDSA`, `X25519`). Signature and encryption algorithms are enumerated in `SigType` and `EncType` respectively. ElGamal is implemented on the `elgamal/` subpackage's generic parameter types; EdDSA lives in the `eddsa/` subpackage (a fork of the reference implementation). The `provider/` subpackage holds the engine classes.

## Key Classes

| Class               | Purpose                                                              |
| ------------------- | -------------------------------------------------------------------- |
| `I2PProvider`       | JCA provider that registers I2P algorithms                           |
| `SigType`           | Enumeration of supported signature algorithms                        |
| `EncType`           | Enumeration of supported encryption algorithms                       |
| `AESEngine`         | AES block cipher engine                                              |
| `ChaCha20`          | ChaCha20 stream cipher                                               |
| `ElGamalEngine`     | ElGamal encryption engine (via `elgamal/`)                           |
| `EdDSAEngine`       | EdDSA/Ed25519 signature engine (via `eddsa/`)                        |
| `X25519DH`          | X25519 Diffie-Hellman key agreement                                  |
| `Hash384`/`Hash512` | SHA-384/SHA-512 hashing                                              |
| `HKDF`              | HKDF key derivation                                                  |
| `SessionKeyManager` | Manages session encryption keys                                      |
| `EntropyHarvester`  | Collects entropy for the random source                               |

## Packages

- [`elgamal/`](elgamal/) — ElGamal keys, parameters, and engine (JCA-style API)
- [`eddsa/`](eddsa/) — EdDSA implementation and math primitives (reference fork)
- [`x25519/`](x25519/) — X25519 key agreement and spec
- [`provider/`](provider/) — Engine implementations registered with the JCA provider

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
