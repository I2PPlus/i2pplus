# I2P Core (`core/`)

The core module is the foundation of the I2P router. It provides the low-level APIs, data structures, cryptographic primitives, and native libraries that all other I2P components depend on.

## Structure

| Directory                    | Description                                                           |
| ---------------------------- | --------------------------------------------------------------------- |
| `java/src/net/i2p/client/`   | I2P client API for applications connecting to the router              |
| `java/src/net/i2p/crypto/`   | Cryptographic engines (AES, ElGamal, EdDSA, ECDSA, HMAC, SHA)         |
| `java/src/net/i2p/data/`     | I2P data structures (Hash, Destination, LeaseSet, Keys, Certificates) |
| `java/src/net/i2p/internal/` | Internal router interfaces consumed by other modules                  |
| `java/src/net/i2p/kademlia/` | Kademlia DHT implementation used for network database lookups         |
| `java/src/net/i2p/stat/`     | Statistics and frequency tracking                                     |
| `java/src/net/i2p/time/`     | Clock and time offset management                                      |
| `java/src/net/i2p/update/`   | Update checking and installation                                      |
| `java/src/net/i2p/util/`     | General utilities (logging, crypto helpers, native BigInteger)        |
| `java/src/net/i2p/socks/`    | SOCKS proxy support                                                   |
| `java/test/`                 | JUnit and ScalaTest tests                                             |
| `java/bench/`                | JMH benchmarks                                                        |
| `c/jbigi/`                   | JBIGI native library (GMP wrapper for fast modular arithmetic)        |
| `c/jcpuid/`                  | JCPUID native library (CPU feature detection)                         |
| `locale/`                    | Translation `.po` files (compiled to resource bundles at build time)  |

## Key Packages

- **`net.i2p.client`** - The public I2P client API (`I2PClient`, `I2PSession`) used by applications to send and receive data through the router.
- **`net.i2p.crypto`** - All cryptographic operations: symmetric encryption (AES/ChaCha20), asymmetric (ElGamal/EdDSA/X25519), hashing (SHA-256/SHA-384/SHA-512), key management, and certificate handling.
- **`net.i2p.data`** - Wire-format data structures serialized for I2CP, NTCP2, and SSU2 protocols.
- **`net.i2p.internal`** - Interfaces bridging core to the `router` module (Floodfill, Tunnel Manager, etc.).

## Building

```bash
# Gradle
./gradlew :core:jar

# Ant (from repo root)
ant buildCore
```

```bash
# Run tests
./gradlew :core:test

# Run benchmarks
./gradlew :core:jmh
```

Translations are compiled from `.po` files into Java resource bundles automatically during the build via `java/bundle-messages.sh`.

## Native Libraries

The `c/` directory contains JNI libraries that accelerate performance-critical operations:

- **JBIGI** (`c/jbigi/`) - Wraps GMP for hardware-optimized `modPow` / `modInverse`. See [`c/jbigi/docs/README.md`](c/jbigi/docs/README.md) for build instructions.
- **JCPUID** (`c/jcpuid/`) - Detects CPU features (AES-NI, etc.) at runtime. See [`c/jcpuid/README.md`](c/jcpuid/README.md) for build instructions.

## License

Public domain unless otherwise marked. Some components use BSD (DSA, ElGamal, SHA256 implementations), Cryptix (AES), or MIT licenses. See `doc/readme.license.txt`.
