# SonarQube

SonarQube static analysis tooling for the I2P+ codebase.

### Prerequisites

- Java 17+ (for SonarQube server)
- ~4GB free RAM (server + scanner)

### Download Sizes

| Component  | Size    | Notes                               |
| ---------- | ------- | ----------------------------------- |
| Server     | ~885 MB | SonarQube Community Edition zip     |
| Scanner    | ~59 MB  | SonarScanner CLI zip                |

Binaries are auto-downloaded on first `ant sonar`. Versions are pinned in `download-sonar.sh` (hardcoded); updating requires editing the
version string in the script and `version.txt` / `server-version.txt` manually. No auto-update. If a newer version is available upstream,
the pinned version must be bumped by a developer.

### Ant Target

| Target                 | Description                                                                                                   |
| ---------------------- | ------------------------------------------------------------------------------------------------------------- |
| `ant sonarqube-report` | Full analysis: download/start server, run scanner, generate HTML report, stop server                          |

### Workflow

    ant sonarqube-report

This downloads the server + scanner on first run, starts the server, runs the analysis, generates HTML reports
to `dist/sonarqube.html` and `dist/sonarqube-local.html`, then stops the server.

### Environment

| Variable          | Default                          | Purpose                                             |
| ----------------- | -------------------------------- | --------------------------------------------------- |
| `SONAR_PORT`      | `11199`                          | Server port                                         |
| `SONAR_RULES_URL` | `https://cloud-ci.sgs.com/sonar` | Base URL for rule documentation links in the report |

### `sonarqube-to-html.py` Arguments

| Argument          | Required | Description                                                                       |
| ----------------- | -------- | --------------------------------------------------------------------------------- |
| `--token`         | Yes\*    | SonarQube API token (not needed with `--cached-issues`)                           |
| `--url`           | No       | SonarQube server URL (default: `http://localhost:11199`)                          |
| `--project`       | No       | Project key (default: `net.i2p.router:i2pplus`)                                   |
| `--output`        | No       | Output HTML path (default: `dist/sonarqube.html`)                                 |
| `--local`         | No       | Use `file://` links for local browsing                                            |
| `--exclude-rules` | No       | Comma-separated rule keys to exclude (e.g. `java:S116,java:S2293`)                |
| `--cached-issues` | No       | Path to cached issues JSON — skip API fetch, work offline                         |
| `--save-cache`    | No       | Save fetched issues to a JSON file for later offline use                          |
| `--sonar-url`     | No       | Base URL for rule documentation links (default: `https://cloud-ci.sgs.com/sonar`) |

\*`--token` is required for API fetch. Omit when using `--cached-issues`.

### Rule Documentation Links

Each issue in the HTML report includes a Doc link pointing to a SonarQube `coding_rules` page.
The base URL defaults to `SONAR_RULES_URL` and can be overridden:

    SONAR_RULES_URL=https://my-sonar.instance ant sonar-report

### Direct Script Usage (Linux / macOS)

The scripts can be invoked directly for more control — no Ant required. Windows is not supported (WSL only).

```bash
# Full analysis
bash tools/sonarqube/run-sonar.sh

# Keep server running after scan (faster subsequent runs)
bash tools/sonarqube/run-sonar.sh --no-stop

# Pass a custom sonar-project.properties file
bash tools/sonarqube/run-sonar.sh --no-stop path/to/sonar-project.properties
```

### Standalone Report Generation

```bash
# Fetch issues from a running server and generate HTML report
python3 tools/sonarqube/sonarqube-to-html.py \
    --token squ_... \
    --url http://localhost:11199 \
    --project net.i2p.router:i2pplus \
    --output dist/sonarqube.html

# Same, but save a local cache for offline use
python3 tools/sonarqube/sonarqube-to-html.py \
    --token squ_... \
    --url http://localhost:11199 \
    --output dist/sonarqube.html \
    --save-cache /tmp/i2p-sonarqube/.issues-cache.json

# Regenerate report from cache (no server needed)
python3 tools/sonarqube/sonarqube-to-html.py \
    --cached-issues /tmp/i2p-sonarqube/.issues-cache.json \
    --output dist/sonarqube.html
```

### Custom Quality Profile

`run-sonar.sh` auto-imports `i2pplus-custom-java.xml` as the default Java quality
profile before each scan. This profile is a copy of "Sonar way" with project-specific
overrides. Current overrides:

| Rule   | Change                          | Reason                                |
| ------ | ------------------------------- | ------------------------------------- |
| S116   | Format: `^_?[a-z][a-zA-Z0-9]*$` | Allow `_prefix` field convention      |
| S106   | Deactivated                     | `System.out` usage (dev logging)      |
| S1135  | Deactivated                     | TODO comment tracking (informational) |
| S1192  | Deactivated                     | Duplicate string literals (invasive)  |

**Creating or modifying a custom profile:**

1. Export the current "Sonar way" Java profile from the running server:
   ```bash
   curl -u admin:SonarQube2026! \
     -G 'http://localhost:11199/api/qualityprofiles/backup' \
     --data-urlencode 'qualityProfile=Sonar way' \
     --data-urlencode 'language=java' \
     -o my-profile-java.xml
   ```
2. Edit the XML — find the `<rule>` for `S116` and change its `<format>` value.
3. Change `<name>Sonar way</name>` to a unique name.
4. Save to `tools/sonarqube/`    prepended with `my-profile-java.xml` — or replace
   `i2pplus-custom-java.xml` entirely (the script imports whatever file
   `CUSTOM_PROFILE` points to at the top of the import block).

The profile is imported as the default Java profile on the server
via `api/qualityprofiles/set_default` — no per-project association needed.
If the XML file is missing from `tools/sonarqube/`, the script skips the import
and the server's existing defaults are used.

### Scripts

- `run-sonar.sh` -- orchestrates server start/stop, scanning, and report generation
- `sonarqube-to-html.py` -- fetches issues from the API (or cache) and generates the HTML report
- `download-sonar.sh` -- auto-downloads server and scanner binaries

### Output

The HTML report is written to `dist/sonarqube.html`.
