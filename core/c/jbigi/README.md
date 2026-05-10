# JBIGI - Native BigInteger Acceleration Library

## Overview

JBIGI is a native JNI library wrapping GNU Multiple Precision Arithmetic Library (GMP) to accelerate cryptographic operations (modPow, modPowCT, modInverse) in I2P.

- **JBIGI Version**: 5 (reported by `nativeJbigiVersion()`)
- **GMP Version**: 6.3.0

## Requirements

- GCC (or MinGW on Windows)
- `m4`, `make`
- GMP source (downloaded automatically by `download_gmp.sh`)
- JDK with JNI headers

### Dependencies by Distro

| Distro                          | Native build                                              | Windows cross-compile                              |
| ------------------------------- | --------------------------------------------------------- | -------------------------------------------------- |
| Debian/Ubuntu/Mint/Pop!_OS      | `sudo apt install build-essential m4`                     | `sudo apt install gcc-mingw-w64-x86-64`            |
| Fedora/RHEL/CentOS/AlmaLinux    | `sudo dnf install gcc make m4`                            | `sudo dnf install mingw64-gcc`                     |
| Arch/Manjaro/EndeavourOS        | `sudo pacman -S base-devel m4`                            | `sudo pacman -S mingw-w64-gcc`                     |
| openSUSE/SLES                   | `sudo zypper install gcc make m4`                         | `sudo zypper install cross-x86_64-w64-mingw32-gcc` |
| Gentoo                          | `sudo emerge sys-devel/gcc sys-devel/make sys-devel/m4`   | `sudo emerge cross-x86_64-w64-mingw32/gcc`         |
| macOS                           | `brew install gcc m4`                                     | —                                                  |

For ARM64 cross-compilation on x86_64: `sudo apt install gcc-aarch64-linux-gnu` (or distro equivalent).

## Building

### Platform Scripts (Recommended)

Each script detects supported CPU targets, compiles optimized libraries, and strips binaries.

```
core/c/jbigi/
  build-win64.sh    # Windows 64-bit (cross-compile from Linux)
  build-linux64.sh  # Linux 64-bit (native)
  build-arm64.sh    # ARM 64-bit (cross-compile from x86_64)
```

#### Usage

| Flag              | Description                         |
| ----------------- | ----------------------------------- |
| `-a`, `--all`     | Build for all supported CPU targets |
| `-g`, `--generic` | Build generic library only          |
| `-h`, `--help`    | Show help message                   |

```bash
cd core/c/jbigi

./build-win64.sh -a         # 13 CPU targets → jbigi-windows-{cpu}_64.dll
./build-linux64.sh -a       # 13 CPU targets → libjbigi-linux-{cpu}_64.so
./build-arm64.sh -a         #  6 CPU targets → libjbigi-linux-{cpu}_64.so

./build-win64.sh --generic  # Generic only (faster)
```

### Ant Targets

Run from project root to build native libraries:

```bash
# Build native libraries (copies to installer/lib/jbigi/)
ant buildJbigi          # All three (parallel): Win64 + Linux64 + ARM64
ant buildJbigi-win64    # Windows 64-bit DLLs
ant buildJbigi-linux64  # Linux 64-bit .so files
ant buildJbigi-arm64    # ARM 64-bit .so files

# List / test
ant listJbigi           # List built libraries in installer/lib/jbigi/
ant testJbigi           # Benchmark native vs Java (modPow/modInverse)
```

### ARM Native Build

Cross-compile using the ARM64 build script:

```bash
cd core/c/jbigi
./build-arm64.sh -a        # All CPU targets
./build-arm64.sh -g        # Generic only
```

---

## Testing

```bash
ant testJbigi    # Benchmark all I2P+ crypto schemes
```

Benchmarks three schemes — ElGamal (jbigi-eligible), EdDSA and X25519 (jbigi-independent). Verifies native vs Java correctness on every ElGamal iteration.

| Scheme          | Operations tested            | jbigi effect                   |
| --------------- | ---------------------------- | ------------------------------ |
| ElGamal         | modPow, modPowCT, modInverse | modInverse **~6x faster**      |
| EdDSA (Ed25519) | sign, verify                 | None — uses radix-2^25.5 limbs |
| ECIES (X25519)  | key agreement                | None — JDK native (JEP 324)    |

## Output

Libraries are placed in `lib/net/i2p/util/` or `installer/lib/jbigi/`:

| Platform  | Pattern                                    |
| --------- | ------------------------------------------ |
| Linux     | `libjbigi-linux-{cpu}_64.so`               |
| Windows   | `jbigi-windows-{cpu}_64.dll`               |
| FreeBSD   | `libjbigi-freebsd-{cpu}.so`                |
| macOS     | `libjbigi-osx-{cpu}.jnilib`                |

Binaries are stripped automatically.

## Supported CPU Targets

### x86_64 (13 targets)
zen3, zen2, zen, skylake, coreisbr (Broadwell), coreihwl (Haswell), bulldozer, piledriver, steamroller, excavator, bobcat, atom, none

### ARM64 (6 targets)
armv8, armv8.2, cortex-a72, cortex-a76, cortex-a53, none

## GCC 15+ Compatibility

GMP 6.3.0 fails with GCC 15+ due to stricter C99 compliance (`void g()` no longer treated as taking no arguments). The fix is applied automatically by `download_gmp.sh` via `patches/gcc15-fix.diff`.

## Known Limitations

- **zen4/zen5**: Not supported by GMP 6.3.0. Zen5 has power/heat issues per gmplib.org. Use zen3 or `none`.
- **ARM cross-compile**: GMP configure tests can fail. Build natively on ARM if possible.

## Troubleshooting

**"Cannot find jni.h"** — Set `JAVA_HOME`: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`

**"GMP download failed"** — Manual download: `wget https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.bz2` in `core/c/jbigi/`

**"Couldn't download binary-armhf/Packages"** — Use Docker with Ubuntu 25.04 (Plucky) or later.

## Notes

- Pre-built libraries are included in `installer/lib/jbigi/` — only rebuild for specific CPU optimizations.
- The `none`/`none_64` builds are generic fallback options.
- Ant `distclean` does not remove pre-built binaries (tracked in git).

## Version History

- **1** (0.8.7): Original with nativeModPow() and nativeDoubleValue()
- **2** (0.8.7): Removed nativeDoubleValue()
- **3** (0.9.26): Added nativeJbigiVersion(), nativeGMPVersion(), nativeModInverse(), nativeModPowCT(), negative base support, ArithmeticException on bad args
- **4** (0.9.27): Fixed GMP version functions for shared library builds
- **5** (0.9.69): JNI null/empty array validation, GMP version parser (GMP 10+), convert_mp2j loop fix, removed dead code (nativeNeg), cached exception classes + thread-safe init, JNI_OnUnload cleanup, convert_j2mp return value for error handling
