[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Tämä on I2P:n Java-toteutuksen lähdekoodi soft-forkille.

Uusin versio: https://i2pplus.github.io/

## Asennus

Katso asennusohjeet tiedostosta [INSTALL.md](docs/INSTALL.md) tai osoitteesta https://i2pplus.github.io/

### Huomio Windows-asentajasta

Java-ympäristössä > 1.8 tai vaihtoisilla jakeluilla (AdoptOpenJDK jne.) asentaja-exe voi epäonnistua "Java not found" tai "invalid/corrupt" -virheillä. Kiertotie: pura install.jar exe-tiedostosta ja aja `java -jar install.jar` komentorivillä.

## Dokumentaatio

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
tai aja 'ant javadoc' ja aloita sivulta build/javadoc/index.html

## Miten osallistua / Kehittää I2P+:aa

Tutustu [HACKING.md](docs/HACKING.md)-dokumenttiin ja muihin docs-hakemiston dokumentteihin.

## Pakettien rakentaminen lähdetiedostoista

Kehityshaaran hakeminen lähdehallinnasta: https://github.com/I2PPlus/i2pplus

### Valmiusedellytykset

- Java SDK 1.8.0 tai uudempi
- Apache Ant 1.9.8 tai uudempi
- GNU gettext -paketista asennetut xgettext-, msgfmt- ja msgmerge-työkalut
  pakettihallinnan kautta tai osoitteesta http://www.gnu.org/software/gettext/
- Käännösympäristön on käytettävä UTF-8-kieliasetusta.
- Debian-pakettien rakentamiseksi: `dpkg-deb`- ja `fakeroot`-paketit (pakettihallinnan kautta)
- IzPack 5:n Windows-exe (`ant installer5`, `ant installer5-windows`): Python 3.
  IzPackin `izpack2exe.py` on Python 3, mutta sen shebang ilmoittaa `python`, joten
  build välittää tulkin eksplisiittisesti. Voit ohittaa sen asetuksella `izpack5.python`.

### Ant-käännösprosessi

x86-järjestelmissä aja seuraava (tämä rakentaa käyttäen IzPack4):

    ant pkg

Muissa kuin x86-järjestelmissä käytä sen sijaan jotain seuraavista:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Jos haluat rakentaa IzPack5:llä, aja seuraavat komennot. Ne lataavat IzPack 5 -jakauman
ensimmäisellä käyttökerralla hakemistoon `installer/lib/izpack/5/` (vaatii verkkoyhteyden
ja ~95MB vapaata tilaa) ja pitävät sen automaattisesti ajan tasalla:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Voit hakea tai päivittää sen ilman installerin rakentamista:

    ant download-izpack5

Allekirjoittamattoman päivityksen rakentamiseksi olemassa olevalle asennukselle aja:

    ant updater

tai Gradlella:

    ./gradlew updater

Jos kokonaisen asentajan rakentamisessa on ongelmia (Java14 ja myöhemmät voivat aiheuttaa izpackiin pack200:een
liittyviä käännösvirheitä), voit rakentaa täydellisen asennuszipin, joka voidaan purkaa ja ajaa paikallaan:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Aja 'ant' ilman argumentteja nähdäksesi muut käännösvaihtoehdot.

AppImage-paketin rakentamiseksi Linuxille:
```bash
ant buildAppImage
```

Katso yksityiskohdat tiedostosta [tools/appimage/README.md](tools/appimage/README.md).

Oman sisältävän Debian-paketin rakentamiseksi Debian/Ubuntu:lle ilman ulkoisia Jetty/Tomcat-riippuvuuksia:
```bash
ant buildDeb
```

Tämä luo oman sisältävän `.deb`-paketin, joka sisältää mukana toimitetut Jetty- ja Tomcat-kirjastot. Vaatii vain OpenJDK-ajoympäristön (asennetaan automaattisesti pakettihallinnan kautta).

Dockerissa ajamiseksi katso [docker/README.md](docker/README.md)

## Yhteystiedot

Tarvitsetko apua? Vieraile IRC-kanavalla #saltR I2P:n IRC-verkossa

Virheraportit: https://github.com/I2PPlus/i2pplus/issues

## Lisenssit

I2P+ on lisensoitu AGPL v.3:n mukaisesti.

Eri osakomponenttien lisensseistä katso: [README.md](docs/LICENSES.md)

## Katso myös

### Dokumentaatio

- [docs/README.md](docs/README.md) - Täydellinen dokumentaatioluettelo
- [docs/INSTALL.md](docs/INSTALL.md) - Asennusopas
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless-asennus (konsolitila)
- [docs/HACKING.md](docs/HACKING.md) - Kehittäjän opas ja käännösjärjestelmät
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Lähdepuun rakenne ja mistä asiat löytyvät
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Reitittimen lähdepuun yleiskuva
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Ydin-kirjaston lähdepuun yleiskuva
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Ajoaikainen vianjälitys JDWP:n ja muiden työkalujen avulla
- [docs/THEMING.md](docs/THEMING.md) - Konsolin ja web-sovelluksen teemajärjestelmä
- [docs/LICENSES.md](docs/LICENSES.md) - Kolmansien osapuolten lisenssit
- [docs/history.txt](docs/history.txt) - Täydellinen muutosloki

### Aliprojektit

- [apps/README.md](apps/README.md) - Sovellusten yleiskuva
- [apps/addressbook/README.md](apps/addressbook/README.md) - Osoitekirjasovellus
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Työpöydän käyttöliittymäsovellus
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent-asiakasohjelma
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P Tunnel -sovellus
- [apps/imagegen/README.md](apps/imagegen/README.md) - Kuvagenerointityökalut
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP -palvelin
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin-valvontakirjasto
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimaalinen streaming-kirjasto
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200-pakkaus
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy-skriptit
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Reitittimen konsoli
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming-kirjasto
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-palvelin
- [apps/susimail/README.md](apps/susimail/README.md) - I2P-sähköpostiohjelma
- [apps/systray/README.md](apps/systray/README.md) - Järjestelmäteline-sovellus
- [core/README.md](core/README.md) - Ydin-kirjaston dokumentaatio
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Alkuperäinen JNI-kirjasto salaukseen (GMP)

### Sekalaista

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - I2P-istuntokieltojen hallinta nftablesilla
- [installer/resources/README.md](installer/resources/README.md) - Mukana toimitetut asentajan resurssit
- [tools/scripts/README.md](tools/scripts/README.md) - Apuskriptit kehitykseen ja ylläpitoon
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validointi- ja testaus-skriptit
