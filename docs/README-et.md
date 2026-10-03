[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

See on I2P Java-tarkvara pehme kahvli (soft-fork) lähtekood.

Viimane versioon: https://i2pplus.github.io/

## Paigaldamine

Paigaldusjuhised on leitavad dokumendis [INSTALL.md](docs/INSTALL.md) ning veebisaidil https://i2pplus.github.io/

### Windowsi paigaldaja märkus

Java > 1.8 või alternatiivsete jaotuste (AdoptOpenJDK jne) puhul võib paigaldaja exe tõrkega "Java not found" või "invalid/corrupt" ebaõnnestuda. Lahendusena: võtke install.jar exe-failist välja ja käivitage käsurealt `java -jar install.jar`.

## Dokumentatsioon

https://geti2p.net/how

KKK: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
või käivitage 'ant javadoc' ning seejärel alustage failist build/javadoc/index.html

## Kuidas panustada / I2P+ kallal töötada

Palun tutvuge failiga [HACKING.md](docs/HACKING.md) ja teiste dokumentidega kataloogis docs.

## Pakettide ehitamine lähtekoodist

Arendusharu hankimiseks lähtekoodi haldussüsteemist: https://github.com/I2PPlus/i2pplus

### Eeldused

- Java SDK 1.8.0 või uuem
- Apache Ant 1.9.8 või uuem
- GNU gettext paketist paigaldatud tööriistad xgettext, msgfmt ja msgmerge
  teie pakihalduri kaudu või aadressilt http://www.gnu.org/software/gettext/
- Ehituskeskkonnas peab olema UTF-8 lokaat.
- Debiani pakettide ehitamiseks: `dpkg-deb` ja `fakeroot` pakid (teie pakihalduri kaudu)
- IzPack 5 Windows exe jaoks (`ant installer5`, `ant installer5-windows`): Python 3.
  IzPacki `izpack2exe.py` on Python 3, kuid selle shebang räägib `python`, mistõttu build
  annab tõlgendaja teadlikult edasi. Selle saab asendada `izpack5.python`-ga.

### Ehitusprotsess Anti abil

x86-süsteemidel käivitage järgmine (see ehitatakse IzPack4 abil):

    ant pkg

x86-välistel süsteemidel kasutage selle asemel ühte järgmistest:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Kui soovite ehitada IzPack5 abil, käivitage järgmine(d) käsk(ud). Need laadivad IzPack 5
leviku esimesel kasutamisel kaustasse `installer/lib/izpack/5/` (vajab võrguühendust ja
~95MB vaba ruumi) ning hoiavad selle automaatselt ajakohasena:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Selle hankimiseks või värskendamiseks ilma installeri ehitamiseta:

    ant download-izpack5

Olemasoleva paigalduse jaoks allkirjastamata uuenduse ehitamiseks käivitage:

    ant updater

või Gradle'iga:

    ./gradlew updater

Kui teil on probleeme täieliku paigaldaja ehitamisel (Java14 ja uuemad võivad tekitada izpacki pack200-ga seotud veateateid),
saate ehitada täieliku paigalduszip-faili, mida saab lahtipakkida ja kohapeal käivitada:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Muude ehitusvalikute nägemiseks käivitage 'ant' ilma argumentideta.

Linuxi AppImage'i ehitamiseks:
```bash
ant buildAppImage
```

Üksikasjad: [tools/appimage/README.md](tools/appimage/README.md).

Debian/Ubuntu jaoks iseseisva Debiani paketi ehitamiseks ilma väliste Jetty/Tomcat-sõltuvusteta:
```bash
ant buildDeb
```

See loob iseseisva `.deb`-paki, mis sisaldab kaasas olevaid Jetty ja Tomcat teeke. Nõuab ainult OpenJDK jooksutuskeskkonda (paigaldatakse automaatselt pakihalduri abil).

Dockeris käivitamiseks vt [docker/README.md](docker/README.md)

## Kontaktandmed

Vajate abi? Külastage I2P IRC-võrgu kanalit #saltR

Vearaportid: https://github.com/I2PPlus/i2pplus/issues

## Litsentsid

I2P+ on litsentseeritud AGPL v.3 litsentsi alusel.

Alamkomponentide litsentside kohta vt: [README.md](docs/LICENSES.md)

## Vaata ka

### Dokumentatsioon

- [docs/README.md](docs/README.md) - Täielik dokumentatsiooni register
- [docs/INSTALL.md](docs/INSTALL.md) - Paigaldusjuhend
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Headless-paigaldus (konsolirežiim)
- [docs/HACKING.md](docs/HACKING.md) - Arendaja juhend ja ehitussüsteemid
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Lähtepuu struktuur ja kuhu asju leida
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Ruuteri lähtepuu ülevaade
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Tuumteegi lähtepuu ülevaade
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Jooksuaegne silumine JDWP ja muude tööriistadega
- [docs/THEMING.md](docs/THEMING.md) - Konsoli ja veebirakenduste teemade süsteem
- [docs/LICENSES.md](docs/LICENSES.md) - Kolmanda poole litsentsid
- [docs/history.txt](docs/history.txt) - Täielik muudatuste logi

### Alamprojektid

- [apps/README.md](apps/README.md) - Rakenduste ülevaade
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aadressiraamatu rakendus
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Töölaua GUI rakendus
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrenti klient
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P tunnelirakendus
- [apps/imagegen/README.md](apps/imagegen/README.md) - Pildigeneratsiooni tööriistad
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP-server
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin seireteek
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimaalne voogedastusteek
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 tihendus
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxyskiptid
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Ruuteri konsool
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Voogedastusteek
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-server
- [apps/susimail/README.md](apps/susimail/README.md) - I2P e-posti klient
- [apps/systray/README.md](apps/systray/README.md) - Süsteemisalve rakendus
- [core/README.md](core/README.md) - Tuumteegi dokumentatsioon
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Krüptograafia jaoks süsteemikohane JNI-teek (GMP)

### Muud

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - I2P seansside keelustamine nftablesi abil
- [installer/resources/README.md](installer/resources/README.md) - Kaasas olevad paigaldaja ressursid
- [tools/scripts/README.md](tools/scripts/README.md) - Arenduseks ja halduseks mõeldud abiskriptid
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Valideerimise ja testimise skriptid
