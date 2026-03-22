# scripts/

Utility scripts for I2P+ development and administration.

## Build & Release

- **`ant-java.sh`** — Run ant with a specific Java version.
  `ant-java.sh [21|24|25] [ant-options]`

- **`build-wrapper-windows.sh`** — Cross-compile Tanuki Java Service Wrapper for Windows using mingw-w64.
  `build-wrapper-windows.sh /path/to/src [--clean] [--arch x64|win32] [--install-dir PATH] [--no-dll]`

- **`maven-dev-release.sh`** — Build and deploy a Maven development release.
  Run from project root.

- **`update-wrapper.sh`** — Download and update Tanuki Java Service Wrapper binaries.
  `update-wrapper.sh --version VERSION [--file ZIP] [--dry-run]`

## Code Quality

- **`check-js-syntax.sh`** — Check JavaScript files for syntax errors using `node --check`. Supports recursive directory scanning and quiet mode.
  `check-js-syntax.sh [-p PATH] [-q] [-h]`

  ```
  check-js-syntax.sh                  # check all .js files below CWD
  check-js-syntax.sh -p ../apps/      # check apps/ from scripts/
  check-js-syntax.sh -p ../apps/foo.js # single file
  check-js-syntax.sh -q -p ../apps/   # quiet: summary only
  ```

  If the target path falls outside the repo root, a warning is printed.

- **`comprehensive_pmd_fix.sh`** — Fix common PMD violations across the codebase. Internal; runs `ensure-pmd.sh` first.

- **`ensure-pmd.sh`** — Download and extract PMD if not present. Internal; called by other PMD scripts.

- **`fix_pmd_violations.sh`** — Fix PMD violations (subset of comprehensive). Internal; runs `ensure-pmd.sh` first.

- **`remove_unused_imports.sh`** — Remove unused Java imports.
  `remove_unused_imports.sh {path}`

- **`run-spotbugs.sh`** — Run SpotBugs static analysis.
  `run-spotbugs.sh [-o FILE] [-f FORMAT] [-e EFFORT] [-l LEVEL] [FILES]`

## Development Tools

- **`align-md-tables.py`** — Align markdown table columns and trim trailing whitespace. Supports CJK double-width characters, recursive directory scanning, and CI check mode.
  `align-md-tables.py [-r] [--dry-run] [--check] PATH [PATH ...]`

## Translation

- **`cleanup_po.sh`** — Clean up PO translation files.
  `cleanup_po.sh <directory> [--no-wrap] [--no-location] [--no-comments]`

- **`extract_untranslated.py`** — Extract untranslated strings from a PO file.
  `extract_untranslated.py <file.po>`

- **`poupdate-man.sh`** — Generate translated man pages from PO files. Internal; run from `installer/resources/`.

- **`translation_status.sh`** — Show translation status for a locale directory.
  `translation_status.sh <folder_path>`

## Administration

- **`i2p-sessionban-nftables.py`** — Sync I2P+ session bans to nftables sets. Uses nftables named sets with `interval` flag for O(log n) lookups. Persists bans across reboots by saving the ruleset and adding an `include` to `/etc/nftables.conf`. Requires `nft`.
  [Documentation](../docs/i2p-sessionban-nftables.md)

  ```
  i2p-sessionban-nftables.py [--list] [--list-summary] [--clean] [--reset]
    [--duration 7d|168h|1w|forever] [--window-hours N] [--workers N]
    [--ipv4-only] [--dry-run] [-l LOG] [--ban-dir PATH]
    [--tracking-file PATH] [--lock-file PATH] [--ruleset-file PATH]
  ```

  Default paths:

| Path                                   | Description                |
| -------------------------------------- | -------------------------- |
| `/home/i2p/.i2p/sessionbans/`          | Sessionban files directory |
| `/home/i2p/i2p-sessionbans.txt`        | Ban tracking file          |
| `/var/log/i2p-sessionban-iptables.log` | Log file                   |
| `/var/run/i2p-sessionban.lock`         | Lock file                  |
| `/etc/nftables/i2p-bans.nft`           | Saved ruleset              |
| `/etc/nftables.conf`                   | nftables boot config       |

## JavaScript

- **`javadoc-jav8-fix.js`** — Fix Javadoc rendering for Java 8 compatibility. Internal; automatically included when Javadoc is compiled.
