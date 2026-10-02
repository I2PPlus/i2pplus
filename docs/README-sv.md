[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Detta är källkoden för soft-forken av Java-implementeringen av I2P.

Senaste versionen: https://i2pplus.github.io/

## Installation

Se [INSTALL.md](docs/INSTALL.md) eller https://i2pplus.github.io/ för installationsinstruktioner.

### Anmärkning om Windows-installationsprogrammet

Med Java > 1.8 eller alternativa distributioner (AdoptOpenJDK osv.) kan installationsprogrammets exe-fil misslyckas med felen "Java not found" eller "invalid/corrupt". Lösning: extrahera install.jar från exe-filen och kör `java -jar install.jar` från kommandoraden.

## Dokumentation

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
eller kör 'ant javadoc' och börja sedan i build/javadoc/index.html

## Så bidrar du / Hacka på I2P+

Titta gärna på [HACKING.md](docs/HACKING.md) och andra dokument i docs-katalogen.

## Bygga paket från källkod

För att hämta utvecklingsgrenen från versionshanteringen: https://github.com/I2PPlus/i2pplus

### Förutsättningar

- Java SDK 1.8.0 eller högre
- Apache Ant 1.9.8 eller högre
- Verktygen xgettext, msgfmt och msgmerge installerade från GNU gettext-paketet
  via din pakethanterare eller http://www.gnu.org/software/gettext/
- Byggmiljön måste använda en UTF-8-lokal.
- För Debian-paketbyggen: paketen `dpkg-deb` och `fakeroot` (via din pakethanterare)

### Ant-byggprocess

På x86-system, kör följande (detta byggs med IzPack4):

    ant pkg

På icke-x86, använd i stället ett av följande:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Om du vill bygga med IzPack5, ladda ner från: http://izpack.org/downloads/ och
installera det sedan, och kör därefter följande kommando:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

För att bygga en osignerad uppdatering för en befintlig installation, kör:

    ant updater

eller med Gradle:

    ./gradlew updater

Om du har problem med att bygga ett komplett installationsprogram (Java14 och senare kan generera byggfel för izpack relaterade till pack200)
kan du bygga ett komplett installations-zip som kan extraheras och köras på plats:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Kör 'ant' utan argument för att se andra byggalternativ.

För att bygga en AppImage för Linux:
```bash
ant buildAppImage
```

Se [tools/appimage/README.md](tools/appimage/README.md) för detaljer.

För att bygga ett fristående Debian-paket för Debian/Ubuntu utan externa Jetty/Tomcat-beroenden:
```bash
ant buildDeb
```

Detta skapar ett fristående `.deb`-paket som innehåller inkluderade Jetty- och Tomcat-bibliotek. Kräver endast OpenJDK-miljö (installeras automatiskt via pakethanteraren).

För att köra i Docker, se [docker/README.md](docker/README.md)

## Kontaktuppgifter

Behöver du hjälp? Besök IRC-kanalen #saltR på I2P IRC-nätverket

Felrapporter: https://github.com/I2PPlus/i2pplus/issues

## Licenser

I2P+ licensieras under AGPL v.3.

För de olika underkomponenternas licenser, se: [README.md](docs/LICENSES.md)

## Se även

### Dokumentation

- [docs/README.md](docs/README.md) - Fullständig dokumentationsindex
- [docs/INSTALL.md](docs/INSTALL.md) - Installationsguide
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless-installation (konsolläge)
- [docs/HACKING.md](docs/HACKING.md) - Utvecklarguide och byggsystem
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Källträdsstruktur och var man hittar saker
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Översikt över routerns källträd
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Översikt över kärnbibliotekets källträd
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Felsökning i körning med JDWP och andra verktyg
- [docs/THEMING.md](docs/THEMING.md) - Konsollens och webbappens temasytem
- [docs/LICENSES.md](docs/LICENSES.md) - Licenser från tredje part
- [docs/history.txt](docs/history.txt) - Fullständig ändringslogg

### Delprojekt

- [apps/README.md](apps/README.md) - Översikt över applikationerna
- [apps/addressbook/README.md](apps/addressbook/README.md) - Adressboksapplikation
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Skrivbords-GUI-applikation
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent-klient
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P Tunnel-applikation
- [apps/imagegen/README.md](apps/imagegen/README.md) - Bildgenereringsverktyg
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP-server
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin-övervakningsbibliotek
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimalt streaming-bibliotek
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200-komprimering
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy-skript
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Routerkonsol
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming-bibliotek
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P e-postklient
- [apps/systray/README.md](apps/systray/README.md) - Systemfältapplikation
- [core/README.md](core/README.md) - Dokumentation för kärnbiblioteket
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Inhemskt JNI-bibliotek för kryptografi (GMP)

### Övrigt

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Hantera I2P-sessionsförbud med nftables
- [installer/resources/README.md](installer/resources/README.md) - Inkluderade installationsresurser
- [tools/scripts/README.md](tools/scripts/README.md) - Hjälpsskript för utveckling och administration
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validerings- och testskript
