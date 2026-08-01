# Util (`util/`)

General-purpose utilities used throughout the router and applications: logging, HTTP retrieval (EepGet), byte and collection helpers, file/stream helpers, concurrency helpers, native BigInteger wrappers, and misc conversions.

## How It Works

This is the grab-bag package of `i2p.jar`. Logging goes through the `Log`/`LogManager` hierarchy with pluggable `LogWriter`s (file, console). `EepGet` and its subclasses fetch URLs through the I2P HTTP proxy, supporting resumption, streaming, and headers. `NativeBigInteger` accelerates modular arithmetic using the `jbigi` JNI library when present. `I2PAppThread` provides daemon threads with context-aware exceptions and cleanup.

## Key Classes

| Class                                | Purpose                                                          |
| ------------------------------------ | ---------------------------------------------------------------- |
| `Log`/`LogManager`                   | Logging API and manager, with `LogWriter` outputs                |
| `EepGet`/`PartialEepGet`/`SSLEepGet` | HTTP(S) retrieval through the I2P proxy                          |
| `NativeBigInteger`                   | BigInteger backed by the `jbigi` native library when available   |
| `I2PAppThread`                       | Daemon thread base class with error handling and cleanup         |
| `ByteArrayStream`/`ByteCache`        | Byte array streams and pooled buffers                            |
| `Clock`                              | Current-time source (monotonic and wall clock)                   |
| `RandomSource`/`FortunaRandomSource` | Entropy sources for secure randomness                            |
| `SimpleTimer2`                       | Lightweight scheduling of periodic tasks                         |
| `SystemVersion`                      | Build/version constants and checks                               |
| `Addresses`                          | IP address helpers                                               |

## Packages

- `net.i2p.util` is a single flat package; no subpackages

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
