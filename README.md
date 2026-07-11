[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](docs/README-ru.md) [<img src="apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](docs/README-ja.md) [<img src="apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](docs/README-zh.md) [<img src="apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](docs/README-hi.md) [<img src="apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](docs/README-bo.md) [<img src="apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](docs/README-fa.md) [<img src="apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](docs/README-ar.md) [<img src="apps/routerconsole/resources/icons/flags_svg/bn.svg" width="28" height="21" title="বাংলা">](docs/README-bn.md) [<img src="apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](docs/README-es.md) [<img src="apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](docs/README-fr.md) [<img src="apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](docs/README-de.md) [<img src="apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](docs/README-tr.md) [<img src="apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](docs/README-id.md) [<img src="apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](docs/README-uk.md) [<img src="apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](docs/README-pt.md) [<img src="apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](docs/README-pl.md) [<img src="apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](docs/README-ko.md) [<img src="apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](docs/README-vi.md) [<img src="apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](docs/README-th.md) [<img src="apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](docs/README-ur.md) [<img src="apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](docs/README-he.md) [<img src="apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](docs/README-it.md) [<img src="apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](docs/README-nl.md) [<img src="apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](docs/README-ro.md) [<img src="apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](docs/README-cs.md) [<img src="apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](docs/README-hu.md) [<img src="apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](docs/README-el.md)

This is the source code for the soft-fork of the Java implementation of I2P.

Latest release: https://i2pplus.github.io/

## Installing

See [INSTALL.md](docs/INSTALL.md) or https://i2pplus.github.io/ for installation instructions.

### Windows installer note

With Java > 1.8 or alternative distributions (AdoptOpenJDK, etc.), the installer exe may fail with "Java not found" or "invalid/corrupt" errors. Workaround: extract install.jar from the exe and run `java -jar install.jar` from the command line.

## Documentation

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
or run 'ant javadoc' then start at build/javadoc/index.html

## How to contribute / Hack on I2P+

Please check out [HACKING.md](docs/HACKING.md) and other documents in the docs directory.

## Building packages from source

To get development branch from source control: https://github.com/I2PPlus/i2pplus

### Prerequisites

- Java SDK 1.8.0 or higher
- Apache Ant 1.9.8 or higher
- The xgettext, msgfmt, and msgmerge tools installed from the GNU gettext package
  via your package manager or http://www.gnu.org/software/gettext/
- Build environment must use a UTF-8 locale.
- For Debian package builds: `dpkg-deb` and `fakeroot` packages (via your package manager)

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

or with Gradle:

    ./gradlew updater

If you have issues building a full installer (Java14 and later may generate build errors for izpack relating to pack200),
you can build a full installation zip which can be extracted and run in situ:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Run 'ant' with no arguments to see other build options.

To build an AppImage for Linux:
```bash
ant buildAppImage
```

See [tools/appimage/README.md](tools/appimage/README.md) for details.

To build a self-contained Debian package for Debian/Ubuntu without external Jetty/Tomcat dependencies:
```bash
ant buildDeb
```

This creates a self-contained `.deb` package that includes bundled Jetty and Tomcat libraries. Requires only OpenJDK runtime (installed automatically via package manager).

To run in Docker, see [docker/README.md](docker/README.md)

## Contact info

Need help? Visit the IRC channel #saltR on the I2P IRC network

Bug reports: https://github.com/I2PPlus/i2pplus/issues

## Licenses

I2P+ is licensed under the AGPL v.3.

For the various sub-component licenses, see: [README.md](docs/LICENSES.md)

## See also

### Documentation

- [docs/README.md](docs/README.md) - Full documentation index
- [docs/INSTALL.md](docs/INSTALL.md) - Installation guide
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless (console mode) installation
- [docs/HACKING.md](docs/HACKING.md) - Developer guide and build systems
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Source tree layout and where to find things
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Runtime debugging with JDWP and other tools
- [docs/LICENSES.md](docs/LICENSES.md) - Third-party licenses
- [docs/history.txt](docs/history.txt) - Full changelog

### Sub-projects

- [apps/README.md](apps/README.md) - Application overview
- [apps/addressbook/README.md](apps/addressbook/README.md) - Addressbook application
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktop GUI application
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent client
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P Tunnel application
- [apps/imagegen/README.md](apps/imagegen/README.md) - Image generation tools
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP server
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin monitoring library
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimal streaming library
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 compression
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy scripts
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router console
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming library
- [apps/susidns/README.md](apps/susidns/README.md) - DNS server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P email client
- [apps/systray/README.md](apps/systray/README.md) - System tray application
- [core/README.md](core/README.md) - Core library documentation
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Native JNI library for cryptography (GMP)

### MISC

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Managing I2P session bans with nftables
- [installer/resources/README.md](installer/resources/README.md) - Bundled installer resources
- [tools/scripts/README.md](tools/scripts/README.md) - Utility scripts for development and administration
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validation and testing scripts
