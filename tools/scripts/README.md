# tools/scripts/

Utility scripts for I2P+ development and administration.

## Build & Release

- **`build-deb.sh`** — Build a self-contained Debian package.
  Run from project root.

- **`ant-java.sh`** — Run ant with a specific Java version.
  `ant-java.sh [21|24|25] [ant-options]`

- **`build-wrapper-windows.sh`** — Cross-compile Tanuki Java Service Wrapper for Windows using mingw-w64.
  `build-wrapper-windows.sh /path/to/src [--clean] [--arch x64|win32] [--install-dir PATH] [--no-dll]`

- **`compare-updaters.sh`** — Compare the ant and gradle updater payloads (and the current branch against mainline) as an HTML size table in `dist/compare-updaters.html`.

- **`compress-jsp.sh`** — Minify a JSP file with `html-minifier-terser`, leaving `<% %>` fragments intact.
  `compress-jsp.sh <file>`

- **`download-ant.sh`** — Download the minimal Ant jars used by the build into `tools/ant/`.

- **`run-ant.sh`** — Run Ant for I2P+ builds, downloading the jars first if they are missing.

- **`maven-dev-release.sh`** — Build and deploy a Maven development release.
  Run from project root.

- **`pack200.sh`** — Pack200-compress (or repack) the update payload staged in `pkg-temp`. Called by the ant `pack200` / `repack200` targets and the Gradle `pack200Updater` task.

- **`update-gradle.sh`** — Update the Gradle wrapper to the latest stable release and regenerate `gradlew` / `gradlew.bat`.
  `update-gradle.sh [--dry-run] [--yes]`

- **`update-wrapper.sh`** — Download and update Tanuki Java Service Wrapper binaries.
  `update-wrapper.sh --version VERSION [--file ZIP] [--dry-run]`

## Documentation

- **`build-devdocs.py`** — Consolidate the markdown tree (`README.md`, `docs/*.md`, package READMEs) into HTML under `dist/devdocs/`, with heading anchors and a navigation index.

- **`bundle-messages.sh`** — Compile ResourceBundle `.class` files and gettext `.mo` files from the `.po` translations. Called from the module build files (`core/java/build.xml`, `apps/desktopgui/build.xml`).

## Code Quality

- **`align-md-tables.py`** — Align markdown table columns and trim trailing whitespace. Supports CJK double-width characters, recursive directory scanning, and CI check mode.
  `align-md-tables.py [-r] [--dry-run] [--check] PATH [PATH ...]`

- **`check-js-syntax.sh`** — Check JavaScript files for syntax errors using `node --check`. Supports recursive directory scanning and quiet mode.
  `check-js-syntax.sh [-p PATH] [-q] [-h]`

  ```
  check-js-syntax.sh                  # check all .js files below CWD
  check-js-syntax.sh -p ../apps/      # check apps/ from tools/scripts/
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

## Testing

- **`run-tests.sh`** — Wrapper for `ant test`: ensures the test dependencies are present, builds the modules, then runs all 12 suites in parallel and renders `dist/test-report.html`.
  `run-tests.sh [core|router]`

- **`ensure-testdeps.sh`** — Download JUnit, Hamcrest, Mockito and ScalaTest into `tools/test/`, tracking versions per group. Called automatically by `ant test`.
  `ensure-testdeps.sh [--force] [--check] [--quiet]`

- **`i2prouter-test.sh`** — Test harness for `installer/resources/platform-specific/unix/i2prouter`, the daemon control script.

- **`streaming-test.sh`** — Fetch one or more eepsites through the HTTP proxy and report streaming-layer health indicators.

- **`asn-smoke-test.py`** — Smoke-test the ASN / GeoIP display by inspecting the console `/profiles` page.

- **`pool-stability-test.py`** — Watch tunnel pool health and auto-tune router PROPs to find better settings.

## Diagnostics

- **`heap-report.sh`** — Generate heap/thread dumps for memory leak analysis.
  `heap-report.sh [pid|path.hprof]`

  Collects thread dump, class histogram, heap dump, GC info, and optionally runs
  dominator tree analysis (via jhat). Output written to `/tmp/dump-i2p/<timestamp>/`.

- **`i2p-diag-dump.sh`** — One-shot forensic capture from a running router: threads, stats, config, and logs.

- **`i2p-cpu-prof.sh`** — Attribute router CPU to threads and Java frames; the CPU-focused companion to `i2p-diag-dump.sh`.

- **`monitor.sh`** — Every 10 minutes, tally tunnel build successes, failures, timeouts and disconnects from the current router log.

- **`router-stats.py`** — Read live values off the console `/stats` page.

- **`router-stats-sample.py`** — Sample router stats on a schedule and report a locally computed mean.

- **`transit-stats.py`** — Sample transit-participation and build statistics into a CSV trend.

- **`graph-stat-period-check.py`** — Check every stat rendered on the graphs page for a 60-second period.

- **`stat-period-audit.py`** — Find `RateStat` registrations whose periods lack a 60-second window.

- **`normalize-rdns-cache.py`** — Rewrite hostnames in the persistent RDNS cache using the same `GeoIP.normalizeOrgName()` logic as the running router.

## Translation

- **`cleanup_po.sh`** — Clean up PO translation files.
  `cleanup_po.sh <directory> [--no-wrap] [--no-location] [--no-comments]`

- **`extract_untranslated.py`** — Extract untranslated strings from a PO file.
  `extract_untranslated.py <file.po>`

- **`poupdate-man.sh`** — Generate translated man pages from PO files. Internal; run from `installer/resources/`.

- **`translate_po.py`** — Translate the untranslated entries of `messages_<lang>.po` for the given languages.

- **`batch_translate.py`** — Translate all `msgstr == msgid` entries in `.po` files using batched API calls.

- **`translation_status.sh`** — Show translation status for a locale directory.
  `translation_status.sh <folder_path>`

- **`validate-po.sh`** — Validate every `.po` file for structural integrity with `msgfmt`.

- **`validate-po.py`** — Validate `.po` files with `polib`: syntax, duplicate `msgid`s, orphan bare-quote lines, and unbalanced `{0}` placeholders.

- **`validate_translations.py`** — Check translated entries for placeholders, HTML tags, empty strings and back-translation consistency; reports for manual review.

## Administration

- **`i2p-sessionban-nftables.py`** — Sync I2P+ session bans to nftables sets. Uses nftables named sets with `interval` flag for O(log n) lookups. Persists bans across reboots by saving the ruleset and adding an `include` to `/etc/nftables.conf`. Requires `nft`.
  [Documentation](../../docs/i2p-sessionban-nftables.md)

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

## Javadoc

The Javadoc output is post-processed by `tools/javadoc/jdk8/javadoc-postprocess.sh`,
run by the ant `javadoc` target, and `tools/javadoc/jdk8/navigation-jav8.js` is
copied over the generated `navigation.js` so the frames work on Java 8 browsers.
See [`tools/javadoc/`](../javadoc/).

## Development Tools

- **`strata-ask.py`** — Query the local Strata model as a review companion.
