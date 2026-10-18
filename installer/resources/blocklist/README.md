# Tor exit-list blocklist scripts

These scripts regenerate `blocklist_tor.txt`, the list of Tor exit
addresses the router blocks. The router loads it at startup from its
installation directory (`$I2P/blocklist_tor.txt`) when
`router.blocklist.enable` and `router.blocklistTor.enable` are both true
(the default).

## Scripts

| Script                          | Sources                                          | Output                                                                                                                              |
| ------------------------------- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| `maketorblocklist-ipv4.sh`      | Tor Project bulk exit list                       | IPv4 only, bare addresses and collapsed consecutive ranges (`1.2.3.4`, `1.2.3.4-1.2.3.9`)                                           |
| `maketorblocklist-ipv4+ipv6.sh` | Tor Project bulk exit list + dan.me.uk exit list | IPv4 ranges and IPv6 addresses, each line prefixed `Tor Exit:`; IPv6 colons are rewritten to semicolons (the blocklist file format) |

Both write `blocklist_tor.txt` into the current directory and keep the
previous file as `blocklist_tor.txt.bak`. The first two lines of the
output are comments naming the source URL(s) and the generation date.

## Requirements

POSIX shell, `curl`, `awk`, `sort`. The dual-stack script additionally
needs GNU `grep` (it uses `-P` for the IPv4/IPv6 split).

## Usage

    ./maketorblocklist-ipv4.sh [--host HOST] [--port PORT]
    ./maketorblocklist-ipv4+ipv6.sh [--host HOST] [--port PORT]

### Proxy options

- Default proxy: `127.0.0.1:4444` (ipv4 script) or `127.0.0.1:4001`
  (dual-stack script).
- `--host` / `--port` override that default.
- `--port 0` disables proxying entirely: the script fetches directly,
  ignoring `--host` and any inherited `http_proxy` / `http_backup_proxy`
  environment variables.
- If the `http_proxy` environment variable is set (and `--port 0` was
  not given) it takes precedence over `--host`/`--port`.
- The dual-stack script verifies the proxy before downloading and falls
  back to `http_backup_proxy` if the primary is unreachable; the ipv4
  script has no verification or fallback step.

### Deploying the result

Run the script in the router's installation directory, or generate the
file elsewhere and copy it to `$I2P/blocklist_tor.txt`. The router reads
the file at startup, so restart the router (or wait for the next
restart) for a refreshed list to take effect. Files in the installation
directory are replaced on router updates — re-run the script after
updating.

## Notes

- The scripts are maintained by hand; they are not run by the router or
  the installer.
- If either download fails (e.g. dan.me.uk is unreachable), the
  dual-stack script exits before writing the output, leaving the previous
  `blocklist_tor.txt` (and its `.bak`) intact.
