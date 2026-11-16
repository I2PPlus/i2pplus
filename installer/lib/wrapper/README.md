# Tanuki Java Service Wrapper

This directory contains the Tanuki Java Service Wrapper binaries for all supported platforms.
The wrapper version is managed via `version.txt` (currently **3.7.4**), and
`README.md` names the same version. `sync-wrapper-version.sh` keeps the two in
step; `wrapper-update.sh` and the scripts it runs do that for you.

## Automated Updates

### ant updateWrapper

The easiest way to update all wrapper binaries:

```bash
ant updateWrapper                        # newest release on SourceForge
ant updateWrapper -Dwrapper.update.args="--pinned"   # only what version.txt names
ant updateWrapper -Dwrapper.update.args="--version 3.7.3"
ant updateWrapper -Dwrapper.update.args="--no-commit"  # install, do not commit
```

This will:
1. Ask SourceForge which release is newest (`wrapper-release.sh`)
2. Download the delta pack and update Unix/Linux/FreeBSD/macOS binaries
3. Download source and cross-compile Windows x64 binaries
4. Automatically remove obsolete platforms not in the current delta pack
5. Name the new release in `version.txt` and this file only once the build passed
6. Commit the release - binaries, `version.txt` and this file only, never the
   scripts - as `installer: update wrapper to X.Y.Z`

A release folder on SourceForge is named `Wrapper_<version>_<date>`, and that
date is part of every download URL, so it is looked up at run time rather than
kept in a table by hand — a remembered date goes stale the day a new release
lands, and the download then asks the previous release's folder for the new one.
If either stage fails, `version.txt` and this file are put back at the release
the tree actually holds. Pass `--no-commit` (as an ant property, see above) to
install the release and leave it unstaged.

`win64/BUILT-FROM` names the release the cross-compiled Windows pair was built
from. The jar's manifest cannot answer that: the jar comes from the delta pack
and the pair is built here, so the two can drift, and a jar the delta pack
already bumped would otherwise certify a win64 pair still built from the
previous release's source.

### Manual Scripts

#### wrapper-update.sh

Runs both stages and rolls the bookkeeping files back if either fails:

```bash
./wrapper-update.sh                       # newest release
./wrapper-update.sh --pinned              # version.txt's release
./wrapper-update.sh --version 3.7.3       # one release
```

#### wrapper-release.sh

Asks SourceForge which releases exist. Used by the other scripts; handy on its own:

```bash
./wrapper-release.sh latest                       # newest version number
./wrapper-release.sh --version 3.7.4 date         # folder date for the URL
./wrapper-release.sh --version 3.7.4 has-src      # is the source published yet?
```

The binaries (`/wrapper`) and the source (`/wrapper_src`) are published
separately, so a new release can have downloadable binaries and no source yet;
`build-wrapper-win64.sh` checks before it downloads anything.

#### update-wrapper.sh

Downloads delta pack and updates Unix binaries:

```bash
./update-wrapper.sh              # Update using version.txt
./update-wrapper.sh --latest     # Newest release on SourceForge
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
./build-wrapper-win64.sh --latest     # Newest release on SourceForge
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

## Updating the wrapper in an install

Everything above updates the **repository**. Putting a new wrapper into a running
install is a separate step, because an I2P update cannot update the wrapper it is
running under: the launcher, the native library it loads and the jar it starts
all belong to the wrapper process, and a restart from the console re-launches
only the JVM inside that same wrapper.

### Staging the release into an update

```bash
ant updaterWithWrapper             # i2pupdate.zip carrying the wrapper files
ant updater200WithWrapper          # the Java 200 update, same payload
ant signed-updater200WithWrapper   # the same, signed as an .su3
```

```bash
./gradlew updaterWithWrapper       # gradle mirrors both ant targets
./gradlew updater200WithWrapper
```

`updaterFull` and `updater200Full` include them as well; the plain `updater`
targets do not. They copy the artifacts out of `installer/lib/wrapper/`, so run
`ant updateWrapper` first — that is what puts the new release in the tree. Each
build says which release it bundled:

```
* Bundling Wrapper 3.7.4 -> lib/wrapper/ and the install-wrapper scripts
```

The payload is the release itself — the `all/` jar and every platform directory,
about 5 MB unpacked — plus the two scripts that do the install:

```
lib/wrapper/<platform>/...
lib/wrapper/version.txt
install-wrapper.sh    linux, FreeBSD, macOS
install-wrapper.ps1   Windows
```

### Applying it

Until the staged files are in place, the router says what is waiting on every
start, in `wrapper.log`:

```
New wrapper release staged -> Run sh ./install-wrapper.sh
* Staged: 3.7.4 * Running: 3.7.3
```

Then run the script from the install directory. It stops the router, replaces
the launcher, the native library and the jar for the platform it is running on,
and starts it again. An update extracts its files without the exec bit, so run
the script through `sh`:

```bash
sh ./install-wrapper.sh --dry-run   # show what it would do
sh ./install-wrapper.sh
```

```powershell
.\install-wrapper.ps1 -DryRun
.\install-wrapper.ps1
```

Both keep what they replace under `lib/wrapper-backup/` and put it back if the
new launcher does not start. They find the router however it is running — a
systemd unit, a macOS launchd job, a FreeBSD service, the `I2P+` Windows
service, or a process started with the `i2prouter` script — and stop and start
it through that mechanism.

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

- Native wrapper binaries reach an incremental install only through
  `updaterWithWrapper`, `updater200WithWrapper`, `updaterFull` or
  `updater200Full` — see Updating the wrapper in an install above. The plain
  `updater` targets stay small and do not carry them.
- Full installers (`ant pkg`) always carry them, and need no follow-up script:
  they install the release they were built with.
- Cache location: `installer/lib/wrapper/cache/`
- Use `version.txt` to manage the wrapper version.
