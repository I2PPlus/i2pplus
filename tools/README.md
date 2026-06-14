# tools/

Development tools, scripts, and static analysis utilities for I2P+.

## Directories

| Directory                      | Description                                                     |
| ------------------------------ | --------------------------------------------------------------- |
| [`scripts/`](scripts/)         | Build, code quality, translation, and admin scripts             |
| [`checkstyle/`](checkstyle/)   | Checkstyle static analysis for Java code style                  |
| [`pmd/`](pmd/)                 | PMD static analysis for Java and JavaScript                     |
| [`spotbugs/`](spotbugs/)       | SpotBugs bytecode analysis for Java bugs                        |
| [`codeql/`](codeql/)           | CodeQL semantic code analysis                                   |
| [`sonarqube/`](sonarqube/)     | SonarQube static analysis with offline/cached report generation |
| [`java-format/`](java-format/) | Google Java Format integration                                  |
| [`template/`](template/)       | Shared templates for HTML reports                               |
| [`test/`](test/)               | Test dependencies (JUnit, Mockito, etc.)                        |
| [`jsdoc/`](jsdoc/)             | JSDoc configuration and templates                               |

## Quick Start

```bash
# Static analysis
ant checkstyle        # Code style → dist/checkstyle.html
ant pmd               # Java + JS → dist/pmd-java.html + dist/pmd-js.html
ant spotbugs          # Bytecode bugs → dist/spotbugs.html
ant sonar             # SonarQube (full scan) → dist/sonarqube.html
ant sonar-report      # SonarQube (cached/offline)
ant report-all-java   # Combined report

# Run analysis targets
ant -p | grep -E "checkstyle|pmd|spotbugs|codeql|sonar"

# Test dependencies (auto-downloaded on first run)
ant test
```

## Tools Overview

### Code Style
- **Checkstyle**: Enforces coding standards (indentation, naming, imports)
- **google-java-format**: Auto-formats Java code

### Static Analysis
- **PMD**: Syntactic pattern matching for Java/JS (error-prone, security, performance)
- **SpotBugs**: Bytecode analysis for bug patterns
- **CodeQL**: Semantic analysis for deep security bugs (requires download)
- **SonarQube**: Comprehensive static analysis with local server; supports offline report generation from cached results. See [`sonarqube/`](sonarqube/) for full docs.

### Testing
- **JUnit 4** with Hamcrest, Mockito, and ScalaTest
- Auto-downloaded by `scripts/ensure-testdeps.sh`

## Downloading Tools

Most tools are auto-downloaded on first use. To manually update:

```bash
# PMD
bash tools/pmd/download-pmd.sh

# Checkstyle
bash tools/checkstyle/download-checkstyle.sh

# CodeQL (~509MB)
bash tools/codeql/download-codeql.sh

# Test dependencies
bash tools/scripts/ensure-testdeps.sh
```

## Report Output

All analysis reports are written to `dist/`:

- `checkstyle.html` / `checkstyle-local.html`
- `pmd-java.html` / `pmd-js.html`
- `spotbugs.html` / `spotbugs-local.html`
- `codeql-java.html` / `codeql-js.html`
- `sonarqube.html`
- `report-all-java.html` / `report-all-java-local.html`
- `test-report.html`