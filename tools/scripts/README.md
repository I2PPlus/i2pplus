# tools/scripts/

Utility scripts for I2P+ development and administration.

## Build & Release

- **`build-deb.sh`** — Build a self-contained Debian package.
  `build-deb.sh [version] [pkg-temp-dir]`

  Run by hand from the project root with no arguments; the ant `buildDeb` target and
  the Debian rules call it with the version and the staging directory, and it prefers
  ant's `ANT_BASEDIR` when that is set. The payload directory is read from the build
  root rather than guessed, so `ant -Dbuild.root=` is honoured.

- **`ant-java.sh`** — Run ant with a specific Java version.
  `ant-java.sh [21|24|25] [ant-options]`

- **`build-wrapper-windows.sh`** — Cross-compile Tanuki Java Service Wrapper for Windows using mingw-w64.
  `build-wrapper-windows.sh /path/to/src [--clean] [--arch x64|win32] [--install-dir PATH] [--no-dll]`

- **`maven-dev-release.sh`** — Build and deploy a Maven development snapshot: runs `ant mavenLocal`, then renames the `pkg-mavencentral/` artifacts with the build number and installs them into `~/.m2`.
  `maven-dev-release.sh BUILD_NUMBER`

  The build number is required — the script exits with a usage message without it.
  `ant mavenLocal` needs `maven-ant-tasks` on ant's classpath.

- **`pack200.sh`** — Pack200-compress (or repack) the update payload staged in `pkg-temp`. Called by the ant `pack200` / `repack200` targets and the Gradle `pack200Updater` task.

- **`update-gradle.sh`** — Update the Gradle wrapper to the latest stable release and regenerate `gradlew` / `gradlew.bat`.
  `update-gradle.sh [--dry-run] [--yes]`

- **`update-tomcat.sh`** — Check for a newer Jasper/Tomcat and update the checked-in jars in place. The jars live in `apps/jetty/apache-tomcat-<version>/`, so an update touches three things at once: the jars, the version pins (`tomcat.ver` in `build.xml`, `tomcatVersion` in `gradle.properties`), and the `NOTICE` that quotes the copyright year. Every shipped jar and the `NOTICE` are fetched and verified against Maven Central / the Tomcat git tag rather than copied out of a tarball.
  `update-tomcat.sh [check|check-version|verify|advisories|apply [VER]|list|selftest]`

  ```
  update-tomcat.sh                # check: report the pin and what is available
  update-tomcat.sh check          # same; exit 3 when an update is available
                                  # or a stale version directory is tracked
  update-tomcat.sh check-version  # one line for a build log; exit 3 when behind,
                                  # 4 when it cannot tell, never fatal
  update-tomcat.sh verify         # verify the checked-in jars against Maven Central
  update-tomcat.sh advisories     # advisories between the pin and the candidate
  update-tomcat.sh apply [VER]    # update in place (default: latest compatible)
  update-tomcat.sh list           # print the shipped-jar allowlist
  update-tomcat.sh selftest       # offline tests of the logic
  ```

  Only one version directory is checked in at a time: `apply` installs the new one,
  deletes the old one from the worktree and stages both halves, because an unstaged
  deletion leaves the previous version's jars tracked and the next checkout ships two
  versions. `check` and `verify` report that state as DRIFT. The ant
  `checkLibraryVersions` target calls `check-version` once per build and cannot fail
  on it; `-Dupdate.tomcat.check=false` skips the network probe (see
  `build.properties`).

- **`update-wrapper.sh`** — Download and update Tanuki Java Service Wrapper binaries.
  `update-wrapper.sh --version VERSION [--file ZIP] [--dry-run]`

## Documentation

- **`build-devdocs.py`** — Consolidate the markdown tree (`README.md`, `docs/*.md`, package READMEs) into HTML under `dist/devdocs/`, with heading anchors and a navigation index.

- **`bundle-messages.sh`** — Compile ResourceBundle `.class` files and gettext `.mo` files from the `.po` translations, and print a per-language coverage summary (`--no-coverage` suppresses it). Requires `xgettext` and `msgfmt`.
  `bundle-messages.sh --dir <cfgdir> [--cfg <name>] [--build-dir <dir>] [-p|--poupdate] [--no-coverage]`

  Called from every module that ships translations, in both builds: the `bundle*`
  targets in `core/java`, `router/java`, `apps/{ministreaming,i2ptunnel,i2psnark,
  routerconsole,susidns,susimail,addressbook,desktopgui}/…/build.xml` and the matching
  `bundleMessages` tasks in their `build.gradle`, plus `installer/resources/locale`
  for the gettext catalogues. It exits fast when every locale class is current, which
  is what makes it cheap to call from a build on every run.

## Code Quality

