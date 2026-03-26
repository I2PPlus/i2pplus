# Tools

Build and analysis tools for I2P+.

## JSDoc

JavaScript API documentation generator.

### Build

```bash
ant jsdoc
```

Output: `dist/jsdoc/`

### Available Targets

- `ant jsdoc` - docdash template (default)
- `ant jsdoc-zip` - docdash as zip
- `ant jsdoc-minami` - minami template
- `ant jsdoc-minami-zip` - minami as zip
- `ant jsdoc-tui` - tui-jsdoc-template
- `ant jsdoc-tui-zip` - tui as zip

### jsdoc/ Directory

- `jsdoc.json` - JSDoc configuration (docdash)
- `jsdoc-minami.json` - JSDoc configuration (minami/tui)
- `jsdoc-patch.js` - Copies fonts and theme CSS to output (docdash)
- `jsdoc-patch-minami.js` - Copies fonts and CSS to output (minami)
- `jsdoc-patch-tui.js` - Copies fonts and CSS to output (tui)
- `jsdoc-dark.css` - Dark theme overrides (docdash)
- `jsdoc-minami-dark.css` - Dark theme overrides (minami)
- `jsdoc-fonts.css` - Font declarations (Open Sans, Fira Code)
- `docdash-template/` - Custom docdash template
- `minami-local/` - Local assets for minami (ionicons)
- `plugins/section.js` - Auto-detects section from file path
- `tmpl/layout.tmpl` - HTML layout template

## Other Tools

- `ant-contrib.jar` - Ant tasks extension (required for build.xml)
- `google-java-format.jar` - Custom I2P+ build of [google-java-format](https://github.com/google/google-java-format) (see below)
- `javadoc/` - Javadoc CSS override theme
- `pmd/` - PMD source code analyzer (see `pmd/README.md`)
- `checkstyle/` - Checkstyle code style analyzer (see `checkstyle/README.md`)
- `spotbugs/` - Static analysis for Java (see `spotbugs/README.md`)
- `fix-java-issues.py` - Automated code fixes (PMD, SpotBugs, Checkstyle, CodeQL)
- `java-formatter.py` - Wrapper for google-java-format with I2P defaults
- `codeql/filter-sarif.py` - Filters CodeQL SARIF reports by `config/exclusions.txt`

### Google Java Format (Custom Build)

`google-java-format.jar` is a custom build with I2P+ defaults.

**I2P defaults (no flags needed):**

| Setting | Default | Override |
|---|---|---|
| Indentation | 4-space (AOSP) | `--aosp` is the default |
| Line length | 900 (no wrapping) | `--column-limit N` |
| String reflow | Disabled | `--skip-reflowing-long-strings` is the default |
| Javadoc formatting | Disabled | `--skip-javadoc-formatting` is the default |
| Import sorting | Disabled | `--skip-sorting-imports` is the default |

All I2P defaults are baked in — running `java -jar google-java-format.jar file.java` applies
AOSP style with no line wrapping, no javadoc changes, no import reordering, and no string reflowing.

To enable line wrapping at a specific column limit, pass `--column-limit N` explicitly:

```bash
# Default usage (I2P style, no wrapping)
java -jar tools/google-java-format.jar file.java

# Enable line wrapping at 100 columns (upstream default)
java -jar tools/google-java-format.jar --column-limit 100 file.java

# Replace files in-place
java -jar tools/google-java-format.jar -r file.java

# Via wrapper script
python3 tools/java-formatter.py --dry-run file.java
```

**Build notes:**
- Source: `/tmp/google-java-format/` (`core` module only)
- Build: `cd core && mvn package -DskipTests`
- Fat JAR: `core/target/google-java-format-HEAD-SNAPSHOT-all-deps.jar`
- The `--column-limit` CLI flag was added to support per-invocation override
- `JavaFormatterOptions` and `CommandLineOptions` extended with `columnLimit` field

### PMD

```bash
ant pmd              # Run Java + JS analysis
ant pmd-java         # Java report → dist/pmd-java.html
ant pmd-js           # JavaScript report → dist/pmd-js.html
ant pmd-java-local   # Java report with file links
ant pmd-js-local     # JavaScript report with file links
```

### Checkstyle

```bash
ant checkstyle                  # Report → dist/checkstyle.html
ant checkstyle-local            # Report with file links
ant checkstyle-fix-errors       # Preview style fixes (dry run)
ant checkstyle-fix-errors-apply # Apply style fixes
```

### Automated Fixes

`fix-java-issues.py` consolidates PMD, SpotBugs, and Checkstyle fix scripts.

```bash
# Preview all fixes (dry run)
python3 tools/fix-java-issues.py -p core/java/src --dry-run

# Apply specific fix types
python3 tools/fix-java-issues.py -p apps/ --fix simpledate newline-fmt encoding

# Checkstyle with indentation (requires XML report)
python3 tools/fix-java-issues.py -p . --fix checkstyle indent imports -x dist/checkstyle.xml

# Ant targets
ant pmd-fix-errors              # Preview all fixes (dry run)
ant pmd-fix-errors-apply        # Apply all fixes
ant checkstyle-fix-errors       # Preview checkstyle fixes (dry run)
ant checkstyle-fix-errors-apply # Apply checkstyle fixes
```

Fix types: `checkstyle`, `imports`, `indent`, `simpledate`, `newline-fmt`, `encoding`, `serializable`, `pattern`, `comparator`, `dead-store`.

### SpotBugs

```bash
ant spotbugs
```

### CodeQL

```bash
ant codeql              # Run Java + JavaScript + JSP analysis
ant codeql-java         # Java report → dist/codeql-java.html
ant codeql-js           # JavaScript report → dist/codeql-js.html
ant codeql-jsp          # JSP report → dist/codeql-jsp.html
```

### Combined Report

```bash
ant report-all-java           # Run PMD + Checkstyle + CodeQL + SpotBugs → dist/report-all-java.html
ant report-all-java-quick     # Combine existing results (no re-analysis)
```

### Report Configuration

All report generators (PMD, Checkstyle, CodeQL) share `tools/template/common.py` for CSS, favicon, HTML helpers, and exclusion patterns. Create `tools/template/config.txt` to override defaults:

```
# Favicon path (relative to project root)
favicon=installer/resources/themes/console/images/plus.svg

# Report title prefix shown in headers
report_prefix=I2P+
```
