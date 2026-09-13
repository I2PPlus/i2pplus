# JBIGI Native Libraries

> Note: For the latest information, see:
> - `history.txt` for changelog entries
> - `core/c/jbigi/README.md` for build instructions
> - `NativeBigInteger.java` and `CPUID.java` for technical details

---

## What is JBIGI?

JBIGI provides native JNI bindings for the **GNU Multiple Precision Arithmetic Library (GMP)** to accelerate cryptographic operations in I2P, including:

- Modular exponentiation (`modPow`)
- Constant-time modular exponentiation (`modPowCT`) - resistant to timing attacks
- Modular multiplicative inverse (`modInverse`)

---

## Current Version (March 2026)

| Component   | Version   |
| ----------- | --------- |
| GMP         | 6.3.0     |
| JBIGI       | 4         |

The JBIGI version can be queried at runtime via `nativeJbigiVersion()`.

### New in This Release

- Added `libjbigi-linux-none_64.so` - Generic 64-bit Linux build (GMP 6.3.0)
- Added `jbigi-windows-none_64.dll` - Generic 64-bit Windows build (GMP 6.3.0)
- Purged all 32-bit support: Windows, Linux, FreeBSD, ARM 32-bit, and PowerPC
  binaries removed; only 64-bit (x86_64 / ARM64) builds ship
- Note: zen4/zen5 support requires GMP 6.4+ or native hardware for building

---

## Supported Architectures

The product is **64-bit only**. All binaries carry a `_64` suffix (for jbigi) or
`x86_64` (for jcpuid).

### Linux (x86_64 64-bit)
```
none  core2  corei  coreisbr  coreihwl  coreibwl
athlon64  k10  bobcat  jaguar  bulldozer  piledriver  steamroller  excavator
atom  nano  pentium4  zen  zen2  zen3  skylake  silvermont  goldmont
```

### Linux (ARM64 / aarch64)
```
armv8  armv8.2  cortex-a72  cortex-a76
```

### macOS (x86_64)
```
none  core2  corei  coreisbr  coreihwl  armv8
```

### Windows (x86_64)
Same architectures as Linux (compiled via MinGW cross-compilation)

### FreeBSD (x86_64)
```
none  atom  nano  k10  pentium4  core2  corei  coreisbr  coreihwl
bobcat  bulldozer  piledriver
```

---

## Build History

### 2026 - March
- Upgraded to GMP 6.3.0
- JBIGI version 4: Added version reporting functions (nativeJbigiVersion, nativeGMP*Version)
- Added nativeModPowCT (constant-time modular exponentiation)
- Added nativeModInverse
- Code cleanup: removed dead code, fixed convert_mp2j() off-by-one error
- Added ARMv8/aarch64 support

### 2016 - April
- Upgraded to GMP 6.0.0a
- Removed: NetBSD, kFreeBSD, Solaris, OpenBSD
- Added: coreihwl, coreisbr, bulldozer, steamroller, cortex-a9, cortex-a15

### 2011 - May/June
- **jcpuid**: Updated for `-fPIC` compatibility
- **jbigi**:
  - Removed k63 (falls back to k62)
  - 32-bit: GMP 4.3.2
  - 64-bit: GMP 5.0.2
  - Added MinGW cross-compiled Windows 64-bit binaries

### 2006 - February
- Added `libjbigi-linux-viac3.so`
- Created `jbigi-win-athlon64.dll`

### 2005 - December
- Updated `libjcpuid-x86-linux.so` to pure C (removed libg++.so.5 dependency)

### 2005 - September
- Added `libjbigi-linux-athlon64.so`

### 2004 - August
- Initial release with GMP 4.1.3
- Platforms: Linux, Windows (MinGW), FreeBSD 4.8

---

## Rebuilding from Source

To rebuild jbigi for your specific architecture:

```bash
# Linux 64-bit
cd core/c/jbigi
./build-linux64.sh -a

# Windows 64-bit (cross-compile with MinGW)
./build-win64.sh -a

# ARM64 (cross-compile with aarch64-linux-gnu-gcc)
./build-arm64.sh -a
```

For more options, see `core/c/jbigi/README.md`.

---

## File Naming Convention

The library naming follows `{lib}jbigi-{OS}-{CPU}.{ext}`:

| OS        | Prefix     | Extension   |
| --------- | ---------- | ----------- |
| Linux     | libjbigi   | .so         |
| FreeBSD   | libjbigi   | .so         |
| macOS     | libjbigi   | .jnilib     |
| Windows   | jbigi      | .dll        |

The `CPUID` library at runtime selects the optimal binary for your CPU based on detected features.
