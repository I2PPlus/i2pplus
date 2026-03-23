# PMD Static Analysis for I2P+
#### https://pmd.github.io/ | [License](../../docs/licenses/LICENSE-pmd.txt): BSD-4-Clause + Apache 2.0

## Ant Targets

| Target         | Description                              |
| -------------- | ---------------------------------------- |
| `ant pmd`      | Run both Java and JavaScript analysis    |
| `ant pmd-java` | Analyze Java source (core, router, apps) |
| `ant pmd-js`   | Analyze JavaScript source (apps/)        |

Reports go to `dist/pmd-java.html` and `dist/pmd-js.html`. PMD auto-downloads on first run. Requires Java 11+ (auto-detected; override with `pmd.java=/path/to/java` in `override.properties`).

## Rulesets

### Java — `ruleset-java.xml`

Focused ruleset for I2P+ production code. Includes:

- **PMD categories**: Security, Error Prone, Multithreading (with noisy rules excluded)
- **jPinpoint rules**: Performance, threading, memory, and code quality rules from [PMD-jPinpoint-rules](https://github.com/jborgers/PMD-jPinpoint-rules) (noisy rules excluded)

Third-party code is excluded via `<exclude-pattern>`: bouncycastle, apache, cybergarage, maxmind, southernstorm, rrd4j, pack200, mp3agic, gnu, freenet, zxing, and others. Test/demo/tmp directories and `*_jsp.java` artifacts are also excluded.

### JavaScript — `ruleset-js.xml`

Standard PMD JavaScript rules with Best Practices and Error Prone categories.

## Updating

### PMD + jPinpoint

```bash
bash tools/pmd/download-pmd.sh            # check for updates, download if stale
bash tools/pmd/download-pmd.sh --force    # force reinstall everything
```

The script checks the latest PMD release from GitHub and the latest jPinpoint release separately. If either is outdated, it downloads and installs the new version. jPinpoint rules are extracted from the release source archive and the ruleset name is normalized to `jpinpoint`.

### Version files

- `version.txt` — installed PMD version
- `jpinpoint-version.txt` — installed jPinpoint rules version

## Customizing

- `report.css` — report theme (dark)
- `ruleset-java.xml` — Java rules and exclusions
- `ruleset-js.xml` — JavaScript rules
- `ruleset-java-jpinpoint.xml` — jPinpoint rules (auto-updated)
- `pmd-to-html.py` — XML → HTML converter with rule filtering

## Files

| File                   | Purpose                                       |
| ---------------------- | --------------------------------------------- |
| `bin/`                 | PMD binary (auto-downloaded)                  |
| `lib/`                 | Stripped PMD jars (Java + JS only)            |
| `version.txt`          | Installed PMD version                         |
| `jpinpoint-version.txt`| Installed jPinpoint rules version             |
| `ruleset-java.xml`     | Java ruleset (security + error-prone + jpinpoint) |
| `ruleset-js.xml`       | JavaScript ruleset                            |
| `ruleset-java-jpinpoint.xml` | jPinpoint performance/quality rules           |
| `pmd-to-html.py`       | XML → HTML converter                          |
| `report.css`           | Dark theme stylesheet                         |
| `download-pmd.sh`      | Download/update PMD + jPinpoint script        |
