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
- `google-java-format.jar` - Java code formatter, used by `scripts/remove_unused_imports.sh`
- `javadoc/` - Javadoc CSS override theme
- `pmd-bin-7.7.0/` - PMD source code analyzer
- `spotbugs/` - Static analysis for Java
