# Launch4j (`lib/launch4j/`)

Third-party tool for wrapping Java JARs into Windows native executables. Used to create `i2pinstall.exe` and `i2p.exe` (standalone router launcher).

## What's Included

- `launch4j.jar` - Launch4j compiler
- `bin/` - MinGW cross-compilation tools (`ld`, `windres`)
- `lib/` - Launch4j dependencies
- `w32api/` - Windows API static libraries
- `head/` - EXE header stubs (console, GUI)
- `i2plustoexe.xml` - I2P installer EXE configuration

## Usage

Launch4j is invoked by Ant targets in the root `build.xml`:

- `installerexe` - Wraps the IzPack 4 `install.jar` into `i2pinstall.exe`
- `installer5exe` - Wraps the IzPack 5 `install.jar` into `i2pinstall.exe`

The standalone router EXE (`i2p.exe`) is configured via `installer/i2pstandalone.xml`.

## License

BSD 3-Clause. See `LICENSE.txt`.
