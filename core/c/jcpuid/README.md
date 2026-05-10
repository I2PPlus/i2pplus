# JCPUID - Native CPU Feature Detection

JCPUID is a JNI library that detects CPU features at runtime (AES-NI, etc.) so the router can select the optimal cryptographic implementation.

## Building

### Linux / macOS

```bash
cd core/c/jcpuid
./build.sh
```

By default, builds for the current machine's architecture. Use `BITS=32` or `BITS=64` to override.

### Windows (Visual Studio)

See [msvc/README.md](msvc/README.md) for Visual Studio build instructions.

## Output

| Platform | Library                             |
| -------- | ----------------------------------- |
| Linux    | `libjcpuid-{arch}-linux.so`         |
| macOS    | `libjcpuid-{arch}-osx.jnilib`       |
| Windows  | `jcpuid-{arch}-windows.dll`         |

The router automatically selects the correct library at runtime based on detected CPU features.

## JBIGI

For building the JBIGI native BigInteger library, see [jbigi/README.md](../jbigi/README.md).
