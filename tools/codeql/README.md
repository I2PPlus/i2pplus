# CodeQL for I2P+
#### https://codeql.github.com/ | License: MIT (CLI) + various (query packs)

CodeQL is GitHub's semantic code analysis engine. Unlike PMD (syntactic pattern matching), CodeQL understands program flow, data paths, and control dependencies to find deeper bugs and security vulnerabilities.

## Setup

```bash
bash tools/codeql/download-codeql.sh            # download if not present
bash tools/codeql/download-codeql.sh --force    # force reinstall
```

CodeQL CLI is ~509MB. Downloaded to `tools/codeql/codeql/` (not tracked in git).

## Usage

### 1. Create a database

```bash
# From the project root:
tools/codeql/codeql/codeql database create codeql-db \
  --language=java \
  --command="ant updaterCompact"
```

This runs a full build while CodeQL intercepts the compiler to build a database of the code's structure.

### 2. Analyze

```bash
# Run default security + quality queries:
tools/codeql/codeql/codeql database analyze codeql-db \
  --format=sarif-latest \
  --output=dist/codeql.sarif

# Run only security queries (faster):
tools/codeql/codeql/codeql database analyze codeql-db \
  --format=sarif-latest \
  --output=dist/codeql.sarif \
  --query-suite=codeql/java-queries:codeql-suites/java-security-and-quality.qls

# View results:
tools/codeql/codeql/codeql database analyze codeql-db \
  --format=csv \
  --output=dist/codeql.csv
```

### 3. Compare with previous results

```bash
# Differential analysis (new issues only):
tools/codeql/codeql/codeql database analyze codeql-db \
  --format=sarif-latest \
  --output=dist/codeql-new.sarif \
  --sarif-add-baseline-file=dist/codeql-baseline.sarif
```

## Query Packs

Default query packs are included with the CLI. For custom queries:

```bash
# Download standard Java query pack:
tools/codeql/codeql/codeql pack download codeql/java-queries

# Create custom queries:
mkdir -p tools/codeql/queries
# Add .ql files and reference them with --additional-packs
```

## What CodeQL finds that PMD/SpotBugs doesn't

- SQL injection, XSS, path traversal (data flow analysis)
- Hardcoded credentials and API keys
- Insecure random number generation
- Command injection
- Integer overflow with provable bounds
- Use-after-free and null dereference (with path tracing)

## Performance

| Step              | Time      | Notes               |
| ----------------- | --------- | ------------------- |
| Database creation | 5-10 min  | Full build required |
| Security queries  | 2-5 min   | ~300 queries        |
| Quality queries   | 5-10 min  | ~1000 queries       |
| Full suite        | 10-15 min | All queries         |

## Files

| File                  | Purpose                          |
| --------------------- | -------------------------------- |
| `download-codeql.sh`  | Download/update CodeQL CLI       |
| `version.txt`         | Installed version (auto-created) |
| `codeql/`             | CLI binary (gitignored, ~509MB)  |
