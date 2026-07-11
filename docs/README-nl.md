[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Dit is de broncode voor de soft-fork van de Java-implementatie van I2P.

 nieuwste release: https://i2pplus.github.io/

## Installatie

Zie [INSTALL.md](INSTALL.md) of https://i2pplus.github.io/ voor installatie-instructies.

### Opmerking over Windows-installatieprogramma

Met Java > 1.8 of alternatieve distributies (AdoptOpenJDK, etc.) kan de installer-exe falen met "Java not found" of "invalid/corrupt" fouten. Workaround: pak install.jar uit de exe en voer `java -jar install.jar` uit vanaf de opdrachtregel.

## Documentatie

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
of voer 'ant javadoc' uit en start dan bij build/javadoc/index.html

## Hoe bij te dragen / Hacken op I2P

Bekijk [HACKING.md](docs/HACKING.md) en andere documenten in de docs-directory.

## Pakketten bouwen vanuit broncode

Om de development-branch te verkrijgen via source control: https://github.com/I2PPlus/i2pplus/

### Voorwaarden

- Java SDK (liefst Oracle/Sun of OpenJDK) 1.8.0 of hoger
  - Bepaalde subsystemen voor embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 of hoger
- De xgettext, msgfmt en msgmerge tools geïnstalleerd vanuit het GNU gettext-pakket
  http://www.gnu.org/software/gettext/
- De build-omgeving moet een UTF-8 locale gebruiken.
- Voor Debian-pakket builds: `dpkg-deb` en `fakeroot` pakketten (via uw pakketbeheerder)

### Ant build-proces

Op x86-systemen voer het volgende uit (dit bouwt met IzPack4):

    ant pkg

Op non-x86 gebruik je in plaats daarvan een van de volgende:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Als je wilt bouwen met IzPack5, download dan van: http://izpack.org/downloads/ en installeer het,
en voer vervolgens de volgende commando(s) uit:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Om een niet-ondertekende update voor een bestaande installatie te bouwen, voer uit:

    ant updater

Als je problemen hebt met het bouwen van een volledige installer (Java14 en later kunnen build-fouten genereren voor izpack met betrekking tot pack200),
kun je een volledige installatie-zip bouwen die kan worden uitgepakt en ter plaatse kan worden uitgevoerd:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Voer 'ant' uit zonder argumenten om andere build-opties te zien.

Om een standalone Debian-pakket te maken voor Debian/Ubuntu zonder externe Jetty/Tomcat-afhankelijkheden:
```bash
ant buildDeb
```

Dit maakt een standalone `.deb`-pakket dat de gebundelde Jetty- en Tomcat-bibliotheken bevat zonder externe afhankelijkheden.


Om een AppImage voor Linux te bouwen:
```bash
ant buildAppImage
```

Zie [tools/appimage/README.md](tools/appimage/README.md) voor details.


Raadpleeg [docker/README.md](docker/README.md) voor meer informatie over het uitvoeren van I2P in Docker.


## Contactgegevens

Hulp nodig? Bezoek het IRC-kanaal #saltR op het I2P IRC-netwerk

Bug-rapporten: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues of https://github.com/I2PPlus/i2pplus/issues

## Licenties

I2P+ is gelicentieerd onder AGPL v.3.

Voor de verschillende sub-component licenties, zie: [README.md](docs/LICENSES.md)

## Zie ook

### Documentatie

- [docs/README.md](docs/README.md) - Volledige documentatie-index
- [docs/INSTALL.md](docs/INSTALL.md) - Installatiegids
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless (console-modus) installatie
- [docs/HACKING.md](docs/HACKING.md) - Ontwikkelaarsgids en build-systemen
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Bronboom-layout en waar dingen te vinden
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Runtime-debugging met JDWP en andere tools
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - I2P-sessieverboden beheren met nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Licenties van derden
- [docs/history.txt](docs/history.txt) - Volledige wijzigingslog

### Sub-projects

- [apps/README.md](apps/README.md) - Applicatie-overzicht
- [apps/addressbook/README.md](apps/addressbook/README.md) - Adresboek-applicatie
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktop GUI-applicatie
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent-client
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P Tunnel-applicatie
- [apps/imagegen/README.md](apps/imagegen/README.md) - Afbeeldingsgeneratie-tools
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP-server
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin monitoring-bibliotheek
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimale streaming-bibliotheek
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200-compressie
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy-scripts
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router-console
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming-bibliotheek
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P e-mailclient
- [apps/systray/README.md](apps/systray/README.md) - Systeemvak-toepassing
- [core/README.md](core/README.md) - Core-bibliotheek-documentatie
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Native JNI-bibliotheek voor cryptografie (GMP)

### MISC

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - I2P-sessieverboden beheren met nftables
- [installer/resources/README.md](installer/resources/README.md) - Gebundelde installer-resources
- [tools/scripts/README.md](tools/scripts/README.md) - Nutsscripts voor ontwikkeling en beheer
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validatie- en test-scripts



- [docker/README.md](docker/README.md) - I2P+ uitvoeren in Docker