- **`align-md-tables.py`** — Align markdown table columns and trim trailing whitespace. Supports CJK double-width characters, recursive directory scanning, and CI check mode.
  `align-md-tables.py [-r] [--dry-run] [--check] PATH [PATH ...]`

- **`check-build-files.py`** — Validate the ant and Gradle build files: 18 rules covering malformed XML, duplicate or self-referential properties, `depends`/`<ant>` calls naming a target that does not exist, unused targets, build XML left in a comment, duplicate classpath and fileset patterns, and Gradle brace balance, duplicate dependencies and duplicate tasks. Any finding is a failure — there is no baseline. Exits 0 clean, 1 findings, 3 tool failure.
  `check-build-files.py [--verbose] [--json] [--rule RULE] [--list] [--selftest] [--ant-report FILE] [FILE ...]`

  ```
  check-build-files.py                    # check every tracked build file
  check-build-files.py --verbose          # list each finding, not just the tally
  check-build-files.py --rule unused-target --verbose
  check-build-files.py --list             # the rule names and what each one means
  check-build-files.py --selftest         # prove every rule still detects its fixture
  check-build-files.py build.xml          # one file, with the rest of the tree as context
  ```

  `--selftest` runs a bad/good fixture pair per rule, so a rule that stops checking
  fails the run instead of reporting a clean tree. `--ant-report FILE` appends the
  finding count as `buildfiles.check.count` and a per-rule tally as
  `buildfiles.check.rules`, so an ant target can fail with a message naming what was
  found rather than `exec returned: 1`.

- **`check-js-syntax.sh`** — Check JavaScript files for syntax errors using `node --check`. Supports recursive directory scanning and quiet mode.
  `check-js-syntax.sh [-p PATH] [-q] [-h]`

  ```
  check-js-syntax.sh                  # check all .js files below CWD
  check-js-syntax.sh -p ../apps/      # check apps/ from tools/scripts/
  check-js-syntax.sh -p ../apps/foo.js # single file
  check-js-syntax.sh -q -p ../apps/   # quiet: summary only
  ```

  If the target path falls outside the repo root, a warning is printed.

- **`checkjsps.py`** — Build-time JSP validation, one entry point for the three webapps that ship JSPs (`susidns`, `routerconsole`, `i2ptunnel`). Wired into their ant and Gradle war targets alongside uglifyjs and htmlmin: skipped when the environment cannot run it, fails the build when it finds a problem.
  `checkjsps.py --app NAME [--webapp DIR] [--package JSP_PKG] [--classes PATH]... [--classpath PATH]... [--tomcat DIR] [--out DIR] [--compile] [--skip-audit]`

  Two phases, because the two kinds of failure are different. `audit` is the default
  and all the builds need it: it runs `jsp-audit.py` and `jsp-security-audit.py`.
  `--compile` adds JspC generate-only plus `javac` of the generated sources — JSP
  syntax, an EL that does not parse, a `<jsp:useBean>` class that is not on the
  classpath, Java type errors in scriptlets. The builds already run JspC themselves
  (`precompilejsp` / `precompileJsp`) with `failonerror`, so they do not need that
  phase; it is there so a manual run can do both at once. The Jasper and servlet-api
  jars are found under `apps/jetty` and added automatically. Exit 0 when nothing
  failed, including when a phase was skipped.

- **`jsp-audit.py`** — Audit the webapp JSPs for the defects the JSP compiler cannot see, chiefly everything Jasper resolves by reflection at runtime: a `<jsp:getProperty>` or `${bean.prop}` naming a property the bean no longer has (compiles clean, 500s in the browser), a `<jsp:useBean id>` used before or without being declared, an include or `href` to a `.jsp` that does not exist, an EL root nothing ever sets, a `_t()`/`_x()`/`ngettext()` msgid missing from the app's `.po` files, a `request.getParameter()` written to the response unencoded, unbalanced HTML markup (an unclosed `<form>`, a stray `</a>`, a `<div>` opened under one scriptlet guard and closed under another), and the house hygiene rules (pageEncoding on every page, no trailing whitespace, no tabs).
  `jsp-audit.py [--verbose] [--json] [--rule RULE] [--selftest] [--ant-report FILE]`

  Every run is a fresh pass over the current tree — no baseline file, and any finding
  is exit 1. `--selftest` runs the markup-rule fixtures.

- **`jsp-security-audit.py`** — Static security audit of the same JSP trees, kept separate from `jsp-audit.py` on purpose: that one asks whether a page will run, this one asks what reaches the browser unescaped and whether every state change is authenticated. Four rules — `unencoded-request-param`, `csrf-check-fails-open`, `form-post-without-nonce`, `script-output-unescaped`.
  `jsp-security-audit.py [path ...] [--verbose] [--json] [--rule RULE] [--app APP]`

  What needs judgement is reported as a blind spot rather than silently accepted: a
  value the page never assigns (a `pageContext` attribute set in Java) is not a
  finding, and a `${param.x}` hit is shown with the surrounding context, because the
  console's own filters decide whether it is reachable. No baseline; any finding is a
  non-zero exit.

