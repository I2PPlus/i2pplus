# Ant Configuration Files

## `.antrc`

Ant's rc file, sourced automatically by the `ant` launcher when placed at
`$HOME/.antrc`.  Sets `ANT_ARGS` to use the custom `ConciseLogger`,
which suppresses noisy build output:

- `[copy] Copying ...` / `Copied ...`
- `[mkdir] Created dir ...`
- `[replace] Replaced N occurrences ...`
- `[javac] Creating empty ...` / `Ignoring source, target ...`
- `[java] JspC` Logging initialized, TLD scan, per-file "Built file"
- `[jar] module-info.class already added`
- `[exec]` Generating ResourceBundle / Using cached translation bundles
- `[izpack]` / `[izpack5]` Copying, Merging, Writing
- `[launch4j]` Linking, Wrapping, WARNING

### Installation

```sh
cp .antrc ~/.antrc
```

For CI, add a step before any `ant` invocation:

```yaml
- name: Enable concise logger
  run: cp tools/ant/.antrc ~/.antrc
```

### Bypass (for debugging)

```sh
ant -logger org.apache.tools.ant.DefaultLogger <target>
```

### Source

Logger source: `tools/build/src/net/i2p/router/build/ConciseLogger.java`  
Install target: `ant install-buildtools` (builds jar to `~/.ant/lib/`)
