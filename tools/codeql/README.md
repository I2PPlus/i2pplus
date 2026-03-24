# CodeQL for I2P+
#### https://codeql.github.com/ | License: MIT (CLI) + various (query packs)

CodeQL is GitHub's semantic code analysis engine. Unlike PMD (syntactic pattern matching), CodeQL understands program flow, data paths, and control dependencies to find deeper bugs and security vulnerabilities.

## Setup

```bash
bash tools/codeql/download-codeql.sh            # download CLI + query packs
bash tools/codeql/download-codeql.sh --force    # force reinstall
bash tools/codeql/download-codeql.sh --packs-only  # download packs only (CLI must exist)
```

CodeQL CLI is ~509MB. Downloaded to `tools/codeql/codeql/` (not tracked in git). Query packs are stored in `tools/codeql/packs/` (also not tracked).

## Usage

### Via ant (recommended)

```bash
ant codeql              # Run Java + JavaScript + JSP analysis
ant codeql-java         # Java report → dist/codeql-java.html
ant codeql-js           # JavaScript report → dist/codeql-js.html
ant codeql-jsp          # JSP report → dist/codeql-jsp.html
```

### Manual

#### 1. Create a database

```bash
# Java:
tools/codeql/codeql/codeql database create /tmp/codeql/db-java \
  --language=java \
  --command="ant build"

# JavaScript:
tools/codeql/codeql/codeql database create /tmp/codeql/db-js \
  --language=javascript
```

This runs a full build while CodeQL intercepts the compiler to build a database of the code's structure. JavaScript doesn't require a build step. Databases are stored in `/tmp/codeql/` (cleaned before each run).

#### 2. Analyze

```bash
# Java:
tools/codeql/codeql/codeql database analyze /tmp/codeql/db-java \
  --format=sarif-latest \
  --output=dist/codeql-java.sarif \
  --additional-packs=tools/codeql/packs \
  codeql/java-queries

# JavaScript:
tools/codeql/codeql/codeql database analyze /tmp/codeql/db-js \
  --format=sarif-latest \
  --output=dist/codeql-js.sarif \
  --additional-packs=tools/codeql/packs \
  codeql/javascript-queries
```

#### 3. Compare with previous results

```bash
# Differential analysis (new issues only):
tools/codeql/codeql/codeql database analyze /tmp/codeql/db-java \
  --format=sarif-latest \
  --output=dist/codeql-new.sarif \
  --sarif-add-baseline-file=dist/codeql-baseline.sarif \
  --additional-packs=tools/codeql/packs \
  codeql/java-queries
```

## Query Packs

Query packs are stored in `tools/codeql/packs/` (gitignored). The download script handles this automatically. To download manually:

```bash
tools/codeql/codeql/codeql pack download codeql/java-queries --dir tools/codeql/packs
tools/codeql/codeql/codeql pack download codeql/javascript-queries --dir tools/codeql/packs
```

For custom queries:

```bash
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

| File                  | Purpose                           |
| --------------------- | --------------------------------- |
| `download-codeql.sh`  | Download/update CLI + query packs |
| `version.txt`         | Installed version (auto-created)  |
| `codeql/`             | CLI binary (gitignored, ~509MB)   |
| `packs/`              | Query packs (gitignored)          |
