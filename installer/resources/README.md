# Installer Resources (`resources/`)

Files that get bundled into the installed I2P package. These are the scripts, configurations, themes, and support files that end up in the user's I2P installation directory.

## Contents

| Directory               | Description                                              |
| ----------------------- | -------------------------------------------------------- |
| `unix/bash-completion/` | Shell completion scripts                                 |
| `blocklist/`            | IP blocklist files and update scripts                    |
| `certificates/`         | SSL/TLS certificates                                     |
| `eepsite/`              | Jetty configuration for the local eepsite                |
| `eepsite-jetty9.3/`     | Jetty 9.3 variant of eepsite config                      |
| `geoip/`                | GeoIP database files                                     |
| `initialNews/`          | First-run news content                                   |
| `locale/`               | Compiled translation bundles                             |
| `locale-man/`           | Manual page translations                                 |
| `macos/`                | macOS-specific scripts (launchd, service install)        |
| `man/`                  | Unix manual pages                                        |
| `package-lists/`        | Jetty package configuration                              |
| `portable/`             | Portable installation support                            |
| `proxy/`                | HTTP proxy configuration                                 |
| `readme/`               | First-run readme content                                 |
| `small/`                | Minimal installation variant                             |
| `themes/`               | Router console and i2psnark UI themes                    |
| `unix/`                 | Unix/Linux scripts (i2prouter, systemd, service install) |
| `windows/`              | Windows batch scripts and service helpers                |

## Key Files

- `unix/i2prouter` - Main router startup script (template)
- `unix/i2prouter.service` - Systemd service unit (template)
- `unix/install_i2p_service_unix` - Unix service installer
- `unix/runplain.sh` - Plain Java launcher (no wrapper)
- `unix/graceful_update` - Graceful update script
- `clients.config` - Client configuration
- `router.testnet.config` - Test network configuration
