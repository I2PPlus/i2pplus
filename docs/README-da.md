[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Dette er kildekoden til soft-forken af Java-implementeringen af I2P.

Seneste version: https://i2pplus.github.io/

## Installation

Se [INSTALL.md](docs/INSTALL.md) eller https://i2pplus.github.io/ for installationsvejledning.

### Bemærkning om Windows-installationsprogrammet

Med Java > 1.8 eller alternative distributioner (AdoptOpenJDK osv.) kan installationsprogrammets .exe fejle med fejlene "Java not found" eller "invalid/corrupt". Løsning: udtræk install.jar fra .exe-filen og kør `java -jar install.jar` fra kommandolinjen.

## Dokumentation

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
eller kør 'ant javadoc' og start derefter ved build/javadoc/index.html

## Sådan bidrager du / Udvikling på I2P+

Se venligst [HACKING.md](docs/HACKING.md) og andre dokumenter i docs-mappen.

## Bygning af pakker fra kildekode

Hent udviklingsgrenen fra versionsstyringen: https://github.com/I2PPlus/i2pplus

### Forudsætninger

- Java SDK 1.8.0 eller nyere
- Apache Ant 1.9.8 eller nyere
- Værktøjerne xgettext, msgfmt og msgmerge installeret fra GNU gettext-pakken
  via din pakkehåndtering eller http://www.gnu.org/software/gettext/
- Byggemiljøet skal bruge et UTF-8-lokale.
- Til bygning af Debian-pakker: `dpkg-deb`- og `fakeroot`-pakker (via din pakkehåndtering)

### Ant-byggeproces

På x86-systemer køres følgende (dette bygger med IzPack4):

    ant pkg

På ikke-x86 skal du i stedet bruge én af følgende:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Hvis du vil bygge med IzPack5, skal du downloade fra: http://izpack.org/downloads/ og
derefter installere det og derefter køre følgende kommando(er):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

For at bygge en usigneret opdatering til en eksisterende installation skal du køre:

    ant updater

eller med Gradle:

    ./gradlew updater

Hvis du har problemer med at bygge et komplet installationsprogram (Java14 og nyere kan generere byggefejl for izpack i forbindelse med pack200),
kan du bygge en komplet installations-zip, som kan udpakkes og køres på stedet:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Kør 'ant' uden argumenter for at se andre byggeindstillinger.

For at bygge et AppImage til Linux:
```bash
ant buildAppImage
```

Se [tools/appimage/README.md](tools/appimage/README.md) for detaljer.

For at bygge en selvstændig Debian-pakke til Debian/Ubuntu uden eksterne Jetty/Tomcat-afhængigheder:
```bash
ant buildDeb
```

Dette opretter en selvstændig `.deb`-pakke, som indeholder medfølgende Jetty- og Tomcat-biblioteker. Kræver kun en OpenJDK-runtime (installeres automatisk via pakkehåndteringen).

For at køre i Docker, se [docker/README.md](docker/README.md)

## Kontaktoplysninger

Brug for hjælp? Besøg IRC-kanalen #saltR på I2P IRC-netværket

Fejlrapporter: https://github.com/I2PPlus/i2pplus/issues

## Licenser

I2P+ er licenseret under AGPL v.3.

For de forskellige underkomponenters licenser, se: [README.md](docs/LICENSES.md)

## Se også

### Dokumentation

- [docs/README.md](docs/README.md) - Fuld dokumentationsindeks
- [docs/INSTALL.md](docs/INSTALL.md) - Installationsvejledning
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless-installation (konsoltilstand)
- [docs/HACKING.md](docs/HACKING.md) - Udviklervejledning og byggesystemer
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Struktur af kildetræet og hvor tingene findes
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Overblik over routerens kildetræ
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Overblik over kernebibliotekets kildetræ
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Køretidsfejlsøgning med JDWP og andre værktøjer
- [docs/THEMING.md](docs/THEMING.md) - Konsol- og webapp-temasystem
- [docs/LICENSES.md](docs/LICENSES.md) - Tredjepartslicenser
- [docs/history.txt](docs/history.txt) - Fuld ændringslog

### Underprojekter

- [apps/README.md](apps/README.md) - Programoversigt
- [apps/addressbook/README.md](apps/addressbook/README.md) - Adressebogsprogram
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktop-GUI-program
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent-klient
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P Tunnel-program
- [apps/imagegen/README.md](apps/imagegen/README.md) - Værktøjer til billedgenerering
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP-server
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin-overvågningsbibliotek
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimal streaming-bibliotek
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200-komprimering
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy-skripter
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Routerkonsol
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming-bibliotek
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P-e-mail-klient
- [apps/systray/README.md](apps/systray/README.md) - Program til systembakken
- [core/README.md](core/README.md) - Dokumentation for kernebiblioteket
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Indfødt JNI-bibliotek til kryptografi (GMP)

### DIVERST

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Håndtering af I2P-sessionsforbud med nftables
- [installer/resources/README.md](installer/resources/README.md) - Medfølgende installationsprogramressourcer
- [tools/scripts/README.md](tools/scripts/README.md) - Hjælpeskripter til udvikling og administration
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validerings- og testskripter
