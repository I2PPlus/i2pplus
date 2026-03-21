# I2P+ Graceful Update Script (Linux only)

Update I2P+ via the console using `./graceful_update`. Designed for updating a running I2P+ instance without manual intervention. Also gracefully restarts the router after download.

Can be run without I2P connectivity using clearnet fallbacks.

```
./graceful_update [release] [options]
```

## Releases

| Flag     | Description                               |
| -------- | ----------------------------------------- |
| `stable` | Download the latest stable release        |
| `devel`  | Download the latest developmental release |

## Examples

```sh
# Stable release via I2P (default)
./graceful_update stable

# Devel release via any available method
./graceful_update devel --any

# Stable release via clearnet
./graceful_update stable --clear

# Download only, no restart
./graceful_update stable -nr

# Dry run (show commands without executing)
./graceful_update stable -n

# Custom URL
./graceful_update -u https://example.com/i2pupdate.zip
```

## Download Sources

| Flag      | Source                           |
| --------- | -------------------------------- |
| `--i2p`   | skank.i2p (default)              |
| `--b32`   | skank.i2p B32 address (fallback) |
| `--tor`   | I2P+ .onion address              |
| `--clear` | GitLab.com/I2P.Plus              |
| `--any`   | Try all methods sequentially     |

Multiple sources can be combined: `./graceful_update stable --i2p --b32 --tor`

## Downloaders

| Flag   | Downloader       |
| ------ | ---------------- |
| `-e`   | eepget (default) |
| `-c`   | curl             |
| `-w`   | wget             |

Multiple downloaders can be combined: `./graceful_update stable -e -c`

## Options

| Flag           | Description                                     |
| -------------- | ----------------------------------------------- |
| `--help`, `-h` | Show help                                       |
| `-q`           | Quiet mode (overrides `-v` and `-d`)            |
| `-v`           | Verbose output (default)                        |
| `-d`           | Debug output                                    |
| `-nr`          | Download only, no restart                       |
| `--restart`    | Hard restart (don't wait for tunnels to expire) |
| `-n`           | Dry run                                         |
| `-u URL`       | Custom download URL                             |
| `-p PROXY`     | Proxy (`[http(s)://]ip:port`)                   |
| `-r N`         | Retries (default: 3)                            |
| `-t N`         | Timeout in seconds (default: 120)               |
| `--ssl`        | Use HTTPS for curl/wget proxy                   |
| `--insecure`   | Allow insecure TLS (custom URLs)                |
| `--pid FILE`   | Custom PID file                                 |
| `--cron`       | Only download if header dates differ            |

## Default Behavior

Without any flags, `./graceful_update` uses eepget, curl, and wget to download the stable release from skank.i2p, then gracefully restarts the router.

Default proxy: `127.0.0.1:4444`

## Notes

- Either `stable` or `devel` must be specified unless `-u` is used
- `-e`, `-c`, and `-w` can be combined
- `--i2p`, `--b32`, `--tor`, and `--clear` can be combined
- `-p` proxy setting is used exactly as specified
- For curl SOCKS5 proxy: `socks5h://host:port`
- Edit `./graceful_update` for advanced configuration
