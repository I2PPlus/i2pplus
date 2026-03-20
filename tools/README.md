# tools/

Development tools and configurations.

## JSDoc

| File | Description |
|---|---|
| `jsdoc/jsdoc.json` | JSDoc configuration (sources, plugins, docdash options) |
| `jsdoc/jsdoc-dark.css` | Dark theme CSS with prefers-color-scheme support |
| `jsdoc/jsdoc-fonts.css` | Local font declarations (Open Sans, Fira Code) |
| `jsdoc/jsdoc-patch-fonts.sh` | Post-process script: strip external fonts, copy local fonts |
| `jsdoc/package.json` | Project metadata for JSDoc header |

### Usage

Generate docs from project root:

```bash
ant jsdoc          # docdash template
ant jsdoc-minami   # minami template
ant jsdoc-tui      # tui-jsdoc-template
ant jsdoc-zip      # docdash + zip
```

All templates are minified automatically if `minify` is installed.
Fonts are patched automatically via `jsdoc-patch-fonts.sh`.
