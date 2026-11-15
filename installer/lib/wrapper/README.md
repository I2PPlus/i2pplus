# Tanuki Java Service Wrapper

This directory contains the Tanuki Java Service Wrapper binaries for all supported platforms.
The wrapper version is managed via `version.txt` (currently **3.7.3**), and
`README.md` names the same version. `sync-wrapper-version.sh` keeps the two in
step; `update-wrapper.sh` and `build-wrapper-win64.sh` run it for you.

## Automated Updates

### ant updateWrapper

The easiest way to update all wrapper binaries:

```bash
ant updateWrapper
```

This will:
1. Download the delta pack and update Unix/Linux/FreeBSD/macOS binaries
2. Download source and cross-compile Windows x64 binaries
3. Automatically remove obsolete platforms not in the current delta pack

### Manual Scripts

#### update-wrapper.sh

Downloads delta pack and updates Unix binaries:

```bash
./update-wrapper.sh              # Update using version.txt
./update-wrapper.sh --version 3.6.5  # Specific version
```

#### build-wrapper-win64.sh

Cross-compiles Windows x64 binaries using mingw-w64.

Requirements:
```bash
sudo apt install mingw-w64
```

```bash
./build-wrapper-win64.sh         # Build using version.txt
./build-wrapper-win64.sh --version 3.6.5  # Specific version
```

#### sync-wrapper-version.sh

Keeps `version.txt` and the version line at the top of this file in step with
the version being installed. Both update scripts run it, including when they
find nothing to do, so a bump cannot leave either file naming an older release.

```bash
./sync-wrapper-version.sh --check           # Report drift, change nothing
./sync-wrapper-version.sh                   # Use the version in all/wrapper.jar
./sync-wrapper-version.sh --version 3.7.3   # Set both files to one version
```

## Supported Platforms

### Native Libraries

| Platform          | Directory        | File                                   |
| ----------------- | ---------------- | -------------------------------------- |
| FreeBSD x86-64    | freebsd64/       | libwrapper.so                          |
| FreeBSD ARM64     | freebsd-arm64/   | libwrapper.so                          |
| Linux x86-64      | linux64/         | libwrapper.so                          |
| Linux ARM64       | linux64-armv8/   | libwrapper.so                          |
| macOS Universal   | macosx/          | libwrapper-macosx-universal-64.jnilib  |
| macOS ARM64       | macosx-arm64/    | libwrapper-macosx-arm-64.dylib         |
| Windows x86-64    | win64/           | wrapper.dll                            |

> Tanuki ships no 64-bit Windows binaries; win64/I2Psvc.exe and win64/wrapper.dll
> are cross-compiled from source by `build-wrapper-win64.sh`. The JNI wrapper.dll
> is required by WrapperManager (service integration) and must match the exe version.
> `jni-win64/jni_md.h` is the JNI platform header that build compiles against and
> is not shipped in the package.

### Executables

| Platform          | Directory        | File                                   |
| ----------------- | ---------------- | -------------------------------------- |
| FreeBSD x86-64    | freebsd64/       | i2psvc                                 |
| FreeBSD ARM64     | freebsd-arm64/   | i2psvc                                 |
| Linux x86-64      | linux64/         | i2psvc                                 |
| Linux ARM64       | linux64-armv8/   | i2psvc                                 |
| macOS Universal   | macosx/          | i2psvc-macosx-universal-64             |
| macOS ARM64       | macosx-arm64/    | i2psvc-macosx-arm-64                   |
| Windows x86-64    | win64/           | I2Psvc.exe                             |

## Building Other Platforms from Source

### FreeBSD

```bash
pkg_add -r apache-ant gmake openjdk7

# 64-bit only
ant -Dbits=64 compile-c-unix

strip --strip-unneeded bin/wrapper lib/libwrapper.so
chmod 644 bin/wrapper lib/libwrapper.so
# Rename "wrapper" to "i2psvc"
```

### Linux/ARM

ARM64 comes from the delta pack: `update-wrapper.sh` installs it into
`linux64-armv8/`. The 32-bit ARM trees (`linux-armv5/`, `linux-armv7/`) were
dropped, and the update script deletes them if they reappear.

### macOS

```bash
# Create universal binary
lipo -create wrapper-macosx-universal-64 -output i2psvc
lipo -create libwrapper-macosx-universal-64.jnilib -output libwrapper.jnilib

strip i2psvc
```

## Notes

- Native wrapper binaries are **not** included in incremental updates (`ant updater`).
- They are only distributed via full installers (`ant pkg`).
- Cache location: `installer/lib/wrapper/cache/`
- Use `version.txt` to manage the wrapper version.
