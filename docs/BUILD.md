# Building I2P+ for Developers

This document is for developers building, hacking on, or packaging I2P+.
End users looking to install I2P+ should see [INSTALL.md](INSTALL.md) instead.

## Two build systems

I2P+ ships with **two** build sub-systems, both maintained side by side:

| System     | Primary use                                                                   |
| ---------- | ----------------------------------------------------------------------------- |
| **Ant**    | The release path: components, installers, updaters, packaging, signing, jbigi |
| **Gradle** | Per-module development builds, IDE import, test tooling                       |

Both write their output outside the workspace: `${java.io.tmpdir}/build-i2p`
(the `build.root` convention), mirroring each other. Final artifacts land in
`./dist`.

The **Ant build is authoritative** for releases; the Gradle build exists for
convenience and does not produce installers.

## Prerequisites

| Requirement      | Details                                                               |
| ---------------- | --------------------------------------------------------------------- |
| **Java SDK**     | 8 or higher to compile (class files target 1.8)                       |
| **Apache Ant**   | 1.9.8 or higher                                                       |
| **GNU gettext**  | `xgettext`, `msgfmt`, and `msgmerge` must be on your PATH             |
| **Locale**       | Build environment must use a UTF-8 locale                             |
| **JDK 14+**      | Only for jpackage targets (see below); pin LTS 21 via `jpackage.home` |

Java 17 will become the minimum after the Jetty 12 migration.

For JVM compatibility details, see https://i2pplus.github.io/i2pplus

### The JDK split

- Compilation targets Java 8 bytecode; ant itself may run on any newer JDK.
- `jpackage` (JDK 14+) must run on a separate, newer JDK. The default is
  `/usr/lib/jvm/java-21-openjdk-amd64`; override in `override.properties`:

  ```properties
  jpackage.home=/path/to/jdk21
  ```

- jpackage cannot cross-compile: `jpackage-win` must run on a Windows host.

### Optional tooling

- **MinGW** — only for `buildJbigi-win64` (Windows DLLs).
- **fakeroot / dpkg-deb** — only for `buildDeb`.
- **launch4j** — bundled; used by `installerexe` (IzPack4 Windows wrapper).

## Configuration

- `build.properties` — build settings; `release.number`, `i2p.build.number`.
- `override.properties` — machine-local overrides (JDK paths, keys); never
  commit machine-specific values.
- `ant help` prints the full curated target list with outputs; the sections
  below are the highlights.

## Ant build

### Components

| Target                                                                                                                                                                                                                           | Output                                                               |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `build`                                                                                                                                                                                                                          | All `.jar` and `.war` components                                     |
| `buildCore`                                                                                                                                                                                                                      | `./build/i2p.jar`                                                    |
| `buildRouter`                                                                                                                                                                                                                    | `./build/router.jar`                                                 |
| `buildRouterConsole`, `buildWEB`                                                                                                                                                                                                 | Console webapp jars/wars                                             |
| `buildJetty`                                                                                                                                                                                                                     | Jetty-related jars                                                   |
| `buildJbigi`                                                                                                                                                                                                                     | Native jbigi libs (Win64 + Linux64 + ARM64) → `installer/lib/jbigi/` |
| `buildAddressbook`, `buildI2PSnark`, `buildI2PTunnel`, `buildSAM`, `buildStreaming`, `buildSusiMail`, `buildSusiDNS`, `buildDesktopGui`, `buildI2PControl`, `buildImagegen`, `buildJrobin`, `buildMinistreaming`, `buildPack200` | Per-app jars/wars                                                    |

### Installers

| Target                                                                             | Output                                                                                                     |
| ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `pkg`                                                                              | `distclean` then everything: updater + installer                                                           |
| `installer` / `installer5`                                                         | Cross-platform IzPack GUI installer → `dist/i2pinstall_<ver>.jar`, wrapped `dist/i2pinstall.exe`           |
| `installer-windows` / `installer5-windows`                                         | Windows GUI installer → `dist/i2pinstall_<ver>_windows.exe`                                                |
| `installer-osx`                                                                    | macOS installer → `dist/i2pinstall_<ver>_osx.tar.bz2`                                                      |
| `installer-linux` / `installer5-linux`, `installer-freebsd`, `installer-nowindows` | Platform GUI installers                                                                                    |
| `jpackage-win`                                                                     | Self-contained Windows exe (bundled runtime, JDK 14+, must run on Windows) → `dist/i2pinstaller-<ver>.exe` |
| `7zip-*`, `zip-*`                                                                  | Non-installer platform archives → `dist/`                                                                  |

### Updaters

| Target                                                                                                            | Output                                       |
| ----------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `updater`                                                                                                         | `dist/i2pupdate.zip`                         |
| `updaterWithJetty`, `updaterWithGeoIP`, `updaterWithASN`, `updaterWithJbigi`, `updaterSmall`, `updaterCompact`, … | Updater variants                             |
| `signed-updater200*`                                                                                              | Signed `.su3` updates → `dist/i2pupdate.su3` |

### Utilities & distribution

| Target                                             | Purpose                                         |
| -------------------------------------------------- | ----------------------------------------------- |
| `test`, `junit.test`                               | Unit tests                                      |
| `distclean`                                        | Remove all derived/temporary files              |
| `buildProperties`, `buildRequirements`             | Show build configuration and requirements       |
| `tarball`                                          | Clean-install tar → `dist/i2p.tar.bz2`          |
| `buildDeb`                                         | Self-contained Debian package → `dist/i2p*.deb` |
| `buildAppImage`                                    | Linux AppImage → `dist/I2P+_<ver>.appimage`     |
| `git-tag`                                          | Create annotated release tag                    |
| `javadoc`, `javadoc-zip`, `devdocs`, `devdocs-zip` | Documentation                                   |
| `poupdate-source`                                  | Update translation `.po` files                  |

## Gradle build

The Gradle build mirrors the ant module layout; each module has its own
`build.gradle`:

```
core/  router/  installer/  apps/{ministreaming,streaming,i2ptunnel,jetty,i2psnark,systray,sam,routerconsole,desktopgui,jrobin,pack200,addressbook,susidns,susimail,i2pcontrol,imagegen}
```

Typical workflow (from the repo root):

```sh
./gradlew tasks                # list tasks
./gradlew :core:build          # build the core module
./gradlew :router:build        # build the router module
./gradlew codeCoverageReport   # Jacoco aggregate report
```

Gradle build output goes to `${java.io.tmpdir}/build-i2p/gradle/<module>` so
the workspace stays clean (matching the ant convention).

## Build output verbosity

The default ant output is verbose. A custom logger can be installed once to
condense it:

```sh
ant install-buildtools
```

This installs `ConciseLogger` into `~/.ant/lib` and activates it via
`~/.antrc` (`ANT_ARGS="-logger net.i2p.router.build.ConciseLogger"`).

## Native crypto libraries (jbigi)

jbigi provides hardware-optimised modular exponentiation via GMP. Pre-built
binaries for common architectures are included in the installer. To rebuild
for a specific CPU, see [`core/c/jbigi/README.md`](../core/c/jbigi/README.md)
or use `ant buildJbigi`.

## Cross-references

- [INSTALL.md](INSTALL.md) — end-user installation, running, and services
- [HACKING.md](HACKING.md) — code conventions and workflow
- [DIRECTORIES.md](DIRECTORIES.md) — repository layout