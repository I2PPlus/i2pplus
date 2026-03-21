# I2P+ Headless (Console Mode) Installation

If you have already run `java -jar i2pinstall.exe -console`, the installer
has completed in text mode and executed the `postinstall.sh` script.
Start the router with:

```sh
sh i2prouter start
```

If no X server is running, the browser launch will fail. Use
`lynx http://localhost:7657/` to configure the router via a terminal.

For support, see https://i2pplus.github.io/i2pplus or
https://i2p-irc.stormycloud.org/

## Data directories

I2P+ stores configuration data in the user directory `~/.i2p/` on Linux
and `%LOCALAPPDATA%\I2P\` on Windows. This directory is created on first run.
Additional files are written to the JVM temporary directory.

To relocate these directories (or configure a portable installation), edit
`i2prouter` (Linux) and `wrapper.config` (Linux/Windows) and look for
comments labelled `PORTABLE`. Do this before running I2P+ for the first time.

## Running I2P+

| Platform            | Command                |
| ------------------- | ---------------------- |
| Linux, BSD, Mac     | `sh i2prouter start`   |
| Windows             | `I2P.exe`              |
| Without wrapper     | `sh runplain.sh`       |

## Stopping I2P+

| Method      | Command                                                       |
| ----------- | ------------------------------------------------------------- |
| Graceful    | `sh i2prouter graceful` or http://localhost:7657/summaryframe |
| Immediate   | `sh i2prouter stop`                                           |

## Uninstalling

```sh
rm -rf $I2PInstallDir ~/.i2p
```

## Supported JVMs

As of release 2.12.0+ (0.9.69+), all platforms require Java 17 or higher.

| Platform        | JVM                                      |
| --------------- | ---------------------------------------- |
| Windows         | OpenJDK, Oracle, or Microsoft JDK        |
| Linux           | OpenJDK or Oracle                        |
| FreeBSD         | OpenJDK or Oracle                        |
| Raspberry Pi    | OpenJDK or Oracle                        |

For details, see https://i2pplus.github.io/i2pplus
