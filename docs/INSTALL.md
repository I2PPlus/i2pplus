# Building and Installing I2P+ from Source

## Prerequisites

| Requirement      | Details                                                        |
| ---------------- | -------------------------------------------------------------- |
| **Java SDK**     | 17 or higher (OpenJDK, Oracle, or Microsoft JDK on Windows).   |
| **Apache Ant**   | 1.9.8 or higher                                                |
| **GNU gettext**  | `xgettext`, `msgfmt`, and `msgmerge` must be on your PATH      |
| **Locale**       | Build environment must use a UTF-8 locale                      |

For JVM compatibility details, see https://i2pplus.github.io/i2pplus

## Building the installer

From the project root:

```sh
ant pkg
```

On non-x86 platforms, use one of the following instead:

```sh
ant installer-linux
ant installer-freebsd
ant installer-osx
```

This produces:

| File               | Description                                           |
| ------------------ | ----------------------------------------------------- |
| `install.jar`      | GUI and console installer                             |
| `i2pinstall.exe`   | Cross-platform wrapped installer (`ant pkg` only)     |
| `i2pupdate.zip`    | Update package (`ant pkg` only)                       |

### Building native crypto libraries (jbigi)

JBIGI provides hardware-optimised modular exponentiation via GMP. Pre-built
binaries for common architectures are included in the installer. To rebuild
for your specific CPU, see [`core/c/jbigi/docs/README.md`](../core/c/jbigi/docs/README.md).

## Installing

### Headless (console mode)

```sh
java -jar i2pinstall.exe -console
# or
java -jar i2pinstall.jar -console
```

### GUI installer

```sh
java -jar i2pinstall.exe
# or
java -jar i2pinstall.jar
```

On Windows, double-click `i2pinstall.exe`.

### Updating an existing installation

Move `i2pupdate.zip` into the existing installation directory and restart.

## Running I2P

```sh
# Linux, BSD, Mac
sh i2prouter start

# Windows
I2P.exe
# or
i2prouter.bat

# Platforms without wrapper support
sh runplain.sh
```

## Installing as a system service (Linux)

I2P+ supports native systemd service management, falling back to init.d where systemd is not available.

### 1. Configure the run-as user

Edit the `i2prouter` script and set `RUNASUSER` to the unprivileged user that should own the I2P process:

```sh
RUNASUSER=i2p
```

### 2. Install the service

Must be run as root:

```sh
sudo sh i2prouter install
```

This registers the service and enables it at boot. On systemd systems, a unit file is created automatically.

### 3. Manage the service

| Action              | Command                            |
| ------------------- | ---------------------------------- |
| Start               | `sudo sh i2prouter start`          |
| Stop gracefully     | `sudo sh i2prouter graceful`       |
| Stop immediately    | `sudo sh i2prouter stop`           |
| Status              | `sudo sh i2prouter status`         |
| Uninstall           | `sudo sh i2prouter remove`         |

On systemd systems you can also use:

```sh
sudo systemctl start i2p
sudo systemctl stop i2p
sudo systemctl status i2p
sudo systemctl enable i2p
sudo systemctl disable i2p
```

## Windows service management

| Action                 | Command                              |
| ---------------------- | ------------------------------------ |
| Install as service     | `install_i2p_service_winnt.bat`      |
| Uninstall service      | `uninstall_i2p-service_winnt.bat`    |

## Uninstalling

```sh
rm -rf $I2PInstallDir ~/.i2p
```
