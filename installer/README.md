# I2P Installer (`installer/`)

Builds the I2P+ installer JAR and Windows EXE packages using IzPack (v4 and v5). Also builds the `jbigi.jar` containing native crypto libraries for all platforms.

## Structure

| Directory       | Description                                         |
| --------------- | --------------------------------------------------- |
| `lib/izpack/`   | IzPack installer toolkit (v4, v5, shared resources) |
| `lib/jbigi/`    | Native JBIGI/JCPUID binaries for all platforms      |
| `lib/wrapper/`  | Tanuki Java Service Wrapper binaries                |
| `lib/launch4j/` | Launch4j Windows EXE wrapper                        |
| `resources/`    | Files bundled into the installed I2P package        |
| `java/`         | Installer Java source                               |
| `tools/`        | Build-time utilities                                |

## Installer Builds

```bash
# IzPack 4 installer (compiler bundled in lib/izpack/4)
ant installer

# IzPack 5 installer (compiler downloaded and cached on first use)
ant installer5
```

IzPack 5 is not bundled: it is fetched into `lib/izpack/5/izpack/` by
`lib/izpack/5/download-izpack5.sh`, which every `installer5*` target runs first
and which keeps the cached copy on the latest upstream release. See
[lib/izpack/README.md](lib/izpack/README.md).
