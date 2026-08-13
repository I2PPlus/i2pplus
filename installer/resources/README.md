# Installer Resources (`resources/`)

Files that get bundled into the installed I2P package. These are the scripts, configurations, themes, and support files that end up in the user's I2P installation directory.

## Contents

| Directory              | Description                                              |
| ---------------------- | -------------------------------------------------------- |
| `platform-specific/`   | Platform-specific resources (see below)                  |
| `blocklist/`           | IP blocklist files and update scripts                    |
| `certificates/`        | SSL/TLS certificates                                     |
| `console/`             | Router console resources (themes, proxy, readme)         |
| `eepsite/`             | Jetty configuration for the local eepsite                |
| `eepsite-jetty9.3/`    | Jetty 9.3 variant of eepsite config                      |
| `geoip/`               | GeoIP database files                                     |
| `initialNews/`         | First-run news content                                   |
| `locale/`              | Compiled translation bundles                             |
| `package-lists/`       | Jetty package configuration                              |
| `small/`               | Minimal installation variant                             |

## Platform-Specific Resources

Platform-specific resources are organized under `platform-specific/`:

| Directory                                 | Description                              |
| ----------------------------------------- | ---------------------------------------- |
| `platform-specific/windows/`              | Windows batch scripts, icons, service    |
| `platform-specific/macos/`                | macOS scripts (launchd, service install) |
| `platform-specific/unix/`                 | Unix/Linux scripts (i2prouter, systemd)  |
| `platform-specific/unix/man/`             | Unix manual pages                        |
| `platform-specific/unix/locale-man/`      | Manual page translations                 |
| `platform-specific/unix/bash-completion/` | Shell completion scripts                 |

## Console Resources

Console-specific resources are organized under `console/`:

| Directory                       | Description                                     |
| ------------------------------- | ----------------------------------------------- |
| `console/themes/`               | Router console and i2psnark UI themes           |
| `console/themes/console/`       | Console themes (classic, dark, light, midnight) |
| `console/themes/snark/`         | i2psnark themes                                 |
| `console/themes/susimail/`      | Susimail themes                                 |
| `console/proxy/`                | HTTP proxy error message templates              |
| `console/readme/`               | First-run readme content                        |

## Key Files

- `platform-specific/unix/i2prouter` - Main router startup script (template)
- `platform-specific/unix/i2prouter.service` - Systemd service unit (template)
- `platform-specific/unix/runplain.sh` - Plain Java launcher (no wrapper)
- `platform-specific/unix/graceful_update` - Graceful update script
- `clients.config` - Client configuration
- `router.testnet.config` - Test network configuration