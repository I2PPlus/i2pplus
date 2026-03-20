# JBIGI - Native BigInteger Acceleration Library

## Overview

JBIGI is a native JNI library that provides hardware-optimized BigInteger operations for I2P. It wraps the GNU Multiple Precision Arithmetic Library (GMP) to accelerate cryptographic operations used in I2P's routing and encryption.

### What it does

The library provides fast implementations of:
- `modPow` - Modular exponentiation (base^exponent mod modulus)
- `modPowCT` - Constant-time modular exponentiation (resistant to timing attacks)
- `modInverse` - Modular multiplicative inverse

### Version Information

- **JBIGI Version**: 4 (as reported by `nativeJbigiVersion()`)
- **GMP Version**: 6.3.0 (configurable in `download_gmp.sh`)

## Requirements

- GCC (or MinGW on Windows)
- GMP library source (automatically downloaded)
- `m4` (for GMP configuration)
- JDK with JNI headers (Java 8 recommended)

### Installing Dependencies

#### Debian/Ubuntu/Mint/Pop!_OS
```bash
sudo apt-get install build-essential m4 openjdk-8-jdk
# For Windows cross-compilation:
sudo apt-get install gcc-mingw-w64-x86-64
```

#### Fedora/RHEL/CentOS/AlmaLinux
```bash
sudo dnf install gcc make m4 java-1.8.0-openjdk-devel
# For Windows cross-compilation:
sudo dnf install mingw64-gcc
```

#### Arch/Manjaro/EndeavourOS
```bash
sudo pacman -S base-devel m4 jdk8-openjdk
# For Windows cross-compilation:
sudo pacman -S mingw-w64-gcc
```

#### openSUSE/SLES
```bash
sudo zypper install gcc make m4 java-1_8_0-openjdk-devel
# For Windows cross-compilation:
sudo zypper install cross-x86_64-w64-mingw32-gcc
```

#### Gentoo
```bash
sudo emerge --ask sys-devel/gcc sys-devel/make sys-devel/m4 virtual/jdk:1.8
# For Windows cross-compilation:
sudo emerge --ask cross-x86_64-w64-mingw32/gcc
```

#### macOS
```bash
brew install gcc m4 openjdk@8
```

## Building

### Recommended: Platform-Specific Scripts

These scripts detect supported CPU targets, compile optimized libraries, and strip binaries automatically.

#### Windows 64-bit (cross-compile from Linux)

```bash
cd core/c/jbigi
./build-win64.sh --help     # Show help
./build-win64.sh -a         # Build all supported CPU targets
./build-win64.sh --generic  # Generic build only (faster)
```

Detects 20 CPU targets (zen3, zen2, zen, skylake, core2, etc.) and builds `jbigi-windows-{cpu}_64.dll` for each. Uses bundled JNI headers - no Java installation required on the build host.

Prerequisites: `gcc-mingw-w64-x86-64`, `m4`, `make`

#### Linux 64-bit (native)

```bash
cd core/c/jbigi
./build-linux64.sh --help     # Show help
./build-linux64.sh -a         # Build all supported CPU targets
./build-linux64.sh --generic  # Generic build only (faster)
```

Detects 20 CPU targets and builds `libjbigi-linux-{cpu}_64.so` for each.

Prerequisites: `gcc`, `m4`, `make`, JDK 8 with JNI headers

#### Linux ARM64 (cross-compile from x86_64)

```bash
cd core/c/jbigi
./build-arm64.sh --help     # Show help
./build-arm64.sh -a         # Build all supported CPU targets
./build-arm64.sh --generic  # Generic build only (faster)
```

Detects 5 ARM64 targets (armv8, armv8.2, cortex-a72, cortex-a76, cortex-a53) and builds `libjbigi-linux-{cpu}_64.so` for each. Uses bundled JNI headers.

Prerequisites: `gcc-aarch64-linux-gnu`, `m4`, `make`

#### Options (all scripts)

| Flag | Description |
|------|-------------|
| `-a`, `--all` | Build for all supported CPU targets |
| `-g`, `--generic` | Build generic library only |
| `-h`, `--help` | Show help message |
| *(no args)* | Show help message |

Both scripts will:
1. Check dependencies and suggest distro-specific install commands
2. Detect which CPU targets your GCC supports
3. Display the list of supported targets before building
4. Build GMP and JBigI for each target
5. Strip binaries automatically

