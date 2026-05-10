# Building with Visual Studio

## Prerequisites

- Visual Studio 2022 or later
- JDK 17 or later (set `JAVA_HOME` environment variable)

## Building

1. Open `jcpuid.sln` in Visual Studio.
2. Select platform: **Win32 (x86)** or **x64**.
3. Build > Build Solution.

## Output

| Platform | Output file                                                       |
| -------- | ----------------------------------------------------------------- |
| Win32    | `../lib/freenet/support/CPUInformation/jcpuid-x86-windows.dll`    |
| x64      | `../lib/freenet/support/CPUInformation/jcpuid-x86_64-windows.dll` |

## Note

MSVC may not preserve the casing of the output folder path. If the folder appears as `cpuinformation` instead of `CPUInformation`, rename it before packaging to ensure the router can load the library.

## Alternative

MinGW cross-compilation from Linux is also supported via the main build script (`./build.sh`).
