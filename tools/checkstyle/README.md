# Checkstyle for I2P+

Static analysis for Java code style, imports, and naming conventions.
Complements PMD (which covers error-prone patterns, performance, threading).

## Quick Start

```bash
ant checkstyle              # Generate report → dist/checkstyle.html
ant checkstyle-local        # Report with clickable local file links → dist/checkstyle-local.html
```

## Fix Style Violations

```bash
# Dry run (preview changes)
python3 tools/checkstyle/fix-style.py -p core/java/src/net/i2p -x dist/checkstyle.xml -n

# Fix a directory
python3 tools/checkstyle/fix-style.py -p router/java/src -x dist/checkstyle.xml

# Fix without Checkstyle XML (runs checkstyle internally)
python3 tools/checkstyle/fix-style.py -p apps/i2psnark/java

# Fix ALL files (ignore exclusions — includes third-party/build output)
python3 tools/checkstyle/fix-style.py -p . --no-exclude
```

Exclusions from `checkstyle.xml` are applied by default. Use `--no-exclude` to process all files.

The fix script runs these passes:

| Pass   | What it fixes                                            |
| ------ | -------------------------------------------------------- |
| 1      | Unused imports (google-java-format `--fix-imports-only`) |
| 2      | Leading tabs → 4 spaces                                  |
| 3      | Brace and paren whitespace (`{ x }` → `{x}`)             |
| 4      | Empty statements (`;;` → `;`)                            |
| 5      | Indentation (iterative until convergence)                |
| 6      | Trailing whitespace                                      |
| 7      | Missing newline at end of file                           |

## Files

| File                     | Purpose                                                                                                               |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| `checkstyle-all.jar`     | Checkstyle 13.3.0 binary                                                                                              |
| `checkstyle.xml`         | Ruleset config. Modules marked `NOISY` produce many violations in legacy code — remove them by searching for `NOISY`. |
| `checkstyle-to-html.py`  | Converts XML output to HTML report                                                                                    |
| `fix-style.py`           | Auto-fixes style violations (needs `google-java-format.jar` for import cleanup)                                       |
| `download-checkstyle.sh` | Downloads/updates the Checkstyle JAR                                                                                  |

## Exclusions

`checkstyle.xml` is the single source of truth for exclusions.
Both the Checkstyle scan and `fix-style.py` read from `BeforeExecutionExclusionFileFilter` patterns.

- `*_jsp.java` — generated JSP files
- `*/WEB-INF/*` — compiled JSPs
- `*/build/*` — build output
- `*/jetty/*`, `*/wrapper/*` — third-party
- `*/com/maxmind/*`, `*/com/freenetproject/*`, `*/org/freenetproject/*` — third-party
- `*/com/southernstorm/*` — noise protocol library
- `*/org/apache/*`, `*/org/minidns/*` — third-party
- `*/org/bouncycastle/*`, `*/org/cybergarage/*` — third-party
- `*/com/mpatric/*` — mp3agic library
- `*/metanotion/*` — third-party
- `*/vuze/*`, `*/ndt/*` — third-party
- `*/pack200/*`, `*/jrobin/*` — third-party
- `*/UPnP.java` — Freenet-derived, tab-based style

## Report Output

Reports go to `dist/`. Use `ant distclean` to remove them.

| File                         | Contents                                   |
| ---------------------------- | ------------------------------------------ |
| `dist/checkstyle.html`       | HTML report (shared with team)             |
| `dist/checkstyle-local.html` | HTML report with clickable `file://` links |
| `dist/checkstyle.xml`        | Raw XML (retained for `fix-style.py -x`)   |
