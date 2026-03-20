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

- `jsdoc.json` - JSDoc configuration
- `jsdoc-patch.js` - Copies fonts and theme CSS to output
- `jsdoc-dark.css` - Dark theme overrides
- `jsdoc-fonts.css` - Font declarations (Open Sans, Fira Code)
- `docdash-template/` - Custom docdash template
- `plugins/section.js` - Auto-detects section from file path
- `tmpl/layout.tmpl` - HTML layout template

## Other Tools

- `ant-contrib.jar` - Ant tasks extension (required for build.xml)
- `google-java-format.jar` - Java code formatter, used by `scripts/remove_unused_imports.sh`
- `javadoc/` - Javadoc CSS override theme
- `pmd-bin-7.7.0/` - PMD source code analyzer
- `spotbugs/` - Static analysis for Java
