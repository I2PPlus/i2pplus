[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Ito ang source code para sa soft-fork ng Java na implementasyon ng I2P.

Pinakabagong release: https://i2pplus.github.io/

## Pag-iinstall

Tingnan ang [INSTALL.md](docs/INSTALL.md) o https://i2pplus.github.io/ para sa mga tagubilin sa pag-iinstall.

### Tala tungkol sa Windows installer

Sa Java > 1.8 o mga alternatibong distribusyon (AdoptOpenJDK, atbp.), maaaring mabigo ang installer exe na may "Java not found" o "invalid/corrupt" na mga error. Solusyon: i-extract ang install.jar mula sa exe at patakbuhin ang `java -jar install.jar` mula sa command line.

## Dokumentasyon

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
o patakbuhin ang 'ant javadoc' tapos magsimula sa build/javadoc/index.html

## Paano mag-ambag / Mag-hack sa I2P+

Pakitingnan ang [HACKING.md](docs/HACKING.md) at iba pang mga dokumento sa docs directory.

## Pagbuo ng mga package mula sa source

Para makuha ang development branch mula sa source control: https://github.com/I2PPlus/i2pplus

### Mga kinakailangan

- Java SDK 1.8.0 o mas mataas
- Apache Ant 1.9.8 o mas mataas
- Ang mga tool na xgettext, msgfmt, at msgmerge na naka-install mula sa GNU gettext package
  sa pamamagitan ng iyong package manager o http://www.gnu.org/software/gettext/
- Dapat gumamit ng UTF-8 locale ang build environment.
- Para sa mga build ng Debian package: mga package na `dpkg-deb` at `fakeroot` (sa pamamagitan ng iyong package manager)
- Para sa IzPack 5 Windows exe (`ant installer5`, `ant installer5-windows`): Python 3.
  Ang `izpack2exe.py` ng IzPack ay Python 3 ngunit ang shebang nito ay `python`, kaya
  ipinapasa ng build ang interpreter nang eksplisito; i-override ito gamit ang `izpack5.python`.

### Proseso ng pagbuo gamit ang Ant

Sa mga system na x86, patakbuhin ang sumusunod (ito ay bubuuin gamit ang IzPack4):

    ant pkg

Sa mga hindi-x86, gumamit ng isa sa mga sumusunod sa halip:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Kung nais mong bumuo gamit ang IzPack5, patakbuhin ang mga sumusunod na utos. Sa unang paggamit,
ida-download nila ang distribusyon ng IzPack 5 sa `installer/lib/izpack/5/` — kailangan ito ng
network access at ~95MB na libreng espasyo — at awtomatiko itong pinapanatiling napapanahon:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Para i-fetch o i-refresh ito nang hindi gumagawa ng installer:

    ant download-izpack5

Para bumuo ng hindi naka-sign na update para sa umiiral na pag-iinstall, patakbuhin:

    ant updater

o gamit ang Gradle:

    ./gradlew updater

Kung mayroon kang problema sa pagbuo ng kumpletong installer (maaaring magbigay ng mga build error ang Java14 at pataas para sa izpack na may kinalaman sa pack200),
maaari kang bumuo ng kumpletong installation zip na maaaring i-extract at patakbuhin sa mismong lugar:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Patakbuhin ang 'ant' nang walang mga argumento para makita ang iba pang mga opsyon sa pagbuo.

Para bumuo ng AppImage para sa Linux:
```bash
ant buildAppImage
```

Tingnan ang [tools/appimage/README.md](tools/appimage/README.md) para sa mga detalye.

Para bumuo ng self-contained na Debian package para sa Debian/Ubuntu nang walang mga external na dependency sa Jetty/Tomcat:
```bash
ant buildDeb
```

Nagbibigay ito ng self-contained na `.deb` na package na may kasamang nakabundleng Jetty at Tomcat na mga library. Kinakailangan lamang ang OpenJDK runtime (awtomatikong nai-install sa pamamagitan ng package manager).

Para patakbuhin sa Docker, tingnan ang [docker/README.md](docker/README.md)

## Impormasyon sa pakikipag-ugnayan

Kailangan ng tulong? Bisitahin ang IRC channel #saltR sa I2P IRC network

Mga ulat ng bug: https://github.com/I2PPlus/i2pplus/issues

## Mga Lisensya

Ang I2P+ ay lisensyado sa ilalim ng AGPL v.3.

Para sa iba't ibang lisensya ng mga sub-component, tingnan: [README.md](docs/LICENSES.md)

## Tingnan din

### Dokumentasyon

- [docs/README.md](docs/README.md) - Kumpletong index ng dokumentasyon
- [docs/INSTALL.md](docs/INSTALL.md) - Gabay sa pag-iinstall
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless (console mode) na pag-iinstall
- [docs/HACKING.md](docs/HACKING.md) - Gabay sa pag-develop at mga sistema ng pagbuo
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Ayos ng source tree at kung saan makakahanap ng mga bagay
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Pangkalahatang-ideya ng source tree ng router
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Pangkalahatang-ideya ng source tree ng core library
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Runtime debugging gamit ang JDWP at iba pang mga tool
- [docs/THEMING.md](docs/THEMING.md) - Sistema ng theming ng console at webapp
- [docs/LICENSES.md](docs/LICENSES.md) - Mga lisensya ng third-party
- [docs/history.txt](docs/history.txt) - Kumpletong changelog

### Mga sub-proyek

- [apps/README.md](apps/README.md) - Pangkalahatang-ideya ng mga application
- [apps/addressbook/README.md](apps/addressbook/README.md) - Application na addressbook
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktop GUI na application
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - BitTorrent client na I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Application na I2P Tunnel
- [apps/imagegen/README.md](apps/imagegen/README.md) - Mga tool sa paggawa ng larawan
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP server
- [apps/jrobin/README.md](apps/jrobin/README.md) - Library sa pagsubaybay ng JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimal na streaming library
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 compression
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Mga script sa proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router console
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming library
- [apps/susidns/README.md](apps/susidns/README.md) - DNS server
- [apps/susimail/README.md](apps/susimail/README.md) - Kliyente ng email sa I2P
- [apps/systray/README.md](apps/systray/README.md) - Application sa system tray
- [core/README.md](core/README.md) - Dokumentasyon ng core library
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Native na JNI library para sa cryptography (GMP)

### Iba pa

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Pamamahala ng mga I2P session ban gamit ang nftables
- [installer/resources/README.md](installer/resources/README.md) - Nakabundleng mga resource ng installer
- [tools/scripts/README.md](tools/scripts/README.md) - Mga utility script para sa pag-unlad at administrasyon
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Mga script sa pagpapatunay at pagsubok
