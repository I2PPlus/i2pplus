# scripts/

Utility scripts for I2P+ development and administration.

## Build & Release

| Script | Description | Usage |
|---|---|---|
| `ant-java.sh` | Run ant with a specific Java version | `ant-java.sh [21\|24\|25] [ant-options]` |
| `build-wrapper-windows.sh` | Cross-compile Tanuki Java Service Wrapper for Windows using mingw-w64 | `build-wrapper-windows.sh /path/to/src [--clean] [--arch x64\|win32] [--install-dir PATH] [--no-dll]` |
| `maven-dev-release.sh` | Build and deploy a Maven development release | Run from project root |
| `update-wrapper.sh` | Download and update Tanuki Java Service Wrapper binaries | `update-wrapper.sh --version VERSION [--file ZIP] [--dry-run]` |

## Code Quality

| Script | Description | Usage |
|---|---|---|
| `comprehensive_pmd_fix.sh` | Fix common PMD violations across the codebase | Internal; ensure-pmd.sh runs first |
| `ensure-pmd.sh` | Download and extract PMD if not present | Internal; called by other PMD scripts |
| `fix_pmd_violations.sh` | Fix PMD violations (subset of comprehensive) | Internal; ensure-pmd.sh runs first |
| `remove_unused_imports.sh` | Remove unused Java imports | `remove_unused_imports.sh {path}` |
| `run-spotbugs.sh` | Run SpotBugs static analysis | `run-spotbugs.sh [-o FILE] [-f FORMAT] [-e EFFORT] [-l LEVEL] [FILES]` |

## Translation

| Script | Description | Usage |
|---|---|---|
| `cleanup_po.sh` | Clean up PO translation files | `cleanup_po.sh <directory> [--no-wrap] [--no-location] [--no-comments]` |
| `extract_untranslated.py` | Extract untranslated strings from a PO file | `extract_untranslated.py <file.po>` |
| `poupdate-man.sh` | Generate translated man pages from PO files | Internal; run from installer/resources/ |
| `translation_status.sh` | Show translation status for a locale directory | `translation_status.sh <folder_path>` |

## Administration

| Script | Description | Usage |
|---|---|---|
| `i2p-sessionban-iptables.py` | Sync I2P session bans to iptables rules | `i2p-sessionban-iptables.py [--list] [--list-summary] [--clean] [--duration 7d\|168h\|1w\|forever] [--window-hours N] [--workers N] [--ipv4-only] [--dry-run] [-l LOG] [--ban-dir PATH] [--tracking-file PATH] [--lock-file PATH]` |
| `i2p-sessionban-nftables.py` | Sync I2P session bans to nftables sets | Same CLI as the iptables version plus `--ruleset-file PATH`. Significantly more efficient and performant for large ban lists — uses nftables named sets with `interval` flag for O(1) lookups instead of iterating thousands of individual iptables rules. Drop-in replacement; same tracking file format. Persists bans across reboots by saving the nftables ruleset (default: `/etc/nftables/i2p-bans.nft`) and adding an `include` directive to `/etc/nftables.conf` if not already present. `--clean` purges all bans and removes the ruleset file and include. Migrates existing iptables `I2P-BANNED` rules on first run. Requires `nft`. |

### Session Ban Default Paths

| Path | Description |
|---|---|
| `/home/i2p/.i2p/sessionbans/` | Sessionban files directory |
| `/home/i2p/i2p-sessionbans.txt` | Ban tracking file (IP\|timestamp) |
| `/var/log/i2p-sessionban-iptables.log` | Log file (both scripts) |
| `/var/run/i2p-sessionban.lock` | Lock file |
| `/etc/nftables/i2p-bans.nft` | Saved ruleset (nftables only) |
| `/etc/nftables.conf` | nftables boot config; auto-updated with include for ruleset |

## JavaScript

| Script | Description | Usage |
|---|---|---|
| `javadoc-jav8-fix.js` | Fix Javadoc rendering for Java 8 compatibility | Internal; automatically included when Javadoc is compiled |
