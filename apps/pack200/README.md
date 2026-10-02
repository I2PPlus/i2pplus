# Pack200 (`pack200/`)

Fork of the Pack200 compression format for JAR files. Extracted from OpenJDK just before its removal in Java 14.

## Why Pack200?

Pack200 achieves 60-80% compression on JAR files by:
- Analyzing bytecode across all classes
- Sharing constant pools
- Applying predictions

This made it ideal for router updates over I2P (bandwidth-constrained). Development I2P+ updates use Pack200-compressed JARs.

## Status

Pack200 was **removed in Java 14** (2019). This fork re-adds Pack200 support for Java 14 and later, enabling compressed I2P+ updates across all supported Java versions.

The JDK's `pack200` command-line tool went with it, so this fork is used on **both** ends:

- **Build time** — the `pack200` and `repack200` Ant targets (and the Gradle
  `pack200Updater` task) compress the staged payload by invoking this jar
  through `tools/scripts/pack200.sh`. Building `updater200*` therefore needs
  only a Java 14+ JDK; no second, older JDK and no JDK-provided tool.
- **Update time** — the resulting jar ships as `lib/pack200.jar` in the update
  payload and the router loads it reflectively to unpack each `.jar.pack` /
  `.war.pack` entry (see `FileUtil.extractZip`).

Because the same code packs and unpacks, a `.pack` produced by one build is
always readable by the routers that install it.

### Local changes from upstream OpenJDK

- The native unpack path (`NativeUnpack`, which needed an `unpack` shared
  library that was never shipped in this fork) was removed. It could only ever
  raise `UnsatisfiedLinkError` and fall back to the Java implementation, and
  its `System.loadLibrary` call made JDK 24+ emit a restricted-method warning on
  every invocation. The `-Dpack.disable.native` property went with it.
- `AccessController` / `System.getSecurityManager` use was dropped from
  `Pack200` and `PropMap`. Both are deprecated for removal, nothing here runs
  under a SecurityManager, and neither a classpath resource nor a system
  property needs the privilege.

## Packages

- `io.pack200` - Packer, unpacker, archive format

## Building

Build the jar on its own:

```bash
ant -f java/build.xml jar
```

From the top level, `ant buildPack200` does the same and drops the result in
`build/pack200.jar`; every updater target pulls it in automatically. To
regenerate the payload zip itself:

```bash
ant updater200
```

### Command-line use

The CLI entry point is `io.pack200.Driver` (`io.pack200.Pack200` has no
`main`):

```bash
# compress: -g/--no-gzip emits a plain .pack stream
java -cp build/pack200.jar io.pack200.Driver \
     --effort=5 --modification-time=latest --segment-limit=-1 \
     -g app.jar.pack app.jar

# unpack (note the undocumented --unpack switch)
java -cp build/pack200.jar io.pack200.Driver --unpack app.jar.pack app.jar

# normalise a jar in place so jarsigner produces a stable signature
java -cp build/pack200.jar io.pack200.Driver -r app.jar
```

`tools/scripts/pack200.sh` wraps all of this and is what the build targets call.
It uses GNU `parallel` when installed and falls back to a serial loop when not,
so `parallel` is an optimisation rather than a requirement.

Note that `-g` leaves the `.pack` stream uncompressed, so a raw `.pack` total
can be *larger* than the raw jars it replaces — the saving only materialises when
the update zip is compressed. `zipit200` prints the real figure.