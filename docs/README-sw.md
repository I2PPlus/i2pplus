[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

Hiki ni chanzo cha msimbo cha soft-fork ya utekelezaji wa Java wa I2P.

Toleo jipya zaidi: https://i2pplus.github.io/

## Usakinishaji

Angalia [INSTALL.md](docs/INSTALL.md) au https://i2pplus.github.io/ kwa maagizo ya usakinishaji.

### Maelezo kuhusu kisakinishaji cha Windows

Na Java > 1.8 au usambazaji mbadala (AdoptOpenJDK, n.k.), faili ya exe ya kisakinishaji inaweza kushindwa na makosa "Java not found" au "invalid/corrupt". Njia ya mbadala: fungua install.jar kutoka kwenye exe na endesha `java -jar install.jar` kutoka kwenye mstari wa amri.

## Nyaraka

https://geti2p.net/how

Maswali: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
au endesha 'ant javadoc' kisha anza katika build/javadoc/index.html

## Jinsi ya kuchangia / Kufanya kazi kwenye I2P+

Tafadhali angalia [HACKING.md](docs/HACKING.md) na nyaraka nyingine katika mfolda wa docs.

## Kujenga vifurushi kutoka kwenye chanzo

Kupata tawi la maendeleo kutoka kwenye udhibiti wa chanzo: https://github.com/I2PPlus/i2pplus

### Mahitaji ya awali

- Java SDK 1.8.0 au zaidi
- Apache Ant 1.9.8 au zaidi
- Zana za xgettext, msgfmt, na msgmerge zilizosakinishwa kutoka kwenye kifurushi cha GNU gettext
  kupitia kijidhibiti chako cha vifurushi au http://www.gnu.org/software/gettext/
- Mazingira ya kujenga lazima yatumie lugha (locale) ya UTF-8.
- Kwa kujenga vifurushi vya Debian: vifurushi `dpkg-deb` na `fakeroot` (kupitia kijidhibiti chako cha vifurushi)
- Kwa exe ya IzPack 5 kwenye Windows (`ant installer5`, `ant installer5-windows`): Python 3.
  `izpack2exe.py` ya IzPack ni Python 3 lakini shebang yake inasema `python`, hivyo
  kujenga hupitisha kipeleuzi kwa moja kwa moja; badilisha kwa `izpack5.python`.

### Mchakato wa kujenga wa Ant

Kwenye mifumo ya x86 endesha yafuatayo (hii itajenga kwa kutumia IzPack4):

    ant pkg

Kwenye mifumo isiyokuwa ya x86, badala yake tumia moja kati ya yafuatayo:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Ukitaka kujenga kwa kutumia IzPack5, endesha amri zifuatazo. Amri hizi hupakua
usambazaji wa IzPack 5 kwenye `installer/lib/izpack/5/` wakati wa matumizi ya kwanza —
ambayo huhitaji muunganisho wa mtandao na nafasi ya bure ya ~95MB — na huweka
ikusarinishwa kwa njia moja kwa moja:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Kupakua au kusasisha IzPack5 bila kujenga kisakinishaji:

    ant download-izpack5

Kujenga masasisho isiyotiwa saini kwa usakinishaji uliopo, endesha:

    ant updater

au kwa Gradle:

    ./gradlew updater

Ukiwa na matatizo ya kujenga kisakinishaji kamili (Java14 na baadaye zinaweza kutoa makosa ya kujenga ya izpack yanayohusiana na pack200),
unaweza kujenga faili zip kamili ya usakinishaji inayoweza kufunguliwa na kuendeshwa mahali ulipo:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Endesha 'ant' bila hoja kuona chaguo nyingine za kujenga.

Kujenga AppImage kwa Linux:
```bash
ant buildAppImage
```

Angalia [tools/appimage/README.md](tools/appimage/README.md) kwa maelezo.

Kujenga kifurushi cha Debian kinachojitosheza kwa Debian/Ubuntu bila utegemezi wa nje wa Jetty/Tomcat:
```bash
ant buildDeb
```

Hii hufanya kifurushi cha `.deb` kinachojitosheza chenye maktabi za Jetty na Tomcat zilizounganishwa. Kinahitaji tu mzunguko wa OpenJDK (unasakinishwa kiotomatiki kupitia kijidhibiti cha vifurushi).

Kwa kuendesha kwenye Docker, angalia [docker/README.md](docker/README.md)

## Taarifa za mawasiliano

Unahitaji msaada? Tembelea kituo cha IRC #saltR kwenye mtandao wa IRC wa I2P

Ripoti za hitilafu: https://github.com/I2PPlus/i2pplus/issues

## Leseni

I2P+ ina leseni chini ya AGPL v.3.

Kwa leseni za vipengele mbalimbali vya ndani, angalia: [README.md](docs/LICENSES.md)

## Tazama pia

### Nyaraka

- [docs/README.md](docs/README.md) - Faharasa kamili ya nyaraka
- [docs/INSTALL.md](docs/INSTALL.md) - Mwongozo wa usakinishaji
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Usakinishaji wa headless (hali ya konsoli)
- [docs/HACKING.md](docs/HACKING.md) - Mwongozo wa wasanidi na mifumo ya kujenga
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Muundo wa mti wa chanzo na mahali pa kupata vitu
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - Muhtasari wa mti wa chanzo wa kiruta
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - Muhtasari wa mti wa chanzo wa maktabi ya msingi
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Utatuzi wa wakati wa uendeshaji kwa JDWP na zana nyingine
- [docs/THEMING.md](docs/THEMING.md) - Mfumo wa mandharinyuma wa konsoli na programu za wavuti
- [docs/LICENSES.md](docs/LICENSES.md) - Leseni za wahusika wa tatu
- [docs/history.txt](docs/history.txt) - Katalogi kamili ya mabadiliko

### Miradi midogo

- [apps/README.md](apps/README.md) - Muhtasari wa programu
- [apps/addressbook/README.md](apps/addressbook/README.md) - Programu ya kitabu cha anwani
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Programu ya GUI ya eneo-kazi
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark mteja wa BitTorrent
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Programu ya tunnel ya I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Zana za kutengeneza picha
- [apps/jetty/README.md](apps/jetty/README.md) - Seva ya HTTP ya Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Maktabi ya ufuatiliaji ya JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Maktabi ndogo ya kutiririka
- [apps/pack200/README.md](apps/pack200/README.md) - Mfinyo wa Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Skripti za proksi
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Konsoli ya kiruta
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Maktabi ya kutiririka
- [apps/susidns/README.md](apps/susidns/README.md) - Seva ya DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Mteja wa barua pepe wa I2P
- [apps/systray/README.md](apps/systray/README.md) - Programu ya jopo la mfumo
- [core/README.md](core/README.md) - Nyaraka za maktabi ya msingi
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Maktabi ya asili ya JNI kwa usimbaji fiche (GMP)

### Mbalimbali

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Kudhibiti marufuku za vikao vya I2P kwa nftables
- [installer/resources/README.md](installer/resources/README.md) - Rasilimali za kisakinishaji zilizounganishwa
- [tools/scripts/README.md](tools/scripts/README.md) - Skripti za matumizi kwa maendeleo na utawala
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Skripti za uthibitisho na majaribio
