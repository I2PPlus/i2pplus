# Native Libraries (JCPUID & JBIGI)

This directory contains build scripts for I2P's optional native cryptographic libraries, which provide ~6x faster modInverse performance compared to pure Java.

For detailed JBIGI build instructions, see [jbigi/README.md](jbigi/README.md).

**Requirements:**
- Java SDK (not just JRE)
- GMP library headers (`libgmp-dev` or equivalent)
- Standard build tools (`gcc`, `make`, `m4`, `tar`)

---

## Building with Ant (Recommended)

```bash
ant buildJbigi         # All: Windows 64-bit + Linux 64-bit + ARM64
ant buildJbigi-win64   # Windows 64-bit DLLs (MinGW cross-compile)
ant buildJbigi-linux64 # Linux 64-bit .so files
ant buildJbigi-arm64   # ARM 64-bit .so files

ant testJbigi          # Benchmark native vs Java (modPow/modInverse)
ant listJbigi          # List built libraries
```

Libraries are copied to `installer/lib/jbigi/`.

---

## Building with Scripts

Each script detects supported CPU targets, compiles optimized libraries, and strips binaries.

```bash
cd core/c/jbigi

# Build for all supported CPU targets
./build-linux64.sh -a       # Linux 64-bit (native)
./build-win64.sh -a         # Windows 64-bit (MinGW cross-compile)
./build-arm64.sh -a         # ARM 64-bit (cross-compile)

# Generic build only (faster)
./build-linux64.sh --generic
```

---

## Installation & Testing

1. Copy the native library to your I2P directory:

| Platform | Library                  | Location |
| -------- | ------------------------ | -------- |
| Linux    | `libjbigi-linux-*_64.so` | `$I2P/`  |
| Windows  | `jbigi-windows-*_64.dll` | `%I2P%\` |
| macOS    | `libjbigi-osx-*.jnilib`  | `$I2P/`  |

2. Copy jcpuid library:

| Platform | Library                   | Destination             |
| -------- | ------------------------- | ----------------------- |
| Linux    | `libjcpuid-*_64-linux.so` | `$I2P/libjcpuid.so`     |
| Windows  | `jcpuid-windows*.dll`     | `%I2P%\jcpuid.dll`      |
| macOS    | `libjcpuid-osx*.jnilib`   | `$I2P/libjcpuid.jnilib` |

3. Run benchmark:

```bash
ant testJbigi
```

Look for output showing native is ~10x faster than pure Java for modInverse. If not, the library may not have loaded correctly.

---

## Notes

- If no native library is available for your platform, I2P falls back to pure Java BigInteger.
- The standard I2P installation includes pre-built libraries for common platforms in `jbigi.jar`.
- Check the router console logs at startup for "jbigi loaded successfully" confirmation.
- Pre-built libraries are included in `installer/lib/jbigi/` — only rebuild for specific CPU optimizations.
