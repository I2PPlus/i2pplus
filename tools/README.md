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
- `google-java-format.jar` - Java code formatter
- `javadoc/` - Javadoc CSS override theme
- `pmd/` - PMD source code analyzer (see `pmd/README.md`)
- `checkstyle/` - Checkstyle code style analyzer (see `checkstyle/README.md`)
- `spotbugs/` - Static analysis for Java (see `spotbugs/README.md`)

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
ant checkstyle            # Report → dist/checkstyle.html
ant checkstyle-local      # Report with file links
ant checkstyle-fix-errors # Auto-fix style violations
```

The `checkstyle-fix-errors` target runs `fix-style.py` which fixes unused imports, tabs, whitespace, empty statements, indentation, trailing whitespace, and missing newlines. Exclusions from `checkstyle.xml` are applied automatically.

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
