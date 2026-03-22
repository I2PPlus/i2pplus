# PMD Static Analysis for I2P+
#### https://pmd.github.io/ | [License](../docs/licenses/LICENSE-pmd.txt): BSD-4-Clause + Apache 2.0

## Ant Targets

| Target         | Description                              |
| -------------- | ---------------------------------------- |
| `ant pmd`      | Run both Java and JavaScript analysis    |
| `ant pmd-java` | Analyze Java source (core, router, apps) |
| `ant pmd-js`   | Analyze JavaScript source (apps/)        |

Reports go to `dist/pmd-java.html` and `dist/pmd-js.html`. PMD auto-downloads on first run. Requires Java 11+ (auto-detected; override with `pmd.java=/path/to/java` in `override.properties`).


## Updating PMD

```bash
bash tools/pmd/download-pmd.sh            # check for updates
bash tools/pmd/download-pmd.sh --force    # force reinstall
```

## Customizing

- `report.css` — report theme
- `ruleset.xml` / `ruleset-js.xml` — PMD rules
- `pmd-to-html.py` — HTML structure

## Files

| File              | Purpose                               |
| ----------------- | ------------------------------------- |
| `bin/`            | PMD binary (auto-downloaded)          |
| `lib/`            | Stripped PMD jars (Java + JS only)    |
| `version.txt`     | Installed PMD version                 |
| `ruleset.xml`     | Java ruleset (security + error-prone) |
| `ruleset-js.xml`  | JavaScript ruleset                    |
| `pmd-to-html.py`  | XML → HTML converter                  |
| `report.css`      | Dark theme stylesheet                 |
| `download-pmd.sh` | Download/update script                |
