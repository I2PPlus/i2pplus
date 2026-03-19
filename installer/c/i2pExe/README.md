# Windows Native Launcher (`c/i2pExe/`)

C source code for the Windows native I2P launcher. Provides a standalone Windows executable that locates a JVM and launches the I2P router, handling DLL loading and error reporting.

## Files

- `i2p.c` - Main launcher entry point
- `java.c` / `java.h` - JNI-based JVM launcher
- `java_md.c` / `java_md.h` - Machine-dependent platform code
- `jni.h` / `jni_md.h` - JNI headers (vendored)
- `I2P.rc` / `resource.h` - Windows resource definitions
- `I2P.sln` / `I2P.vcproj` - Visual Studio project files
- `errors.h` - Error code definitions

## Building

Open `I2P.sln` in Visual Studio and build for the target platform. The output is used by Launch4j via `../lib/izpack/i2pinstaller.xml` and `../i2pstandalone.xml`.
