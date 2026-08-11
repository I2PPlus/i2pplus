# Gradle Build

The Gradle build provides an alternative to Ant for compiling Java modules and building update packages. Build output is redirected to `/tmp/` to keep the workspace clean.

## Prerequisites

- Java SDK 1.8+ (JDK 21 recommended)
- The Gradle wrapper at `gradlew` bootstraps itself — no pre-installed Gradle required

## Build output

All build artifacts go under `/tmp/build-i2p/gradle/`. No `.class` files or jars are written to the workspace.

## Available tasks

### Update packages

| Task                      | Description                                    | Output                                     |
| ------------------------- | ---------------------------------------------- | ------------------------------------------ |
| `./gradlew updater`       | Build full update zip                          | `dist/i2pupdate.zip`                       |
| `./gradlew updaterSmall`  | Build minimal update (router + essential apps) | `dist/i2pupdate.zip`                       |
| `./gradlew updaterRouter` | Build router-only update                       | `dist/i2pupdate.zip`                       |

Update zips land in `dist/` (repo root), like the Ant build.

### Prep tasks (used by updater targets)

| Task               | Description                                                       |
| ------------------ | ----------------------------------------------------------------- |
| `prepUpdate`       | Build all Java jars/wars and stage them into the package temp dir |
| `prepUpdateSmall`  | Build and stage router + essential apps only                      |
| `prepUpdateRouter` | Build and stage router jars only                                  |

### IzPack installers

Mirrors of the Ant `installer*` targets in `build.xml`. Each installer is
built from a per-platform staging directory under `/tmp/build-i2p/pkg-installer/`;
the payload is seeded from `prepUpdate` and then refined per platform (wrapper
dirs, jbigi natives, scripts, Windows CRLF). All output goes to `dist/`.

| Task                            | Description                                                        | Output                                   |
| ------------------------------- | ------------------------------------------------------------------ | ---------------------------------------- |
| `./gradlew installer`           | Full all-platform installer (Ant `installer`)                      | `dist/install.jar`                       |
| `./gradlew installerexe`        | Wrap the full installer in a Windows exe (launch4j)                | `dist/i2pinstall.exe`                    |
| `./gradlew installer-nowindows` | No-windows installer                                               | `dist/i2pinstall_<ver>.jar`              |
| `./gradlew installer-linux`     | Linux-only installer                                               | `dist/i2pinstall_<ver>_linux-only.jar`   |
| `./gradlew installer-freebsd`   | FreeBSD-only installer                                             | `dist/i2pinstall_<ver>_freebsd-only.jar` |
| `./gradlew installer-osx`       | OSX-only installer                                                 | `dist/i2pinstall_<ver>_osx-only.jar`     |
| `./gradlew installer2app`       | Wrap the OSX installer as a `.app` bundle                          | `dist/i2pinstall_<ver>_osx.tar.bz2`      |
| `./gradlew installer-windows`   | Windows-only installer exe (skips the broken Ant move)             | `dist/i2pinstall_<ver>_windows.exe`      |
| `./gradlew installer5*`         | Same family using IzPack 5 (requires `~/IzPack`)                   | (same names)                             |
| `./gradlew installer-all`       | Build every platform installer                                     |                                          |

The `preppkg*` staging tasks (`preppkg`, `preppkg-nowindows`,
`preppkg-linux-only`, `preppkg-freebsd-only`, `preppkg-osx-only`,
`preppkg-windows-only`) can be run standalone to inspect a payload. The
`buildexe` task stages the Windows launcher (`launchi2p.jar`, `i2p.exe`).

Prerequisites:

- IzPack 4.3.5 standalone compiler jars ship in `installer/lib/izpack/4/`
  (vendored, so the Ant parity builds work out of the box)
- IzPack 5 must be installed at `~/IzPack`; `installer5*` fails with an
  Ant-style message otherwise (mirrors `build.xml`)
- `installer2app` needs `~/IzPack/utils/wrappers/izpack2app/` and skips
  silently if absent
- `installerexe`/`installer-windows`/`installer5exe` run launch4j/izpack2exe
  from the vendored jars in `installer/lib/launch4j/` on x86-family
  Linux/Windows hosts only

### Individual module builds

Each module can be built independently:

