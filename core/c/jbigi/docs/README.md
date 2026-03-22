# JBIGI - Native BigInteger Acceleration Library

## Overview

JBIGI is a native JNI library wrapping the GNU Multiple Precision Arithmetic Library (GMP) to accelerate cryptographic operations (modPow, modPowCT, modInverse) in I2P.

- **JBIGI Version**: 4 (as reported by `nativeJbigiVersion()`)
- **GMP Version**: 6.3.0 (configurable in `download_gmp.sh`)

## Requirements

- GCC (or MinGW on Windows)
- `m4`, `make`
- GMP source (downloaded automatically by `download_gmp.sh`)
- JDK with JNI headers (for native builds only — cross-compile scripts bundle their own headers)

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
| *(no args)*       | Show help message                   |

```bash
cd core/c/jbigi

./build-win64.sh -a         # 20 CPU targets → jbigi-windows-{cpu}_64.dll
./build-linux64.sh -a       # 20 CPU targets → libjbigi-linux-{cpu}_64.so
./build-arm64.sh -a         #  5 CPU targets → libjbigi-linux-{cpu}_64.so

./build-win64.sh --generic  # Generic only (faster)
```

#### Ant Targets

Run from project root:

```bash
ant buildJbigi-win64    # Windows 64-bit DLLs → installer/lib/jbigi/
ant buildJbigi-linux64  # Linux 64-bit .so files → installer/lib/jbigi/
ant buildJbigi-arm64    # ARM 64-bit .so files → installer/lib/jbigi/
ant buildJbigi          # All three (parallel) → installer/lib/jbigi/
ant listJbigi           # List built libraries
```

`buildJbigi` runs win64, linux64, and arm64 in parallel, then copies results to `installer/lib/jbigi/`.

### Testing

```bash
ant testJbigi    # Benchmark all I2P+ crypto schemes
```

Benchmarks three schemes — ElGamal (jbigi-eligible), EdDSA and X25519 (jbigi-independent). Verifies native vs Java correctness on every ElGamal iteration.

| Scheme          | Operations tested            | jbigi effect                   |
| --------------- | ---------------------------- | ------------------------------ |
| ElGamal         | modPow, modPowCT, modInverse | modInverse **~6x faster**      |
| EdDSA (Ed25519) | sign, verify                 | None — uses radix-2^25.5 limbs |
| ECIES (X25519)  | key agreement                | None — JDK native (JEP 324)    |

| Path   | Implementation                                        | Method                        |
| ------ | ----------------------------------------------------- | ----------------------------- |
| Native | `NativeBigInteger.modPow()` → JNI → GMP `mpz_powm()`  | Hand-optimized assembly       |
| Java   | `BigInteger.modPow()`                                 | Pure Java (Montgomery ladder) |

Example output:

```
 • Native: libjbigi-linux-zen3_64.so (JBIGI v4, GMP 6.3.0)

 • ElGamal (ElG) — 2048-bit exp, 1060-bit inverse

 modPow (base^exp mod m (2048-bit)), 5000 iterations:
   Native:   5441.1 ms  (1.088 ms/op)
   Java:     5439.1 ms  (1.088 ms/op)
   Result: Native is marginally faster

 modPowCT (constant-time base^exp mod m (2048-bit)), 5000 iterations:
   Native:   7159.1 ms  (1.432 ms/op)
   Java:     5469.8 ms  (1.094 ms/op)
   Result: Native is 1.3x slower

 modInverse (a^-1 mod m (1060-bit)), 5000 iterations:
   Native:     67.8 ms  (0.014 ms/op)
   Java:      428.5 ms  (0.086 ms/op)
   Result: Native is 6.3x faster

 • EdDSA (Ed25519) — curve arithmetic, no BigInteger.modPow

 sign (5000 iterations):
   Result:    191.3 ms  (0.038 ms/op)
 verify (5000 iterations):
   Result:    399.3 ms  (0.080 ms/op)
 sign + verify: 590.7 ms  (0.118 ms/sv)

 • ECIES (X25519) — JDK native KeyAgreement (JEP 324)

 key agreement (10000 iterations):
   Result:      0.6 ms  (0.0001 ms/op)
   Shared secret: 32 bytes
```

### Legacy Build Scripts

```bash
cd core/c/jbigi
./build.sh dynamic     # Links against system GMP (faster, current machine)
./build.sh local       # Statically links GMP (portable, recommended)
./build.sh all         # Multi-architecture build
BITS=32 ./build.sh     # 32-bit on 64-bit system
```

See `build-all.sh` for the full list of supported platforms.

## Output

Libraries are placed in `lib/net/i2p/util/`:

| Platform  | Pattern                                    |
| --------- | ------------------------------------------ |
| Linux     | `libjbigi-linux-{cpu}_64.so`               |
| Windows   | `jbigi-windows-{cpu}_64.dll`               |
| FreeBSD   | `libjbigi-freebsd-{cpu}.so`                |
| macOS     | `libjbigi-osx-{cpu}.jnilib`                |

Binaries are stripped automatically.

## Build Scripts

| Script             | Purpose                                |
| ------------------ | -------------------------------------- |
| `build-win64.sh`   | Windows 64-bit cross-compilation       |
| `build-linux64.sh` | Linux 64-bit native compilation        |
| `build-arm64.sh`   | ARM 64-bit cross-compilation           |
| `build-all.sh`     | Full multi-architecture build (legacy) |
| `build.sh`         | Local/all builds (legacy)              |
| `download_gmp.sh`  | Downloads and patches GMP source       |

## GCC 15+ Compatibility

GMP 6.3.0 fails with GCC 15+ due to stricter C99 compliance (`void g()` no longer treated as taking no arguments). The fix is applied automatically by `download_gmp.sh` via `patches/gcc15-fix.diff`.

## Known Limitations

- **zen4/zen5**: Not supported by GMP 6.3.0. Zen5 has power/heat issues per gmplib.org. Use zen3 or `none`.
- **ARM cross-compile**: GMP configure tests can fail. Build natively on ARM if possible.

## Troubleshooting

**"Cannot find jni.h"** — Set `JAVA_HOME`: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`

**"Unable to detect default setting for BITS variable"** — Specify manually: `BITS=64 ./build.sh`

**"GMP download failed"** — Manual download: `wget https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.bz2` in `core/c/jbigi/`

## Notes

- Pre-built libraries are included in `installer/lib/jbigi/` — only rebuild for specific CPU optimizations.
- The `none`/`none_64` builds are generic fallback options.
- Ant `distclean` does not remove pre-built binaries (tracked in git).
