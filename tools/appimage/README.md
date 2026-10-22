# I2P+ AppImage Build Tools

## What is AppImage?

AppImage is a standalone Linux executable that packages I2P+ with its Java runtime. No installation needed - just run.

## Benefits

- Self-contained - includes Java runtime, works on any Linux x86_64
- Uses Java 17 (default, change in build script if needed)
- No installation required - run from anywhere
- No root needed
- Single file distribution and sharing

## Building

```bash
ant buildAppImage
```

Or directly:

```bash
bash tools/appimage/build-appimage.sh [version] [appimagetool-update] [pkg-temp-dir]
```

Pass the third argument to keep the staging directory out of the workspace, as
the ant target does — without it the script defaults to `pkg-temp/` in the
repository root:

```bash
bash tools/appimage/build-appimage.sh "" never /tmp/build-i2p/pkg-temp
```

The build script automatically downloads appimagetool if not present.

## Output

`dist/I2P+_{version}.appimage`

## Usage

```bash
./dist/I2P+_{version}.appimage
```

The AppImage is fully portable - copy to any location and run.

On first run, it creates `~/.i2p` in your home directory for config and logs.