### Legacy Build Scripts

#### Quick Build (Current Machine Only)

Build a dynamic library using the system GMP:

```bash
cd core/c/jbigi
./build.sh dynamic
```

This builds a `libjbigi.so` (or `.dll`/`.jnilib`) optimized for your current architecture.

#### Full Build (All Architectures)

Build for multiple CPU architectures:

```bash
cd core/c/jbigi
./build.sh all
```

This produces optimized libraries for:
- x86 (pentium, pentium2, pentium4, etc.)
- x86_64 (core2, athlon64, zen, skylake, etc.)
- ARM (armv6, armv7a, armv8/aarch64)

#### Static vs Dynamic Linking

- `./build.sh local dyn` - Links against system GMP (faster build)
- `./build.sh local` (default) - Statically links GMP (portable, recommended)

#### 32-bit build on 64-bit system
```bash
BITS=32 ./build.sh
```

#### Android NDK
```bash
TARGET=android BITS=64 ./build.sh all
```

### GCC 15+ Compatibility

GMP 6.3.0 has a compatibility issue with GCC 15 and newer due to stricter C99 standards compliance. The configure tests fail because `void g()` (old-style C) is no longer interpreted as taking no arguments.

A patch is automatically applied when running `download_gmp.sh`:
- **Patch**: `patches/gcc15-fix.diff`
- **Issue**: `void g(){}` in configure tests must be `void g(int, t2, int, t2, t2, int){}` to match the actual function signature

## Output

Built libraries are placed in:
- `lib/net/i2p/util/` - Platform-specific builds

Naming convention:
- Linux: `libjbigi-linux-{cpu}_64.so`
- Windows: `jbigi-windows-{cpu}_64.dll`
- FreeBSD: `libjbigi-freebsd-{cpu}.so`
- macOS: `libjbigi-osx-{cpu}.jnilib`

Binaries are stripped automatically to reduce file size.

## Ant Targets

Run from the project root directory:

```bash
ant buildJbigi-win64    # Build Windows 64-bit DLLs
ant buildJbigi-linux64  # Build Linux 64-bit .so files
ant buildJbigi          # Build both, copy to installer/lib/jbigi/
```

These targets call the platform-specific build scripts and copy the results to `installer/lib/jbigi/`.

## Build Scripts

| Script | Purpose |
|--------|---------|
| `build-win64.sh` | Windows 64-bit cross-compilation (all or generic) |
| `build-linux64.sh` | Linux 64-bit native compilation (all or generic) |
| `build-all.sh` | Full multi-architecture build (legacy) |
| `build_jbigi.sh` | Core compilation script (used by build-all.sh) |
| `build.sh` | Wrapper for local/all builds (legacy) |
| `download_gmp.sh` | Downloads and patches GMP source |

## Troubleshooting

### "Cannot find jni.h"

Set `JAVA_HOME` to your JDK installation:
```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
```

### "Unable to detect default setting for BITS variable"

Manually specify:
```bash
BITS=64 ./build.sh
```

### "GMP download failed"

Manually download GMP:
```bash
cd core/c/jbigi
wget https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.bz2
```

### Build fails with "unsupported platform"

Check your architecture is supported in `build-all.sh`. The supported platforms are defined in the platform lists (X86_64_PLATFORMS, X86_PLATFORMS, ARM_PLATFORMS, etc.).

### Cross-compilation Notes

- **ARM**: Cross-compiling to ARM is not reliably supported due to GMP's configure tests. Build natively on ARM hardware.
- **Windows**: Install MinGW-w64 (`mingw-w64` package) to cross-compile Windows binaries.
- **zen4/zen5**: Not supported by GMP 6.3.0. Zen5 also has known power/heat issues with GMP per gmplib.org. Use zen3 or none.

## Cleaning Build Artifacts

To clean all build artifacts:

```bash
# Clean GMP source and build directories
cd core/c/jbigi
rm -rf bin/ lib/ gmp-6.3.0/
```

Or use the project's ant targets:
```bash
ant distclean
```

Note: Pre-built binaries in `installer/lib/jbigi/` are tracked in git and should not be removed.

## Notes

- Pre-built libraries for many architectures are already included in `installer/lib/jbigi/`
- Only rebuild if you need performance improvements for a specific CPU architecture
- The `none` and `none_64` builds are generic fallback options
