# JCPUID - Native CPU Feature Detection

JCPUID is a JNI library that detects CPU features at runtime (AES-NI, etc.) so the router can select optimized cryptographic implementations.

## Building

### Windows

Install MS Visual Studio and import the project from the `msvc/` files, then build.

Cross-compilation using MinGW has been attempted with versions 4.6.3 and 5.2.1, but the resulting 32-bit binaries have not been able to execute on 32-bit Windows machines. 64-bit machines work fine.

### FreeBSD

Compiled natively: x86 on a 32-bit install, x86_64 on a 64-bit install.

```sh
# 32-bit
BITS=32 ./build.sh

# 64-bit
BITS=64 ./build.sh
```

### Linux

Compiled natively on x86 and x86_64 (64-bit install).

```sh
# 32-bit
BITS=32 ./build.sh

# 64-bit
BITS=64 ./build.sh
```

### macOS

Compiled natively on an macOS machine.

```sh
# 32-bit
BITS=32 ./build.sh

# 64-bit
BITS=64 ./build.sh
```

## Installation

Copy the built library to your I2P installation directory:

- Linux: `libjcpuid.so`
- Windows: `jcpuid.dll`
- macOS: `libjcpuid.jnilib`

## JBIGI

For building the JBIGI native BigInteger library, see [`../jbigi/docs/README.md`](../jbigi/docs/README.md).
