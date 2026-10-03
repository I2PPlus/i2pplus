# Launch4j (`lib/launch4j/`)

Third-party tool for wrapping Java JARs into Windows native executables. Used to create `i2pinstall.exe` and `i2p.exe` (standalone router launcher).

**Version: 3.50** (released 2022-11-15)

Source: https://sourceforge.net/projects/launch4j/

## What's Included

- `launch4j.jar` - Launch4j compiler (v3.50)
- `launch4j` - Shell wrapper script
- `bin/` - MinGW cross-compilation tools (`ld`, `windres`)
- `lib/` - Launch4j dependencies
- `w32api/` - Windows API static libraries
- `head/` - EXE header stubs (console, GUI)
- `i2plustoexe.xml` - I2P installer EXE configuration
- `manifest/` - Windows manifest files
- `i2pstandalone.xml` - Standalone router EXE configuration (in `installer/`)

## Usage

Launch4j is invoked by these Ant targets in the root `build.xml`:

- `installerexe` - Wraps the IzPack 4 `dist/install.jar` into `dist/i2pinstall.exe`
  (config `installer/i2pinstaller.xml`)
- `buildexe` - Wraps the standalone router launcher into `i2p.exe`

The **IzPack 5** exe is *not* built with launch4j. `installer5exe` uses IzPack's own
`izpack2exe.py` from the downloaded distribution, because launch4j-generated exes
cannot be Windows-signed and `i2pinstall.exe` has to be signable. See
[../izpack/README.md](../izpack/README.md).

```bash
# Direct usage
java -jar lib/launch4j/launch4j.jar config.xml
```

## License

BSD 3-Clause. See `LICENSE.txt`.