- **`comprehensive_pmd_fix.sh`** — Fix common PMD violations across the codebase. Internal; runs `ensure-pmd.sh` first.

- **`ensure-pmd.sh`** — Download and extract PMD if not present. Internal; called by other PMD scripts.

- **`fix_pmd_violations.sh`** — Fix PMD violations (subset of comprehensive). Internal; runs `ensure-pmd.sh` first.

- **`remove_unused_imports.sh`** — Remove unused Java imports.
  `remove_unused_imports.sh {path}`

- **`run-spotbugs.sh`** — Run SpotBugs static analysis.
  `run-spotbugs.sh [-o FILE] [-f FORMAT] [-e EFFORT] [-l LEVEL] [FILES]`

## Testing

- **`run-tests.sh`** — The test entry point: ensures the test dependencies are present, builds each module's dependencies, runs the JUnit suites through the modules' own `junit.test` targets, then renders `dist/test-report.html` with `tools/test/unit-tests-to-html.py`. This is what `ant test` shells out to, and what CI runs.
  `run-tests.sh [--logs] [--logs-dir DIR] [--test PATTERN] [SUITE]`

  ```
  run-tests.sh                        # everything: the 12 JUnit suites (core,
                                      # ministreaming, streaming, router, i2ptunnel,
                                      # addressbook, routerconsole, i2psnark,
                                      # susimail, sam, pack200, jrobin), then the
                                      # node JS harness
  run-tests.sh core                   # one suite (the first non-flag argument); an
                                      # unknown name is an error, not a full run
  run-tests.sh js                     # the node JS harness only, no Java build
  run-tests.sh --logs all             # keep ant's console output and leave every
                                      # per-module logfile behind (what CI should use)
  run-tests.sh --logs-dir DIR         # where those logfiles go
                                      # (default: /tmp/test-i2p/logs)
  run-tests.sh --test '**/FooTest.java' router   # narrow to test sources matching a
                                      # pattern, several as a comma-separated list; a
                                      # pattern matching no test is an error, not a pass
  ```

  A flag may appear anywhere. The forked test JVM is Java 8, so a test may not use
  APIs or providers newer than that even though the build itself runs on a newer JDK.
  ScalaTest is not part of this script — it runs through the modules' `scalatest.test`
  targets.

- **JS tests** — not in this directory. The node harness is `tools/test/js/`, driven
  by `ant test-js` and `run-tests.sh js`; see
  [`tools/test/README.md`](../test/README.md).

- **`ensure-testdeps.sh`** — Download JUnit, Hamcrest, Mockito and ScalaTest into `tools/test/`, tracking versions per group. Called automatically by `ant test`.
  `ensure-testdeps.sh [--force] [--check] [--quiet]`

- **`i2prouter-test.sh`** — Test harness for `installer/resources/platform-specific/unix/i2prouter`, the daemon control script. Hermetic by design: no router is started, no network is touched, and every artefact is written under a temporary directory rather than into the workspace.
  `i2prouter-test.sh [PATH] [--shellcheck]`

  With no argument it checks the source copy; `PATH` checks a specific copy, so an
  installed, already-substituted build can be verified before it is put in place.
  `--shellcheck` adds the linter when it is installed. Exit 0 only when every check
  passes.

- **`asn-smoke-test.py`** — Smoke-test the ASN / GeoIP display by inspecting the console `/profiles` page.

## Diagnostics

- **`heap-report.sh`** — Generate heap/thread dumps for memory leak analysis.
  `heap-report.sh [pid|path.hprof]`

  Collects thread dump, class histogram, heap dump, GC info, and optionally runs
  dominator tree analysis (via jhat). Output written to `/tmp/dump-i2p/<timestamp>/`.

- **`i2p-diag-dump.sh`** — One-shot forensic capture from a running router: threads, stats, config, and logs.

- **`i2p-cpu-prof.sh`** — Attribute router CPU to threads and Java frames; the CPU-focused companion to `i2p-diag-dump.sh`.

## Translation

- **`cleanup_po.sh`** — Clean up PO translation files.
  `cleanup_po.sh <directory> [--no-wrap] [--no-location] [--no-comments]`

- **`poupdate-man.sh`** — Generate translated man pages from PO files. Internal; run from `installer/resources/`.

- **`translation_status.sh`** — Show translation status for a locale directory.
  `translation_status.sh <folder_path>`

- **`validate-po.sh`** — Validate every `.po` file for structural integrity with `msgfmt`.

- **`validate-po.py`** — Validate `.po` files with `polib`: syntax, duplicate `msgid`s, orphan bare-quote lines, and unbalanced `{0}` placeholders.

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

