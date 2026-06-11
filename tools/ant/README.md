# Ant Configuration Files

## `.antrc`

Ant's rc file, sourced automatically by the `ant` launcher when placed at
`$HOME/.antrc`.  Sets `ANT_ARGS` to use the custom `QuietCopyLogger`,
which suppresses noisy build output:

- `[copy] Copying ...` / `Copied ...`
- `[mkdir] Created dir ...`
- `[javac] Creating empty ... package-info.class`
- `[java] JspC` Logging initialized, TLD scan, per-file "Built file"
- `[izpack]` / `[izpack5]` Copying, Merging, Writing
- `[launch4j]` Linking, Wrapping, WARNING

### Installation

```sh
cp .antrc ~/.antrc
```

For CI, add a step before any `ant` invocation:

```yaml
- name: Enable quiet logger
  run: cp tools/ant/.antrc ~/.antrc
```

### Bypass (for debugging)

```sh
ant -logger org.apache.tools.ant.DefaultLogger <target>
```

### Source

Logger source: `tools/build/src/net/i2p/router/build/QuietCopyLogger.java`  
Install target: `ant install-buildtools` (builds jar to `~/.ant/lib/`)
