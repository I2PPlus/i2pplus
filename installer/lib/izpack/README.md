# IzPack Installer Toolkit (`lib/izpack/`)

Contains the IzPack installer compiler and all resources needed to build I2P+ installer packages for both IzPack v4 and v5.

## Structure

| Path               | Description                                                      |
| ------------------ | ---------------------------------------------------------------- |
| `4/`               | IzPack 4 standalone compiler and patches (bundled in git)        |
| `5/`               | IzPack 5 installation descriptor and language pack patches        |
| `resources/`       | Shared installer resources (images, panel text, shortcut spec)   |
| `install.xml`      | IzPack 4 installation descriptor                                 |
| `install5.xml`     | IzPack 5 installation descriptor                                 |
| `customicons.xml`  | IzPack 5 icon configuration                                      |
| `i2pinstaller.xml` | Launch4j config for wrapping the installer JAR as a Windows EXE  |

## IzPack 5 Distribution

IzPack 4 is bundled (`4/standalone-compiler.jar`, tracked in git); IzPack 5 is
not, because it ships as ~100MB of jars. `5/download-izpack5.sh` fetches it
instead:

```sh
ant download-izpack5          # or: installer/lib/izpack/5/download-izpack5.sh
```

Every `installer5*` target runs that check first, so a normal build needs no
setup. The script compares `5/version.txt` against the latest upstream release
(GitHub releases API, falling back to Maven Central metadata) and replaces the
cached copy when they differ, deleting the version it supersedes.

The distribution lands in `5/izpack-<version>/` with `5/izpack` symlinked to it;
that symlink is what `izpack5.home` resolves to. Both, plus `5/version.txt`, are
gitignored. The versioned subdirectory is not cosmetic: IzPack's own installer
wipes its target folder before unpacking, which would destroy `install5.xml` and
`patches/` if it were pointed at `5/` directly.

Offline builds keep working - if the release check or the download fails and a
cached copy is already present, the script warns and uses it. `--force`
re-downloads the current version, and `--version X.Y.Z` (or the `IZPACK5_VERSION`
environment variable) pins a release instead of tracking the latest. On every
update it deletes the version directory it supersedes, matched on
`izpack-<digits>` so the sweep can never reach `izpack5.properties` or the
`izpack` symlink.

### Per-host settings

`5/izpack5.properties` is created by the script on first run, is gitignored, and
is read by `build.xml` **ahead of** `override.properties`. Put machine-specific
values there so they are never committed - `override.properties` is tracked in
git despite appearing in `.gitignore`, so anything written to it does get
committed.

| Property         | Effect                                                                    |
| ---------------- | ------------------------------------------------------------------------- |
| `izpack5.home`   | Use an existing distribution. Used as-is, so it also stops auto-update.    |
| `izpack5.python` | Interpreter for `izpack2exe.py`, which needs Python 3 (default `python3`) |

The file carries no version pin; delete it to fall back to the built-in defaults,
which track the latest release.

### Building `dist/i2pinstall.exe`

`installer5exe` runs IzPack's `izpack2exe.py`, which produces an exe that can
still be Windows-signed (launch4j output cannot). Two details it depends on:

- The interpreter is passed explicitly. The script's `#!/usr/bin/env python`
  shebang does not resolve where only `python3` is on PATH.
- IzPack's own `7za` and `7zS.sfx` are used, not a system `7zr`. The wrapper
  finds `7zS.sfx` via `os.path.dirname(p7z)`, and the bundled pair is
  self-consistent: both are from 2009 (p7zip 4.65, SFX FileVersion 4.64), before
  LZMA2 existed. A modern `7zr` defaults to LZMA2, which that stub cannot decode,
  so the exe would fail to unpack on any Windows version.

`izpack2exe.py` writes `installer.7z` and `config.txt` into the current directory,
so it runs in `5/exe-work/` (also gitignored) rather than the repo root. It
cleans both up on success, and `ant clean` removes the directory.

## Shared Resources (`resources/`)

Consumed by `install5.xml` as `<res>` entries, with paths relative to the repo
root.

- `images/` - Installer banner images (`i2plogo.png`, `i2plogo2.png`, `console.png`)
- `shortcutSpec.xml` - Shortcut definitions for the IzPack 5 ShortcutPanel only (IzPack 4 creates shortcuts via `service.ps1 -Action Shortcuts`)
- `CustomLangPack.xml_eng` - Overrides for IzPack's built-in English strings
- `welcome.html` - IzPack 5 HTMLHelloPanel content
- `start-i2p.txt` - Post-install info panel text

The Windows `.ico` files are not here: they ship in the payload as
`docs/*.ico` from `installer/resources/platform-specific/windows/`, so
`shortcutSpec.xml` refers to `$INSTALL_PATH\docs\`.
