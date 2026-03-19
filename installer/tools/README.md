# Build Utilities (`tools/`)

Build-time tools used during the I2P packaging process.

## Contents

- `TranslationStatus.java` - Analyzes `.po` translation files and reports completion status per language
- `BundleRouterInfos.java` - Bundles router info files for the network database seed

## Building

```bash
# Ant (from repo root)
ant -f tools/java/build.xml
```