| Command                             | Builds                     |
| ----------------------------------- | -------------------------- |
| `./gradlew :core:jar`               | Core library (`i2p.jar`)   |
| `./gradlew :router:jar`             | Router (`router.jar`)      |
| `./gradlew :apps:jetty:jar`         | Jetty servlet support      |
| `./gradlew :apps:routerconsole:jar` | Router console             |
| `./gradlew :apps:i2ptunnel:jar`     | I2P tunnel manager         |
| `./gradlew :apps:sam:jar`           | SAM application            |
| `./gradlew :apps:streaming:jar`     | Streaming library          |
| `./gradlew :apps:ministreaming:jar` | Minimal streaming library  |
| `./gradlew :apps:addressbook:jar`   | Addressbook susidns        |
| `./gradlew :apps:i2psnark:jar`      | I2PSnark bitTorrent client |
| `./gradlew :apps:systray:jar`       | System tray support        |
| `./gradlew :apps:desktopgui:jar`    | Desktop GUI                |
| `./gradlew :apps:susimail:war`      | SUSI mail webapp           |
| `./gradlew :apps:susidns:war`       | SUSI DNS webapp            |
| `./gradlew :apps:i2pcontrol:war`    | JSON-RPC control API       |
| `./gradlew :apps:imagegen:war`      | Image generation webapp    |
| `./gradlew :apps:jrobin:jar`        | JRobin monitoring          |

### Other

| Task                  | Description                                                        |
| --------------------- | ------------------------------------------------------------------ |
| `./gradlew test`      | Run unit tests                                                     |
| `./gradlew clean`     | Delete build output                                                |
| `./gradlew jar`       | Build all jars                                                     |
| `./gradlew war`       | Build all wars                                                     |
| `./gradlew pkg`       | Full distribution: update zip, deb, tarball, installer (Ant `pkg`) |
| `./gradlew buildDeb`  | Debian package                                                     |
| `./gradlew tarball`   | Source-less tarball                                                |
| `./gradlew distclean` | Wipe all build state                                               |

## Running

```sh
# Build everything and create update zip
./gradlew updater

# Build the full distribution (Ant pkg parity)
./gradlew pkg

# Build just the installer
./gradlew installer

# Build a single module
./gradlew :core:jar

# Run tests
./gradlew test

# Clean
./gradlew clean
```

## Configuration cache

Configuration cache is enabled in `gradle.properties`. If you see "configuration cache discarded" warnings, the build still works — it just re-computes the cache on the next run.

## Deviations from Ant (deliberate)

- The Ant `installer-windows`/`installer5-windows` targets move files at
  repo-root paths (`basedir/install.jar`, `basedir/i2pinstall.exe`) that the
  Ant build never creates, so the `move` fails and the dist file is never
  renamed; the Gradle port uses `dist/`-relative intermediates
  (`install-windows.jar`, `i2pinstall-windows.exe`) and renames into
  `dist/i2pinstall_<ver>_windows.exe`
- `installer2app` tar output goes to `dist/` (Ant writes it to the repo root)
- Each platform installer stages into its own directory; Ant shares one
  `pkg-temp` for everything and deletes it per target (`installer-all` in Ant
  is order-dependent)
- `lib/pack200.jar` is not built (no Gradle module for pack200); the payload
  and the launcher Class-Path still reference it for Ant parity
- The installer payload is seeded from `prepUpdate`, so its contents follow
  the fix-ups (no jars in `WEB-INF/lib`, clean locale trees)
- Jetty runtime jars (vendored in `apps/jetty/jettylib/`) and the Tomcat
  NOTICE are staged into the installer payload only, matching Ant's
  `preppkg-base`; the updater/deb/tarball packages do not include them

## Notes

- Tests are not wired into the task graph for update builds (they must be run explicitly with `./gradlew test`)
- The canonical test runner is `ant test` or `tools/scripts/run-tests.sh`
- Build output destination is controlled by `buildDir` in `build.gradle` (default: `/tmp/build-i2p/gradle/`)
- Per-platform installer staging lives under `/tmp/build-i2p/pkg-installer/`; IzPack work files under `/tmp/build-i2p/gradle/izpack/`; launch4j work under `/tmp/build-i2p/gradle/launch4j/`
