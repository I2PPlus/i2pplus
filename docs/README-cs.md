[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Toto je zdrojový kód soft-forku Java implementace I2P.

Nejnovější vydání: https://i2pplus.github.io/

## Instalace

Pokyny k instalaci najdete v [INSTALL.md](INSTALL.md) nebo na https://i2pplus.github.io/.

### Poznámka k Windows installeru

Při použití Java > 1.8 nebo alternativních distribucí (AdoptOpenJDK atd.) může exe installer selhat s chybami "Java not found" nebo "invalid/corrupt". Řešení: rozbalte install.jar z exe a spusťte `java -jar install.jar` z příkazového řádku.

## Dokumentace

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
nebo spusťte 'ant javadoc' a poté začněte v build/javadoc/index.html

## Jak přispět / Hackovat na I2P

Prohlédněte si prosím [HACKING.md](docs/HACKING.md) a další dokumenty v adresáři docs.

## Sestavování balíčků ze zdrojového kódu

Chcete-li získat vývojovou větev ze správy zdrojového kódu: https://github.com/I2PPlus/i2pplus/

### Požadavky

- Java SDK (nejlépe Oracle/Sun nebo OpenJDK) 1.8.0 nebo vyšší
  - Některé subsystémy pro vestavěné systémy (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 nebo vyšší
- Nástroje xgettext, msgfmt a msgmerge nainstalované z balíčku GNU gettext
  http://www.gnu.org/software/gettext/
- Buildovací prostředí musí používat UTF-8 locale.
- Pro sestavování Debian balíčků: balíčky `dpkg-deb` a `fakeroot` (přes správce balíčků)
- Pro exe soubor IzPack 5 pro Windows (`ant installer5`, `ant installer5-windows`): Python 3.
  `izpack2exe.py` od IzPacku je v Pythonu 3, ale jeho shebang říká `python`, takže sestavení
  předá interpret explicitně; lze to přepsat pomocí `izpack5.python`.

### Buildovací proces Ant

Na systémech x86 spusťte následující (bude sestaveno pomocí IzPack4):

    ant pkg

Na non-x86 systémech použijte místo toho jednu z následujících možností:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Chcete-li sestavovat pomocí IzPack5, spusťte následující příkaz(y). Při prvním použití
si stáhnou distribuci IzPack 5 do `installer/lib/izpack/5/` — což vyžaduje přístup
k síti a ~95MB volného místa — a automaticky ji udržují aktuální:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Chcete-li ji stáhnout nebo aktualizovat, aniž byste sestavovali instalátor:

    ant download-izpack5

Chcete-li sestavit nepodepsanou aktualizaci pro stávající instalaci, spusťte:

    ant updater

Pokud máte problémy se sestavením úplného instalátoru (Java14 a novější mohou generovat build chyby pro izpack týkající se pack200),
můžete sestavit úplný instalační zip, který lze rozbalit a spustit na místě:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Spusťte 'ant' bez argumentů pro zobrazení dalších možností sestavení.

Pro vytvoření samostatného Debian balíčku pro Debian/Ubuntu bez externích závislostí Jetty/Tomcat:
```bash
ant buildDeb
```

Toto vytvoří samostatný `.deb` balíček, který obsahuje sdružené knihovny Jetty a Tomcat bez externích závislostí.


Pro vytvoření AppImage pro Linux:
```bash
ant buildAppImage
```

Podrobnosti viz [tools/appimage/README.md](tools/appimage/README.md).


Další informace o spouštění I2P v Docker naleznete v [docker/README.md](docker/README.md)


## Kontaktní informace

Potřebujete pomoc? Navštivte IRC kanál #saltR v síti I2P IRC

Nahlašování chyb: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues nebo https://github.com/I2PPlus/i2pplus/issues

## Licence

I2P+ je licencováno pod AGPL v.3.

Pro licence různých subkomponent viz: [README.md](docs/LICENSES.md)

## Viz také

### Dokumentace

- [docs/README.md](docs/README.md) - Úplný rejstřík dokumentace
- [docs/INSTALL.md](docs/INSTALL.md) - Průvodce instalací
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Instalace bez GUI (konzolový režim)
- [docs/HACKING.md](docs/HACKING.md) - Průvodce pro vývojáře a build systémy
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Rozložení zdrojového stromu
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Ladicí runtime prostředí pomocí JDWP a dalších nástrojů
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Správa zákazů relací I2P pomocí nftables
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system
- [docs/LICENSES.md](docs/LICENSES.md) - Licence třetích stran
- [docs/history.txt](docs/history.txt) - Úplný seznam změn

### Sub-projekty

- [apps/README.md](apps/README.md) - Přehled aplikací
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplikace adresář
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktopová GUI aplikace
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent klient
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplikace I2P Tunnel
- [apps/imagegen/README.md](apps/imagegen/README.md) - Nástroje pro generování obrázků
- [apps/jetty/README.md](apps/jetty/README.md) - HTTP server Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Monitorovací knihovna JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimální streamovací knihovna
- [apps/pack200/README.md](apps/pack200/README.md) - Komprese Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy skripty
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router konzole
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streamovací knihovna
- [apps/susidns/README.md](apps/susidns/README.md) - DNS server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P email klient
- [apps/systray/README.md](apps/systray/README.md) - System tray aplikace
- [core/README.md](core/README.md) - Dokumentace core knihovny
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Nativní JNI knihovna pro kryptografii (GMP)

### Různé

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Správa zákazů relací I2P pomocí nftables
- [installer/resources/README.md](installer/resources/README.md) - Zdroje instalátoru
- [tools/scripts/README.md](tools/scripts/README.md) - Utilitní skripty pro vývoj a správu
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validační a testovací skripty



- [docker/README.md](docker/README.md) - Spouštění I2P+ v Docker

