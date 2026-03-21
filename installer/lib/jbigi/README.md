# JBIGI Native Libraries

Native JNI bindings for the GNU Multiple Precision Arithmetic Library (GMP)
that accelerate cryptographic operations in I2P+:

- Modular exponentiation (`modPow`)
- Constant-time modular exponentiation (`modPowCT`) — resistant to timing attacks
- Modular multiplicative inverse (`modInverse`)

Versions: GMP 6.3.0, JBIGI 4. Query at runtime via `nativeJbigiVersion()`.

## Build

```sh
# All platforms (runs win64, linux64, arm64 in parallel)
ant buildJbigi

# Single platform (ant targets)
ant buildJbigi-linux64
ant buildJbigi-win64
ant buildJbigi-arm64

# Single platform (direct)
cd core/c/jbigi
./build-linux64.sh -a
./build-win64.sh -a
./build-arm64.sh -a

# List built binaries
ant listJbigi
./list-jbigi.sh
```

See `core/c/jbigi/docs/README.md` for full build instructions.

## Architecture Targets

### Linux x86_64 (20 targets)
`none` `athlon64` `k10` `bobcat` `jaguar` `bulldozer` `piledriver` `steamroller` `excavator` `core2` `coreisbr` `coreihwl` `coreibwl` `skylake` `zen` `zen2` `zen3` `atom` `silvermont` `goldmont`

### Linux ARM64 (6 targets)
`armv8` `armv8.2` `cortex-a53` `cortex-a72` `cortex-a76` `none`

### Windows x86_64 (20 targets)
Same as Linux x86_64 (MinGW cross-compiled)

### macOS (6 targets)
`core2` `corei` `coreisbr` `coreihwl` `coreibwl` `none`

### FreeBSD, Linux x86 (32-bit)
Legacy targets from earlier builds, not rebuilt by current scripts.

## File Naming

`{lib}jbigi-{OS}-{CPU}[_64].{ext}`

| OS          | Prefix       | Extension   | Example                            |
| ----------- | ------------ | ----------- | ---------------------------------- |
| Linux       | libjbigi     | .so         | libjbigi-linux-skylake_64.so       |
| Windows     | jbigi        | .dll        | jbigi-windows-zen3_64.dll          |
| macOS       | libjbigi     | .jnilib     | libjbigi-osx-coreihwl_64.jnilib    |
| FreeBSD     | libjbigi     | .so         | libjbigi-freebsd-core2_64.so       |

The CPUID library at runtime selects the optimal binary for your CPU.

## History

| Date         | GMP           | Notes                                                                                        |
| ------------ | ------------- | -------------------------------------------------------------------------------------------- |
| 2026-03      | 6.3.0         | JBIGI 4: version reporting, constant-time modPow, modInverse, ARM64/aarch64, parallel builds |
| 2016-04      | 6.0.0a        | Dropped NetBSD/kFreeBSD/Solaris/OpenBSD, added coreihwl/coreisbr/bulldozer/steamroller       |
| 2011-05      | 4.3.2 / 5.0.2 | jcpuid -fPIC, MinGW 64-bit Windows                                                           |
| 2004-08      | 4.1.3         | Initial release: Linux, Windows (MinGW), FreeBSD                                             |
