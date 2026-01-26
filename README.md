[![CodeQL](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml)
[![Java CI](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml)
[![I2P+ Update zip](https://i2pplus.github.io/download.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](https://i2pplus.github.io/i2psnarkdownload.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](https://i2pplus.github.io/javadocsdownload.svg)](https://i2pplus.github.io/javadoc.zip)

# I2P+

[Русский](README-ru.md) | [日本語](README-ja.md) | [中文](README-zh.md) | [हिन्दी](README-hi.md) | [བོད་ཡིག](README-bo.md) | [فارسی](README-fa.md)

This is the source code for the soft-fork of the Java implementation of I2P.

Latest release: https://i2pplus.github.io/

## Installing

See INSTALL.txt or https://i2pplus.github.io/ for installation instructions.

## Documentation

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: http://docs.i2p-projekt.de/javadoc/
or run 'ant javadoc' then start at build/javadoc/index.html

## How to contribute / Hack on I2P

Please check out [HACKING.md](docs/HACKING.md) and other documents in the docs directory.

## Building packages from source

To get development branch from source control: https://gitlab.com/i2p.plus/I2P.Plus/

### Prerequisites

- Java SDK (preferably Oracle/Sun or OpenJDK) 1.8.0 or higher
  - Non-linux operating systems and JVMs: See https://trac.i2p2.de/wiki/java
  - Certain subsystems for embedded (core, router, mstreaming, streaming, i2ptunnel)
    require only Java 1.6
- Apache Ant 1.9.8 or higher
- The xgettext, msgfmt, and msgmerge tools installed from the GNU gettext package
  http://www.gnu.org/software/gettext/
- Build environment must use a UTF-8 locale.

### Ant build process

On x86 systems run the following (this will build using IzPack4):

    ant pkg

On non-x86, use one of the following instead:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

If you want to build with IzPack5, download from: http://izpack.org/downloads/ and then
install it, and then run the following command(s):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

To build an unsigned update for an existing installation, run:

    ant updater

If you have issues building a full installer (Java14 and later may generate build errors for izpack relating to pack200),
you can build a full installation zip which can be extracted and run in situ:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Run 'ant' with no arguments to see other build options.

### Docker
For more information how to run I2P in Docker, see [Docker.md](Docker.md)
## Contact info

Need help? See the IRC channel #saltR on the I2P IRC network

Bug reports: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues or https://github.com/I2PPlus/i2pplus/issues

## Licenses

I2P+ is licensed under the AGPL v.3.

For the various sub-component licenses, see: LICENSE.txt

