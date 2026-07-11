[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Ez a Java alapú I2P implementáció soft-fork verziójának forráskódja.

 legújabb kiadás: https://i2pplus.github.io/

## Telepítés

A telepítési utasításokért lásd: [INSTALL.md](INSTALL.md) vagy https://i2pplus.github.io/.

### Windows installer megjegyzés

Java > 1.8 vagy alternatív disztribúciók (AdoptOpenJDK stb.) használata esetén a telepítő exe sikertelen lehet "Java not found" vagy "invalid/corrupt" hibákkal. Megoldás: bontsa ki az install.jar-t az exe-ből és futtassa a `java -jar install.jar` parancsot a parancssorból.

## Dokumentáció

https://geti2p.net/how

GYIK: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
vagy futtasd az 'ant javadoc' parancsot, majd nyisd meg a build/javadoc/index.html fájlt

## Hogyan contribute- / Hackeld az I2P-t

Kérlek nézd meg a [HACKING.md](docs/HACKING.md) fájlt és a docs könyvtár egyéb dokumentumait.

## Csomagok fordítása forrásból

Ha a fejlesztői ágat szeretnéd megszerezni: https://github.com/I2PPlus/i2pplus/

### Előfeltételek

- Java SDK (lehetőleg Oracle/Sun vagy OpenJDK) 1.8.0 vagy újabb
- Apache Ant 1.9.8 vagy újabb
- GNU gettext csomagból telepített xgettext, msgfmt és msgmerge eszközök
  http://www.gnu.org/software/gettext/
- A fordítókörnyezetnek UTF-8 locale-t kell használnia.
- Debian csomagok fordításához: `dpkg-deb` és `fakeroot` csomagok (a csomagkezelőn keresztül)

### Ant fordítási folyamat

x86 rendszereken futtasd a következőt (IzPack4-gyel fordít):

    ant pkg

Nem x86 rendszereken használj egyet az alábbiak közül:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Ha IzPack5-tel szeretnél fordítani, töltsd le innen: http://izpack.org/downloads/ és telepítsd,
majd futtasd a következő parancsokat:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Ha egy meglévő telepítéshez szeretnél aláírás nélküli frissítést fordítani, futtasd:

    ant updater

Ha problémák adódnak a teljes installer fordításával (Java14 és újabb izpack pack200 hibákat dobhat),
fordíthatsz egy teljes telepítő zip-et is, amit ki lehet csomagolni és a helyén futtatni:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Futtasd az 'ant' parancsot argumentumok nélkül a további fordítási opciókért.

Autonóm Debian-csomag létrehozása Debian/Ubuntu-hoz külső Jetty/Tomcat-függőségek nélkül:
```bash
ant buildDeb
```

Ez egy autonóm `.deb` csomagot hoz létre, amely a kötegelt Jetty és Tomcat könyvtárakat tartalmazza külső függőségek nélkül.


Linux AppImage építéséhez:
```bash
ant buildAppImage
```

Részletekért lásd: [tools/appimage/README.md](tools/appimage/README.md).


Ha többet szeretnél tudni az I2P Docker-ben történő futtatásáról, lásd: [docker/README.md](docker/README.md)


## Kapcsolati adatok

Kell segítség? Nézd meg az IRC csatornát #saltR az I2P IRC hálózaton

Hibajelentések: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues vagy https://github.com/I2PPlus/i2pplus/issues

## Licenciák

Az I2P+ az AGPL v.3 licenciával rendelkezik.

A különböző alkomponensek licenciájáért lásd: [README.md](docs/LICENSES.md)

## Lásd még

### Dokumentáció

- [docs/README.md](docs/README.md) - Teljes dokumentációs jegyzék
- [docs/INSTALL.md](docs/INSTALL.md) - Telepítési útmutató
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Fej nélküli (konzol) telepítés
- [docs/HACKING.md](docs/HACKING.md) - Fejlesztői útmutató és build rendszerek
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Forrásfa elrendezés és hol mi található
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Futtatási hibakeresés JDWP-vel és más eszközökkel
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - I2P session tiltások kezelése nftables-szel
- [docs/LICENSES.md](docs/LICENSES.md) - Harmadik fél licenciák
- [docs/history.txt](docs/history.txt) - Teljes változtatási napló

### Alprojektek

- [apps/README.md](apps/README.md) - Alkalmazás áttekintés
- [apps/addressbook/README.md](apps/addressbook/README.md) - Címjegyzék alkalmazás
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Desktop GUI alkalmazás
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent kliens
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P Tunnel alkalmazás
- [apps/imagegen/README.md](apps/imagegen/README.md) - Képgeneráló eszközök
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP szerver
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin monitoring könyvtár
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimális streaming könyvtár
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 tömörítés
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy szkriptek
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router konzol
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Streaming könyvtár
- [apps/susidns/README.md](apps/susidns/README.md) - DNS szerver
- [apps/susimail/README.md](apps/susimail/README.md) - I2P levelező kliens
- [apps/systray/README.md](apps/systray/README.md) - Rendszer tálca alkalmazás
- [core/README.md](core/README.md) - Core könyvtár dokumentáció
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Natív JNI könyvtár kriptográfiához (GMP)

### Egyéb

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - I2P session tiltások kezelése nftables-szel
- [installer/resources/README.md](installer/resources/README.md) - Telepítő erőforrások
- [tools/scripts/README.md](tools/scripts/README.md) - Fejlesztési és adminisztrációs segédszkriptek
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Validációs és tesztelési szkriptek



- [docker/README.md](docker/README.md) - I2P+ futtatása Docker-ben

