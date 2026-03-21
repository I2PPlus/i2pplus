# Hacking on the I2P+ Source

## Build systems

### Release/Core Packages build system: Ant

The I2P Java packages are built using the Apache `ant` build system.
To build a complete I2P installer for all platforms, run:

```sh
ant pkg
```

This produces `jar` and `exe` files. Developers who already have I2P installed
may prefer to build updater packages instead of the full installer to speed up
builds and avoid re-running the installer. To build the updater `zip` files:

```sh
ant updater
```

Then copy the updater to the I2P configuration directory and restart I2P.
On Linux, this is `$HOME/.i2p` if installed with the `install.jar` file;
on Windows it is `%LOCALAPPDATA%\I2P\`. Debian packages intentionally disable
this type of update so that the application can be updated from the repositories
securely. Debian users who want to try a dev build can stop the system service:

```sh
sudo systemctl stop i2p
```

or

```sh
sudo service i2p stop
```

then use `install.jar` to set up a user-installed I2P package, and start it with:

```sh
~/i2p/i2prouter start
```

This user-installed version can also update itself via a `zip` file. When finished:

```sh
~/i2p/i2prouter stop
```

then restart the system service:

```sh
sudo systemctl start i2p
```

or

```sh
sudo service i2p start
```

### Peripheral/External Packages: SBT

The `sbt` tool from the Scala project is used for the (discontinued) Browser Bundle
launcher and the Mac OS X launcher. It can be invoked via an `ant` target. To build
the OS X launcher:

```sh
ant osxLauncher
```

To build the Browser Bundle launcher:

```sh
ant bbLauncher
```

## Browsing the source code

* The [DIRECTORIES.md](DIRECTORIES.md) contains a listing of the directories
in the I2P source code and what the code inside them is used for.

* Some parts of the I2P router are started by files that end with the suffix
`'Runner.java'` and can be listed by running the command
`find . -type f -name '*Runner.java'` in the root of your `i2p.i2p` source
directory.

## Version control

I2P+ uses Git. The canonical repositories are:

- [https://github.com/I2PPlus/i2pplus](https://github.com/I2PPlus/i2pplus)
- [http://git.skank.i2p/i2pplus/I2P.Plus](http://git.skank.i2p/i2pplus/I2P.Plus) (in-network)

## Git clone

Clone from the public repository:

```sh
git clone https://github.com/I2PPlus/i2pplus.git
```

Over I2P (requires a configured git proxy):

```sh
git clone http://git.skank.i2p/i2pplus/I2P.Plus.git
```

A shallow clone can speed up the initial download:

```sh
git clone --depth 1 https://github.com/I2PPlus/i2pplus.git
```

Then populate the full history if needed:

```sh
git fetch --unshallow
```

## Git documentation

For developers new to `git`, the official documentation provides a comprehensive
knowledge base: https://git-scm.com/docs . See also the **Guides** section.

## SBT behind a proxy

`sbt` does not work well with SOCKS5 proxies. To use it with Tor, configure an
HTTP proxy via the `SBT_OPTS` environment variable:

```sh
export SBT_OPTS="$SBT_OPTS -Dhttp.proxyHost=myproxy -Dhttp.proxyPort=myport"
```
