# I2P+ Slackware Package

## Getting the Sources

### Git

The I2P+ source code is hosted on GitHub:

```
git clone https://github.com/I2PPlus/i2pplus.git
```

### Tarball

The latest stable release is always available from the I2P+ homepage at
https://i2pplus.github.io/.

## Building the Package

### Requirements

The following are needed to build the i2p package:

- JRE >= 8
- JDK >= 8
- gettext
- Apache Ant >= 1.9.8

If you don't care about bundling the translations, the gettext requirement can
be avoided by adding `-Drequire.gettext=false` to the ant lines in
`i2p/i2p.SlackBuild`.

A JRE >= v8 is the only requirement to run I2P+.

### Build

As the root user, run either `$I2PSRC/Slackware/i2p/i2p.SlackBuild` or
`ant slackpkg` to create a package in `$I2PSRC/Slackware/i2p` which can be
installed using the Slackware packaging tools.

## See Also

- [i2p/README](i2p/README)
- `eepget(1)`
- `i2prouter(1)`
- https://github.com/I2PPlus/i2pplus/
- https://i2pplus.github.io/
