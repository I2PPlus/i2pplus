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

| Task                            | Description                                                     | Output                                   |
| ------------------------------- | --------------------------------------------------------------- | ---------------------------------------- |
| `./gradlew installer`           | Full all-platform installer (Ant `installer`)                   | `dist/install.jar`                       |
| `./gradlew installerexe`        | Wrap the full installer in a Windows exe (launch4j)             | `dist/i2pinstall.exe`                    |
| `./gradlew installer-nowindows` | No-windows installer                                            | `dist/i2pinstall_<ver>.jar`              |
| `./gradlew installer-linux`     | Linux-only installer                                            | `dist/i2pinstall_<ver>_linux-only.jar`   |
| `./gradlew installer-freebsd`   | FreeBSD-only installer                                          | `dist/i2pinstall_<ver>_freebsd-only.jar` |
| `./gradlew installer-osx`       | OSX-only installer                                              | `dist/i2pinstall_<ver>_osx-only.jar`     |
| `./gradlew installer2app`       | Wrap the OSX installer as a `.app` bundle                       | `dist/i2pinstall_<ver>_osx.tar.bz2`      |
| `./gradlew installer-windows`   | Windows-only installer exe (skips the broken Ant move)          | `dist/i2pinstall_<ver>_windows.exe`      |
| `./gradlew installer5*`         | Same family using IzPack 5 (compiler auto-downloaded)          | (same names)                             |
| `./gradlew installer5exe`       | Windows exe from the IzPack 5 jar (izpack2exe.py, not launch4j) | `dist/i2pinstall.exe`                    |
| `./gradlew installer-all`       | Build every platform installer                                  |                                          |
| `./gradlew downloadIzpack5`     | Download or update the IzPack 5 distribution                   |                                          |

The `preppkg*` staging tasks (`preppkg`, `preppkg-nowindows`,
`preppkg-linux-only`, `preppkg-freebsd-only`, `preppkg-osx-only`,
`preppkg-windows-only`) can be run standalone to inspect a payload. The
`buildexe` task stages the Windows launcher (`launchi2p.jar`, `i2p.exe`).

Prerequisites:

- IzPack 4.3.5 standalone compiler jars ship in `installer/lib/izpack/4/`
  (vendored, so the Ant parity builds work out of the box)
- IzPack 5 is downloaded on demand into `installer/lib/izpack/5/izpack` by
  `installer/lib/izpack/5/download-izpack5.sh` (the `downloadIzpack5` task, which
  every `installer5*` task depends on). It needs network access and ~95MB of
  free space on the first run; the cache is gitignored and self-updating.
- Per-host IzPack settings live in `installer/lib/izpack/5/izpack5.properties`
  (gitignored, written by the download script) and are read ahead of
  `-Dizpack5.home`. It can set `izpack5.home` (use an existing distribution) and
  `izpack5.python` (interpreter for the wrapper scripts).
- Python 3 is required by `installer5exe` and `installer2app`. The scripts'
  `#!/usr/bin/env python` shebangs do not resolve where only `python3` is on
  `PATH`, so the interpreter is passed explicitly and defaults to `python3`.
- `installer2app` needs `utils/wrappers/izpack2app/` from that distribution and
  skips silently if absent
- `installerexe`/`installer-windows` run launch4j from the vendored jars in
  `installer/lib/launch4j/`; `installer5exe` does **not** use launch4j, because
  launch4j output cannot be Windows-signed. Both are x86-family Linux/Windows
  hosts only.

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

### Standalone I2PSnark

Mirrors of the Ant `i2psnark` family in `build.xml`. Each builds the fat
standalone jar (with the patched jetty-util and the jbigi natives) and the
themed standalone war, then packages the install dir.

| Task                       | Description                                | Output                              |
| -------------------------- | ------------------------------------------ | ----------------------------------- |
| `./gradlew i2psnark`       | Standalone zip + sha256 (Ant `i2psnark`)   | `dist/i2psnark-standalone.zip`      |
| `./gradlew i2psnark7zip`   | Standalone 7z, needs `7z` on PATH          | `dist/i2psnark-standalone.7z`       |
| `./gradlew i2psnark_nozip` | Standalone dir (Ant `i2psnark_nozip`)      | `dist/i2psnark_standalone/`         |

Prerequisite: `jbigi` (jars the vendored natives from `installer/lib/jbigi`).

### Other

| Task                              | Description                                                                                                                                   |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `./gradlew test`                  | Run unit tests                                                                                                                                |
| `./gradlew clean`                 | Delete build output                                                                                                                           |
| `./gradlew jar`                   | Build all jars                                                                                                                                |
| `./gradlew war`                   | Build all wars                                                                                                                                |
| `./gradlew pkg`                   | Full distribution: update zip, deb, tarball, installer (Ant `pkg`)                                                                            |
| `./gradlew buildDeb`              | Debian package                                                                                                                                |
| `./gradlew tarball`               | Source-less tarball                                                                                                                           |
| `./gradlew distclean`             | Wipe all build state                                                                                                                          |
| `./gradlew sonarqube-report-full` | Full analysis with local rule docs (mirrors `ant sonarqube-report-full`)                                                                      |
| `./gradlew sonarqube-report-zip`  | Zip the report + rules into `dist/sonarqube-report.zip` (mirrors `ant sonarqube-report-zip`)                                                  |
| `./gradlew selftest`              | Verify the documented task inventory, then run `updaterCompact` (compiles and packages every module); all other targets are name-checked only |

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
- `lib/pack200.jar` is built by the `:apps:pack200` module and rides in the
  update payload (ant parity with `prepupdateSmall`); the launcher
  Class-Path references it too. `pack200Updater` compresses with that same
  jar, so no JDK-provided `pack200` tool is needed.
- The installer payload is seeded from `prepUpdate`, so its contents follow
  the fix-ups (no jars in `WEB-INF/lib`, clean locale trees)
- Jetty runtime jars (vendored in `apps/jetty/jettylib/`) and the Tomcat
  NOTICE are staged into the installer payload only, matching Ant's
  `preppkg-base`; the updater/deb/tarball packages do not include them
- The standalone war is built as a separate `i2psnark-standalone.war`;
  Ant mutates the regular `build/i2psnark.war` in place (`zip update`)
- The standalone fat jar merges with `duplicates=EXCLUDE` (first entry
  wins); Ant's `jar` task defaults to keeping duplicates
- `Base-Revision` is `git` in the fat jar manifest (Ant uses the workspace
  revision; the Gradle `getWorkspaceVersion()` is a placeholder)

## Notes

- Tests are not wired into the task graph for update builds (they must be run explicitly with `./gradlew test`)
- The canonical test runner is `ant test` or `tools/scripts/run-tests.sh`
- Build output destination is controlled by `buildDir` in `build.gradle` (default: `/tmp/build-i2p/gradle/`)
- Per-platform installer staging lives under `/tmp/build-i2p/pkg-installer/`; IzPack work files under `/tmp/build-i2p/gradle/izpack/`; launch4j work under `/tmp/build-i2p/gradle/launch4j/`
