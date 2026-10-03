[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Toto je zdrojový kód soft-forku implementácie I2P v jazyku Java.

Najnovšie vydanie: https://i2pplus.github.io/

## Inštalácia

Pozrite si [INSTALL.md](docs/INSTALL.md) alebo https://i2pplus.github.io/ pre podrobnosti k inštalácii.

### Poznámka k inštalátoru pre Windows

Pri použití Java > 1.8 alebo alternatívnych distribúcií (AdoptOpenJDK atď.) môže inštalátor exe zlyhať s chybami "Java not found" alebo "invalid/corrupt". Riešenie: extrahujte install.jar z exe a spustite `java -jar install.jar` z príkazového riadka.

## Dokumentácia

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
alebo spustite 'ant javadoc' a potom otvorte build/javadoc/index.html

## Ako prispieť / Práca na I2P+

Pozrite si [HACKING.md](docs/HACKING.md) a ďalšie dokumenty v adresári docs.

## Budovanie balíkov zo zdrojového kódu

Ak chcete získať vývojovú vetvu zo systému na správu zdrojového kódu: https://github.com/I2PPlus/i2pplus

### Požiadavky

- Java SDK 1.8.0 alebo vyššia
- Apache Ant 1.9.8 alebo vyšší
- nástroje xgettext, msgfmt a msgmerge nainštalované z balíka GNU gettext
  prostredníctvom správcu balíkov alebo http://www.gnu.org/software/gettext/
- Prostredie na zostavenie musí používať lokalitu UTF-8.
- Pre zostavenie balíkov Debian: balíky `dpkg-deb` a `fakeroot` (prostredníctvom správcu balíkov)
- Pre exe súbor IzPack 5 pre Windows (`ant installer5`, `ant installer5-windows`): Python 3.
  `izpack2exe.py` od IzPacku je napísaný v jazyku Python 3, ale jeho shebang uvádza `python`,
  preto zostavenie predáva interpret explicitne; možno ho prepísať pomocou `izpack5.python`.

### Proces zostavenia pomocou Ant

Na systémoch x86 spustite nasledujúce (zostavenie prebehne pomocou IzPack4):

    ant pkg

Na systémoch iných ako x86 použite namiesto toho jedno z nasledujúcich:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Ak chcete zostaviť pomocou IzPack5, spustite nasledujúce príkaz(y). Pri prvom použití
si stiahnu distribúciu IzPack 5 do `installer/lib/izpack/5/` — čo vyžaduje prístup
k sieti a ~95MB voľného miesta — a automaticky ju udržujú aktuálnu:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Ak ju chcete stiahnuť alebo aktualizovať bez zostavenia inštalátora:

    ant download-izpack5

Ak chcete zostaviť nepodpísanú aktualizáciu pre existujúcu inštaláciu, spustite:

    ant updater

alebo pomocou Gradle:

    ./gradlew updater

Ak máte problémy so zostavením kompletného inštalátora (Java14 a novšie môžu generovať chyby zostavenia
pre izpack súvisiace s pack200), môžete zostaviť kompletný inštalačný zip, ktorý možno rozbaliť a spustiť priamo na mieste:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Ak chcete vidieť ďalšie možnosti zostavenia, spustite 'ant' bez argumentov.

Ak chcete zostaviť AppImage pre Linux:
```bash
ant buildAppImage
```

Podrobnosti pozrite v [tools/appimage/README.md](tools/appimage/README.md).

Ak chcete zostaviť samostatný balík pre Debian/Ubuntu bez externých závislostí Jetty/Tomcat:
```bash
ant buildDeb
```

Tým sa vytvorí samostatný `.deb` balík, ktorý obsahuje pribalené knižnice Jetty a Tomcat. Vyžaduje iba bežiace prostredie OpenJDK (inštalované automaticky prostredníctvom správcu balíkov).

Ak chcete spustiť v Docker, pozrite si [docker/README.md](docker/README.md)

## Kontaktné údaje

Potrebujete pomoc? Navštívte IRC kanál #saltR na I2P IRC sieti

Hlásenie chýb: https://github.com/I2PPlus/i2pplus/issues

## Licencie

I2P+ je licencovaný pod licenciou AGPL v.3.

Licencie jednotlivých podkomponentov: [README.md](docs/LICENSES.md)

## Pozri tiež

### Dokumentácia

- [docs/README.md](docs/README.md) - Úplný index dokumentácie
- [docs/INSTALL.md](docs/INSTALL.md) - Inštalačná príručka
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless inštalácia (konzolový režim)
- [docs/HACKING.md](docs/HACKING.md) - Príručka pre vývojárov a systémy zostavenia
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Štruktúra zdrojového stromu a kde čo nájsť
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Prehľad zdrojového stromu smerovača
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Prehľad zdrojového stromu knižnice jadra
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Ladenie počas behu pomocou JDWP a iných nástrojov
- [docs/THEMING.md](docs/THEMING.md) - Systém tém konzoly a webovej aplikácie
- [docs/LICENSES.md](docs/LICENSES.md) - Licencie tretích strán
- [docs/history.txt](docs/history.txt) - Úplný zoznam zmien

### Podprojekty

- [apps/README.md](apps/README.md) - Prehľad aplikácií
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplikácia knihy adries
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktopová GUI aplikácia
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - BitTorrent klient I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplikácia I2PTunnel
- [apps/imagegen/README.md](apps/imagegen/README.md) - Nástroje na generovanie obrázkov
- [apps/jetty/README.md](apps/jetty/README.md) - HTTP server Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Knižnica monitorovania JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimálna knižnica streaming
- [apps/pack200/README.md](apps/pack200/README.md) - Kompresia Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Skripty proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Konzola smerovača
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Knižnica streaming
- [apps/susidns/README.md](apps/susidns/README.md) - DNS server
- [apps/susimail/README.md](apps/susimail/README.md) - E-mailový klient I2P
- [apps/systray/README.md](apps/systray/README.md) - Aplikácia pre systémovú lištu
- [core/README.md](core/README.md) - Dokumentácia knižnice jadra
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Natívna JNI knižnica pre kryptografiu (GMP)

### RÔZNE

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Správa zákazov relácií I2P pomocou nftables
- [installer/resources/README.md](installer/resources/README.md) - Pribalené zdroje inštalátora
- [tools/scripts/README.md](tools/scripts/README.md) - Pomocné skripty pre vývoj a správu
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Skripty na overovanie a testovanie
