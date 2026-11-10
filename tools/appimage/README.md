# I2P+ AppImage Build Tools

## What is AppImage?

AppImage is a standalone Linux executable that packages I2P+ with its Java runtime. No installation needed - just run.

## Benefits

- Self-contained - includes Java runtime, works on any Linux x86_64
- Bundles the Java 17 runtime as the router's JVM (`-Dappimage.jdk=` to ship another)
- No installation required - run from anywhere
- No root needed
- Single file distribution and sharing

## Building

```bash
ant buildAppImage
```

Or directly:

```bash
bash tools/appimage/build-appimage.sh [version] [appimagetool-update] [pkg-temp-dir] [app-dir] [java-dir]
```

Both staging directories live under the build root (`/tmp/build-i2p` by default),
never in the workspace: the payload in `pkg-temp`, the AppDir - the tree that
gets packed - in `appimage`. Run by hand with the last two arguments empty, the
script asks ant or gradle where the payload is staged and puts the AppDir beside
it:

```bash
bash tools/appimage/build-appimage.sh "" never
```

Pass them to stage somewhere else:

```bash
bash tools/appimage/build-appimage.sh "" never /tmp/build-i2p/pkg-temp /tmp/build-i2p/appimage
```

## Java runtime

The router jars are Java 8 bytecode, so any runtime from 8 up runs them; the
AppImage ships OpenJDK 17 because the console's Jetty 9 is only exercised up to
there. ant names the runtime with `-Dappimage.jdk=`, and a path with no `bin/java`
in it stops the build rather than shipping an AppImage with no JVM. `AppRun` and
`i2prouter-wrapper` look the runtime up inside the image instead of naming a
version, so shipping a different one changes nothing in the shipped scripts.

The copy is pruned: `jmods` (jlink inputs), `include` and `man` (build-time),
`lib/ct.sym` (javac's cross-compilation symbol table), `lib/src.zip` (sources for
javadoc) and the CDS archives in `lib/server`, which are tied to the install path
of the JDK they were built in and so never map inside the image. That is 108 MB of
the 262 MB a full OpenJDK 17 adds. Everything a running JVM reads - `lib/modules`,
`lib/server/libjvm.so`, the other native libraries, `conf`, `legal` - stays.

The copy also dereferences symlinks (`cp -aL`) and fills in what a Debian/Ubuntu
JDK keeps outside its own tree. Those distributions reach `java.security`, the
jurisdiction policy files and the `cacerts` trust store through absolute links
into `/etc/java-<feature>-openjdk` and `/etc/ssl/certs/java`; a link copied into
the image is resolved on the machine that runs it, which may have no such
directory. The build copies those files in and stops if it cannot find them,
because a JVM without `java.security` throws `InternalError` at the first crypto
call and a JVM without a trust store fails every HTTPS handshake - eepget, the
plugin updates, console HTTPS.

## AppStream metadata

`io.github.i2pplus.i2pplus.appdata.xml` is the component description appimagetool
asks for. The build copies it into the AppDir under `usr/share/metainfo/` and
stamps the release version and date into the two placeholders. Without it the
packer says:

```
WARNING: AppStream upstream metadata is missing, please consider creating it
in usr/share/metainfo/i2pplus.appdata.xml
```

and the software centres that read this metadata show an entry with no
description, no upstream links and no version.

The names are not free, and the packer's own suggestion is not the file it looks
for. appimagetool looks for `usr/share/metainfo/<desktop file name>.appdata.xml`,
then validates the whole AppDir with `appstreamcli validate-tree <APPDIR>`. That
validator wants the metainfo file named after the component id, the id in
reverse-DNS form, and the `<launchable type="desktop-id">` naming a desktop file
that is really in the AppDir. So the desktop entry the build writes is
`io.github.i2pplus.i2pplus.desktop` - at the AppDir root, in `applications/` and
in `usr/share/applications/` - and the metadata's launchable names that file. Any
other pairing makes the packer call the metadata missing (`i2pplus.appdata.xml`
next to an id-named file), or fail the build with `metainfo-filename-cid-mismatch`
or `desktop-file-not-found`.

To check the file by hand, stamp it and validate a tree the way the packer does -
a single file on its own skips the desktop-entry check and proves nothing:

```bash
D=io.github.i2pplus.i2pplus
T=/tmp/appstream-check
rm -rf $T && mkdir -p $T/usr/share/metainfo $T/usr/share/applications
sed -e "s|@VERSION@|2.14.1|" -e "s|@DATE@|$(date +%Y-%m-%d)|" \
    tools/appimage/$D.appdata.xml > $T/usr/share/metainfo/$D.appdata.xml
cat > $T/usr/share/applications/$D.desktop << 'EOF'
[Desktop Entry]
Name=I2P+
GenericName=Anonymous Network Router
Comment=Router for the I2P anonymous network
Exec=i2prouter-wrapper
Icon=i2p
Terminal=false
Type=Application
Categories=Network;
StartupNotify=true
EOF
cp $T/usr/share/applications/$D.desktop $T/
appstreamcli validate-tree $T
```

The build's own line to watch for is:

```
AppStream upstream metadata found in usr/share/metainfo/io.github.i2pplus.i2pplus.appdata.xml
✔ Validation was successful.
```

## appimagetool

The packer is not in the repository (`tools/` is gitignored). `ant buildAppImage`
runs `appimage-setup` first, which takes the newest release from
[AppImage/appimagetool](https://github.com/AppImage/appimagetool/releases) and
replaces an older local copy; a hand run of the build script does the same when
the binary is missing.

```bash
ant appimage-setup                              # latest release
ant buildAppImage -Dappimagetool.update=1.9.0   # pin a release tag
ant buildAppImage -Dappimagetool.update=never   # keep what is installed
```

1.9.0 is the floor - the oldest release carrying the `SOURCE [DESTINATION]` form
the build script calls it with. The rolling `continuous` build is not used: it
has no version number to compare against.

appimagetool is itself an AppImage and needs FUSE 2 to mount itself, so the build
box wants `sudo apt install libfuse2` (on 24.04 apt resolves that to
`libfuse2t64`). Without the package, `APPIMAGE_EXTRACT_AND_RUN=1` makes it unpack
and run instead.

## Output

`dist/I2P+_{version}.appimage` - 78 MB with the OpenJDK 17 above (173 MB before
the runtime was pruned). The packer's own compression is zstd at level 15 over
128K blocks; the build asks for level 19 over 1M blocks, which costs two seconds
and saves 7 MB of the 84 MB staged tree. Level 22 buys nothing, and at 128K blocks
level 19 buys nothing either - the two settings only pay together.

## Usage

```bash
./dist/I2P+_{version}.appimage
```

The AppImage is fully portable - copy to any location and run.

On first run, it creates `~/.i2p` in your home directory for config and logs.

**Not working yet: the launcher chain.** `AppRun` execs `i2p/i2prouter`, which is
the Tanuki wrapper script from the payload, and that script still carries the
`%INSTALL_PATH`, `%USER_HOME` and `%SYSTEM_java_io_tmpdir` placeholders the izpack
installer substitutes. Nothing substitutes them in an AppImage, so `i2prouter`
stops with `Unable to find %INSTALL_PATH/wrapper.config` and the router never
starts. The payload's `runplain.sh` has the same placeholders. The image itself is
fine - running the bundled JVM against `net.i2p.router.Router` with
`-Di2p.dir.base` pointed at the mounted `i2p/` directory starts the router and
serves the console - so the fix is in the launcher, not the payload.
