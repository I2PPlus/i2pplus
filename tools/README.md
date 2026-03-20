# Tools

Build and analysis tools for I2P+.

## JSDoc

JavaScript API documentation generator.

### Setup

```bash
cd tools/jsdoc
npm install
```

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
- `jsdoc-patch.js` - Post-processes output: fonts, stylesheets, footer cleanup
- `jsdoc-dark.css` - Dark theme overrides
- `jsdoc-fonts.css` - Font declarations
- `package.json` - npm dependencies

## Other Tools

- `ant-contrib.jar` - Ant tasks extension
- `google-java-format.jar` - Java code formatter
- `pmd-bin-7.7.0/` - PMD source code analyzer
- `spotbugs/` - Static analysis for Java
