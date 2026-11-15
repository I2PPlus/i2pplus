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

The **Ant build is authoritative** for releases; the Gradle build mirrors the
installer targets but is not used for releases.

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

Only needed by the targets listed against each entry.

- **MinGW** — only for `buildJbigi-win64` (Windows DLLs).
- **fakeroot / dpkg-deb** — only for `buildDeb`.
- **launch4j** — bundled; used by `installerexe` (the IzPack 4 Windows wrapper)
  and `buildexe` (the standalone `i2p.exe`).
- **Python 3** — only for `installer5exe`, which drives IzPack's `izpack2exe.py`.
  The script's `#!/usr/bin/env python` shebang does not resolve where only
  `python3` is on `PATH`, so the interpreter is passed explicitly and can be
  overridden with `izpack5.python`.
- **7-Zip** — not needed. `installer5exe` uses the `7za` and `7zS.sfx` bundled
  with the IzPack 5 distribution rather than a system `p7zip`.

### The IzPack 5 compiler

IzPack 4 ships in `installer/lib/izpack/4/standalone-compiler.jar`, so `ant
installer*` needs nothing extra. IzPack 5 is ~95MB of jars and is **downloaded
and cached** instead:

```bash
ant download-izpack5          # or: installer/lib/izpack/5/download-izpack5.sh
```

Every `installer5*` target runs that check first, so the first such build needs
network access and ~95MB of free space in `installer/lib/izpack/5/`. The cache
is gitignored and self-updating: each run compares the installed version against
the latest upstream release and replaces it when they differ. Once cached,
offline builds keep working. See
[installer/lib/izpack/README.md](../installer/lib/izpack/README.md).

## Configuration

- `build.properties` — build settings; `release.number`, `i2p.build.number`.
- `override.properties` — overrides (JDK paths, keys). **It is tracked in git**,
  despite appearing in `.gitignore`, so values written here do get committed.
  Treat anything machine-specific as belonging in one of the gitignored
  per-tool files instead — for IzPack that is
  `installer/lib/izpack/5/izpack5.properties`, which `build.xml` reads ahead of
  `override.properties`.
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

| Target                                                                                                                                 | Output                                       |
| -------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `updater`                                                                                                                              | `dist/i2pupdate.zip`                         |
| `updaterWithJetty`, `updaterWithGeoIP`, `updaterWithASN`, `updaterWithJbigi`, `updaterWithTomcat`, `updaterSmall`, `updaterCompact`, … | Updater variants                             |
| `updater200`, `updater200Compact`, `updater200WithTomcat`, …                                                                           | `dist/i2pupdate200.zip` (pack200-compressed) |
| `signed-updater200*`, `signed-updater200WithTomcat`                                                                                    | Signed `.su3` updates → `dist/i2pupdate.su3` |

Every target that writes a deliverable ends with the `sha256sum` target, which
prints `SHA256: <hash>` for the artifact it just wrote (the producing task names
the file on the line above). `-releaseit` hashes the release set it assembles:
the update zip, the signed `i2pupdate.su3` and its dashed copy, the installer
jar, the windows installer exe, and the source tarball.

#### Translations

Every payload carries the gettext catalogs (`locale/<lang>/LC_MESSAGES/i2prouter.mo`)
except the compact updaters (`updaterCompact`, `updater200Compact`, the
`updaterCompactWith*` family), which exist to be small, and the Windows packages,
whose scripts are the `.bat`/`.ps1` ones that never read them. If `xgettext` and
`msgfmt` are not on your PATH, set `require.gettext=false` in `build.properties`:
the build then prints

```
WARNING: require.gettext=false - no gettext catalogs are compiled, ...
```

and every package built from there ships untranslated. `updater` and
`updaterSmall` also print one line saying how many catalogs they staged.

### Utilities & distribution

| Target                                                                           | Purpose                                         |
| -------------------------------------------------------------------------------- | ----------------------------------------------- |
| `test`, `junit.test`                                                             | Unit tests                                      |
| `distclean`                                                                      | Remove all derived/temporary files              |
| `buildProperties`, `buildRequirements`                                           | Show build configuration and requirements       |
| `tarball`                                                                        | Clean-install tar → `dist/i2p.tar.bz2`          |
| `buildDeb`                                                                       | Self-contained Debian package → `dist/i2p*.deb` |
| `zip-linux`, `zip-windows`, `zip-macos`, `zip-freebsd`                           | Platform package → `dist/i2p-<platform>.zip`    |
| `7zip-linux`, `7zip-windows`, `7zip-macos`, `7zip-freebsd`, `7zip-multiplatform` | The same as 7z → `dist/i2p-<platform>.7z`       |
| `i2pseeds`                                                                       | Reseed bundle → `dist/i2pseeds.su3`             |
| `buildAppImage`                                                                  | Linux AppImage → `dist/I2P+_<ver>.appimage`     |
| `git-tag`                                                                        | Create annotated release tag                    |
| `javadoc`, `javadoc-zip`, `devdocs`, `devdocs-zip`                               | Documentation                                   |
| `poupdate-source`                                                                | Update translation `.po` files                  |

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
```

Gradle build output goes to `${java.io.tmpdir}/build-i2p/gradle/<module>` so
the workspace stays clean (matching the ant convention).

The packaging targets are mirrored too, and each one prints the same
`SHA256: <hash>` line the ant `sha256sum` target prints:

| Area               | Gradle tasks                                                                                                                               |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Update zips        | `updater`, `updaterSmall`, `updaterRouter`, `updaterCompact`, `updater200`, `updater200Compact`                                            |
| Installers         | `installer`, `installerexe`, `installer-{nowindows,linux,freebsd,osx,windows}`, `installer2app`, `installer-all`, the `installer5*` family |
| Platform packages  | `zip-{linux,windows,macos,freebsd}`, `7zip-{linux,windows,macos,freebsd}`, `7zip-multiplatform`                                            |
| Other deliverables | `tarball`, `buildDeb`, `buildAppImage`, `i2psnark`, `i2psnark7zip`, `i2psnark_nozip`, `i2pseeds`                                           |
| Sets               | `pkg`, `pkg200`, `distclean`                                                                                                               |

Still Ant-only: the heavy `updater*With*` / `signed-updater*` variant family
(javadoc, GeoIP, ASN, jbigi, Jetty and Tomcat payloads beyond the plain and
compact updaters), `jpackage-win`, the native `buildJbigi*` compiles (Gradle
jars the vendored natives as `jbigi`), the `javadoc*` / `jsdoc*` / `devdocs*`
documentation targets, and the `debian*` / `slackpkg` helpers.
`tools/gradle/README.md` keeps the full task inventory, the properties each task
takes, and the list of deliberate deviations from ant.

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
