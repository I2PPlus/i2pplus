# Building JBIGI for Multiple Architectures

This document describes how to build jbigi native libraries for different CPU architectures.

## Quick Start

### Linux x86_64 (native)

```bash
cd core/c/jbigi
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
./build.sh
```

This builds a generic 64-bit Linux binary (`libjbigi-linux-none_64.so`) that works on any x86_64 CPU.

### Windows x86_64 (cross-compile)

Requires MinGW-w64:

```bash
sudo apt-get install mingw-w64
cd core/c/jbigi
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
TARGET=mingw64 BITS=64 CC=x86_64-w64-mingw32-gcc ./build.sh
```

## Supported Architectures

| Architecture   | Code    | Native Build   | Cross-Compile    |
| -------------- | ------- | -------------- | ---------------- |
| Linux x86_64   | none_64 | ✅ Native      | N/A              |
| Linux x86_32   | none    | ✅ Native      | N/A              |
| Windows x86_64 | none_64 | N/A            | ✅ MinGW         |
| Windows x86_32 | none    | N/A            | ✅ MinGW         |
| ARM 64-bit     | armv8   | ⚠️ Docker/QEMU | ❌               |
| ARM 32-bit     | armv7   | ⚠️ Docker/QEMU | ❌               |

## Building ARM Binaries (Docker/QEMU)

Due to Ubuntu's package repository changes, ARM cross-compilation requires Docker with QEMU emulation.

### Prerequisites

```bash
# Install Docker and QEMU
sudo apt-get install docker.io qemu-user-static

# Enable multi-arch support
docker run --rm --privileged multiarch/qemu-user-static --setup -p yes
```

### Using the Docker Setup Script

```bash
# Copy and run the setup script
cp core/c/jbigi/setup-docker-env.sh /tmp/
sudo bash /tmp/setup-docker-env.sh aarch64
```

This will:
1. Pull an ARM-native Ubuntu image
2. Set up the build environment
3. Create build scripts

### Manual Docker Build

```bash
# For ARM64 (aarch64)
docker run --rm -it \
  -v $(pwd)/core/c/jbigi:/build/jbigi-src \
  -v $(pwd)/core/c/jbigi/lib:/build/output \
  ubuntu:plucky \
  bash -c '
    apt-get update
    apt-get install -y build-essential autoconf automake libtool m4 openjdk-17-jdk
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
    cd /build/jbigi-src
    ./build.sh notest
  '

# For ARM32 (armhf)
docker run --rm -it \
  -v $(pwd)/core/c/jbigi:/build/jbigi-src \
  -v $(pwd)/core/c/jbigi/lib:/build/output \
  arm32v7/ubuntu:plucky \
  bash -c '
    apt-get update
    apt-get install -y build-essential autoconf automake libtool m4 openjdk-17-jdk
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-armhf
    cd /build/jbigi-src
    ./build.sh notest
  '
```

## Troubleshooting

### "Couldn't download binary-armhf/Packages"

The Ubuntu archive no longer hosts ARM packages for older releases. Use Docker with Ubuntu 25.04 (Plucky) or later, which has native ARM support.

### Zen4/Zen5 Not Supported

GMP 6.3.0 does not support zen4/zen5 architectures. Use `zen3` or `none` as the target. Zen5 also has known power/heat issues with GMP's MULX instructions.

### ARM Cross-Compile Fails

GMP's configure tests don't work well with cross-compilers. Use native ARM hardware or Docker/QEMU emulation instead.

## Output Files

Built libraries are placed in:

- `core/c/jbigi/lib/` - Single architecture builds
- `installer/lib/jbigi/` - Distribution binaries

Naming convention: `{lib}jbigi-{OS}-{CPU}.{so|dll|jnilib}`

## Notes

- Always use Java 8 for maximum compatibility
- Strip binaries before distribution: `strip libjbigi.so`
- Test binaries on target hardware when possible
