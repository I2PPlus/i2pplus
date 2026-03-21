# Wrapper Update Instructions

This directory contains the Tanuki Java Service Wrapper binaries for all supported platforms.
The wrapper version is currently **3.6.4**.

## Sources

- **Delta Pack**: http://wrapper.tanukisoftware.com/doc/english/download.jsp
- **Source Code**: http://wrapper.tanukisoftware.com/downloads/

## Updating Wrapper Binaries

### 1. wrapper.jar

Copy `lib/wrapper.jar` from the delta pack to:

- `all/wrapper.jar` (used by most installers)
- `win-all/wrapper.jar` (used by Windows installer)

### 2. Native Libraries (.so, .jnilib, .dll)

From the delta pack's `lib/` directory, strip binaries (if tools are available) and copy to the appropriate directory:

| Platform        | Directory      | File                                                              |
| --------------- | -------------- | ----------------------------------------------------------------- |
| FreeBSD x86     | freebsd/       | libwrapper.so                                                     |
| FreeBSD x86-64  | freebsd64/     | libwrapper.so                                                     |
| FreeBSD ARM64   | freebsd-arm64/ | libwrapper.so                                                     |
| Linux x86       | linux/         | libwrapper.so                                                     |
| Linux x86-64    | linux64/       | libwrapper.so                                                     |
| Linux ARM64     | linux64-armv8/ | libwrapper.so                                                     |
| Linux ARM v5    | linux-armv5/   | libwrapper.so                                                     |
| Linux ARM v7    | linux-armv7/   | libwrapper.so                                                     |
| macOS Universal | macosx/        | libwrapper-macosx-universal-64.jnilib                             |
| macOS ARM64     | macosx-arm64/  | libwrapper-macosx-arm-64.dylib (JNI only, no service executable)  |
| Windows x86     | win32/         | wrapper.dll                                                       |
| Windows x86-64  | win64/         | wrapper.dll                                                       |

### 3. Executables (i2psvc, wrapper.exe)

From the delta pack's `bin/` directory, strip binaries (if tools are available) and copy to the appropriate directory:

| Platform          | Directory            | File                                                             |
| ----------------- | -------------------- | ---------------------------------------------------------------- |
| FreeBSD x86       | freebsd/             | i2psvc                                                           |
| FreeBSD x86-64    | freebsd64/           | i2psvc                                                           |
| FreeBSD ARM64     | freebsd-arm64/       | i2psvc                                                           |
| Linux x86         | linux/               | i2psvc                                                           |
| Linux x86-64      | linux64/             | i2psvc                                                           |
| Linux ARM64       | linux64-armv8/       | i2psvc                                                           |
| Linux ARM v5      | linux-armv5/         | i2psvc                                                           |
| Linux ARM v7      | linux-armv7/         | i2psvc                                                           |
| macOS Universal   | macosx/              | i2psvc-macosx-universal-64                                       |
| Windows x86       | win32/               | I2Psvc.exe                                                       |
| Windows x86-64    | win64/               | I2Psvc.exe                                                       |

## Building from Source

### Linux/ARM (armv6)

Build from source following instructions in `linux-armv5/README.txt`.

### macOS

Combine (if possible) the `universal-32` and `universal-64` files from the delta pack into a "quad-fat" binary.
See `macosx/README.txt` for instructions.

### Windows (x86 and x86-64)

You can cross-compile Windows binaries from Linux using the build script:

```bash
# Install dependencies
sudo apt install mingw-w64 binutils-mingw-w64-x86-64 gcc-mingw-w64-x86-64 git

# Download wrapper source from https://wrapper.tanukisoftware.com/downloads/
# Extract to /path/to/wrapper_3.6.4_src

# Build Windows x86-64 binaries (exe + dll)
bash scripts/build-wrapper-windows.sh /path/to/wrapper_3.6.4_src --arch x64

# Build Windows x86 binaries
bash scripts/build-wrapper-windows.sh /path/to/wrapper_3.6.4_src --arch win32

# Clean build artifacts
bash scripts/build-wrapper-windows.sh /path/to/wrapper_3.6.4_src --clean
```

The script automatically:
- Downloads JNI headers for DLL compilation
- Applies SEH patches for mingw compatibility
- Builds both I2Psvc.exe and wrapper.dll
- Strips binaries for smaller size
- Installs to the correct `win64/` or `win32/` directory

After building, delete `win-all/wrapper.jar` and update build.xml to use `all/wrapper.jar` for the Windows installer.

## Incremental Updates

The native wrapper binaries (i2psvc, wrapper.dll, libwrapper.so) are **not** included in incremental updates (`ant updater`).
They are only distributed via full installers (`ant pkg`).

To update wrapper binaries on an existing installation:
1. Stop the I2P service
2. Copy the new files to the installation directory:
   - Copy `libwrapper.so` or `wrapper.dll` to `lib/`
   - Copy `i2psvc` or `I2Psvc.exe` to the root installation directory
   - Copy `wrapper.jar` to `lib/`
3. Restart the service
