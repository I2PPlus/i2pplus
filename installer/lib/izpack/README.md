# IzPack Installer Toolkit (`lib/izpack/`)

Contains the IzPack installer compiler and all resources needed to build I2P+ installer packages for both IzPack v4 and v5.

## Structure

| Path               | Description                                                           |
| ------------------ | --------------------------------------------------------------------- |
| `4/`               | IzPack 4 standalone compiler and patches (bundled in git)             |
| `5/`               | IzPack 5 installation descriptor and language pack patches            |
| `resources/`       | Shared installer resources (images, panel text, flags, welcome pages) |
| `install.xml`      | IzPack 4 installation descriptor                                      |
| `install5.xml`     | IzPack 5 installation descriptor                                      |
| `i2pinstaller.xml` | Launch4j config for wrapping the installer JAR as a Windows EXE       |

## Language Toolkit

Both descriptors must offer exactly the languages `/configui` offers, so the
mapping between them is generated and checked rather than hand-maintained.

| Script                            | Role                                                                                   |
| --------------------------------- | -------------------------------------------------------------------------------------- |
| `izpack-required-strings.py`      | Ids each IzPack version's panels reach, from the panel sources and the descriptor      |
| `check-izpack-langs.py`           | The gate: every console language selectable, translatable and flagged on both versions |
| `gen-izpack-flags.sh`             | Renders the language-picker flag GIFs from the console's SVG set                       |
| `gen-izpack-langpacks.py`         | Langpack reachability, and purging the "Made with IzPack" branding                     |
| `gen-izpack-langpack-strings.py`  | Reads and writes reviewed translations into the packs                                  |
| `gen-izpack-welcome.py`           | Localized first-panel HTML                                                             |

The console offers 41 languages, which map to 40 IzPack codes: `zh` and `zh_TW`
both resolve to `zho`, since Java collapses the region. The ISO 639-1 to 639-2
mapping lives in `gen-izpack-flags.sh` because it is IzPack's side of the join -
several languages are spelled differently by the two distributions, and a code no
JVM reports can be declared but never selected, so `check-izpack-langs.py` probes
a JDK and fails on one.

Flags are required in GIF: both compilers hardcode `flags/<iso3>.gif` and fail the
build otherwise, and neither accepts PNG, WebP or SVG. They live once in
`resources/flags/` and each build stages them to the path its compiler wants -
`izpack-patches` folds them into `patches.jar`, `izpack5-patches` copies them
beside the langpacks.

### Branding

`installer.madewith` titles the etched border around the installer's log window,
and every langpack the distributions ship has its own translation of it. The two
versions need opposite fixes, because the mechanism is not the same:

- **IzPack 5** resolves one global `CustomLangPack.xml` and merges it with
  `LocaleDatabase.add`, a `TreeMap` `putAll`, so it overrides for every language
  at once. The resource name must be exactly that - the lookup does no locale
  suffixing, so a per-language `CustomLangPack.xml_<iso3>` is silently never found.
- **IzPack 4** has no such mechanism, so the only lever is classpath shadowing:
  `patches.jar` precedes `standalone-compiler.jar`, so our copy of a pack wins.

`ant izpack-branding-check` proves the result rather than assuming it, by
performing the same merge the installer performs against the built jar.

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
| `izpack5.home`   | Use an existing distribution. Used as-is, so it also stops auto-update.   |
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

### The resulting exe is Windows-only

`dist/i2pinstall.exe` is a Windows PE launcher and nothing else. `java -jar` on it
fails:

```console
$ java -jar ./dist/i2pinstall.exe
Error: Invalid or corrupt jarfile ./dist/i2pinstall.exe
```

That is the file's construction, not a packaging defect. `izpack2exe.py`
concatenates `7zS.sfx` + `config.txt` + `installer.7z`, so the tail of the file is
7z data and there is no zip end-of-central-directory record for a zip reader to
find. At runtime the stub unpacks `install.jar` to a temporary directory and runs

```
ExecuteFile="javaw"
ExecuteParameters="-jar \"install.jar\""
```

`javaw` is the Windows console-less launcher and does not exist on Linux, so the
exe is not runnable here even with a prefix or a JRE installed.

The IzPack 4 exe was different, and still is if you build it: the launch4j target
prepends its PE stub and leaves the jar appended intact, so the zip central
directory sits 22 bytes from EOF and `java -jar` reads the archive straight out of
the launcher. Verified against `installer/i2pinstaller.xml`:

| Target          | Launcher   | Tail of file     | `java -jar` |
| --------------- | ---------- | ---------------- | ----------- |
| `installer5exe` | izpack2exe | 7z archive       | no          |
| `installerexe`  | launch4j   | jar, EOCD intact | yes         |

This changed in `21504f1539` (2019-04-22, ticket #2403), which moved the release
chain from `installerexe` to `installer5exe`. It was a deliberate trade for Windows
code-signing: launch4j output cannot be signed without being corrupted, whereas
izpack2exe output can. There is no single layout that is both signable and
loadable by `java -jar`, so if signability wins, the exe is Windows-only and
nothing should expect otherwise of it.

To exercise the installer on Linux, run the jar it wraps:

```sh
java -jar dist/install.jar
```

Both exes embed exactly that jar, so this tests the same content.

#### The two targets collide

`installer/i2pinstaller.xml` and the `installer5exe` target both write
`dist/i2pinstall.exe`. Whichever runs last wins, and the loser is not recorded
anywhere, so a release that mixes the two target chains can silently ship the
IzPack 4 launcher for an IzPack 5 build or the reverse. When checking a build,
confirm which produced the file before trusting it.

To check an exe without a Windows host, carve the payload back out and compare it
against the jar:

```sh
python3 - <<'PY'
d = open('dist/i2pinstall.exe', 'rb').read()
open('/tmp/installer.7z', 'wb').write(d[d.find(b'7z\xbc\xaf\x27\x1c'):])
PY
installer/lib/izpack/5/izpack/utils/wrappers/izpack2exe/7za e -y -o/tmp/carved /tmp/installer.7z
cmp /tmp/carved/install.jar dist/install.jar && echo "exe wraps the current jar"
```

`7za l -slt` on the archive should report `Method = LZMA`, not `LZMA2`, for the
reason given above.

## Shared Resources (`resources/`)

Consumed by `install5.xml` as `<res>` entries, with paths relative to the repo
root.

- `images/` - Installer banner images (`i2plogo.png`, `i2plogo2.png`, `console.png`)
- `shortcutSpec.xml` - Shortcut definitions for the IzPack 5 ShortcutPanel only (IzPack 4 creates shortcuts via `service.ps1 -Action Shortcuts`)
- `flags/` - Language-picker flag GIFs, shared by both IzPack versions
- `CustomLangPack.xml` - Overrides IzPack's built-in strings, including blanking the "Made with IzPack" branding
- `welcome.html`, `welcome_<iso3>.html` - IzPack 5 HTMLHelloPanel content, English plus one per language
- `start-i2p.txt` - Post-install info panel text

IzPack 5 fetches an HTML panel's body by resource name and never through the
langpack, so without the per-language pages every language would show the English
one. `net.i2p.installer.LocalizedHTMLInfoPanel` resolves `welcome_<iso3>.html` and
falls back to `welcome.html`.

The Windows `.ico` files are not here: they ship in the payload as
`docs/*.ico` from `installer/resources/platform-specific/windows/`, so
`shortcutSpec.xml` refers to `$INSTALL_PATH\docs\`.
