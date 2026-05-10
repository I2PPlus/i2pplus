[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

To jest kod źródłowy miękkiego fork (soft-fork) implementacji Java I2P.

Najnowsze wydanie: https://i2pplus.github.io/

## Instalacja

Instrukcje instalacji znajdują się w [INSTALL.md](INSTALL.md) lub na https://i2pplus.github.io/.

### Uwaga dotycząca instalatora Windows

Przy używaniu Java > 1.8 lub alternatywnych dystrybucji (AdoptOpenJDK itp.) instalator exe może zakończyć się błędami "Java not found" lub "invalid/corrupt". Rozwiązanie: wyodrębnij install.jar z exe i uruchom `java -jar install.jar` z linii poleceń.

## Dokumentacja

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
lub uruchom 'ant javadoc', a następnie zacznij od build/javadoc/index.html

## Jak przyczynić się / Programować na I2P

Proszę sprawdź [HACKING.md](docs/HACKING.md) i inne dokumenty w katalogu docs.

## Budowanie pakietów ze źródeł

Aby uzyskać gałąź deweloperską z kontroli źródła: https://github.com/I2PPlus/i2pplus/

### Wymagania wstępne

- Java SDK (najlepiej Oracle/Sun lub OpenJDK) 1.8.0 lub wyższa
  - Niektóre podsystemy dla wbudowanych (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 lub wyższy
- Narzędzia xgettext, msgfmt i msgmerge zainstalowane z pakietu GNU gettext
  http://www.gnu.org/software/gettext/
- Środowisko budowania musi używać lokalizacji UTF-8.
- Do budowania pakietów Debian: pakiety `dpkg-deb` i `fakeroot` (za pośrednictwem menedżera pakietów)

### Proces budowania z Ant

Na systemach x86 uruchom następujące (to zbuduje używając IzPack4):

    ant pkg

Na systemach nie-x86, użyj jednego z następujących zamiast tego:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Jeśli chcesz budować z IzPack5, pobierz z: http://izpack.org/downloads/
następnie zainstaluj, a następnie uruchom następujące polecenie(a):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Aby zbudować niepodpisany update dla istniejącej instalacji, uruchom:

    ant updater

Jeśli masz problemy z budowaniem pełnego instalatora (Java14 i nowsze mogą generować błędy budowania dla izpack dotyczące pack200),
możesz zbudować pełny zip instalacyjny, który można rozpakować i uruchomić w miejscu:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Uruchom 'ant' bez argumentów, aby zobaczyć inne opcje budowania.

Aby utworzyć autonomiczny pakiet Debian dla Debian/Ubuntu bez zewnętrznych zależności Jetty/Tomcat:
```bash
ant buildDeb
```

Tworzy to autonomiczny pakiet `.deb`, który zawiera połączone biblioteki Jetty i Tomcat bez zewnętrznych zależności.


Aby zbudować AppImage dla Linux:
```bash
ant buildAppImage
```

Zobacz [tools/appimage/README.md](tools/appimage/README.md) aby uzyskać szczegóły.


Aby uzyskać więcej informacji o tym, jak uruchomić I2P w Docker, zobacz [docker/README.md](docker/README.md)


## Informacje kontaktowe

Potrzebujesz pomocy? Odwiedź kanał IRC #saltR w sieci IRC I2P

Raporty o błędach: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues lub https://github.com/I2PPlus/i2pplus/issues

## Licencje

I2P+ jest licencjonowany na podstawie AGPL w wersji 3.

W przypadku różnych subkomponentów zobacz: [README.md](docs/LICENSES.md)

## Zobacz również

### Dokumentacja

- [docs/README.md](docs/README.md) - Pełny indeks dokumentacji
- [docs/INSTALL.md](docs/INSTALL.md) - Instrukcja instalacji
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Instalacja bez GUI (tryb konsolowy)
- [docs/HACKING.md](docs/HACKING.md) - Przewodnik programisty i systemy budowania
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Układ drzewa źródłowego
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Zarządzanie blokadami sesji I2P za pomocą nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Licencje stron trzecich
- [docs/history.txt](docs/history.txt) - Pełny dziennik zmian

### Sub-projects

- [apps/README.md](apps/README.md) - Przegląd aplikacji
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplikacja książki adresowej
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Aplikacja GUI pulpitu
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API sterowania I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Klient BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplikacja tunelu I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Narzędzia generowania obrazów
- [apps/jetty/README.md](apps/jetty/README.md) - Serwer HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Biblioteka monitoringu JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Minimalna biblioteka strumieniowania
- [apps/pack200/README.md](apps/pack200/README.md) - Kompresja Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Skrypty proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Konsola routera
- [apps/sam/README.md](apps/sam/README.md) - Prosta anonimowa transmisja wiadomości
- [apps/streaming/README.md](apps/streaming/README.md) - Biblioteka strumieniowania
- [apps/susidns/README.md](apps/susidns/README.md) - Serwer DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Klient poczty I2P
- [apps/systray/README.md](apps/systray/README.md) - Aplikacja zasobnika systemowego
- [core/README.md](core/README.md) - Dokumentacja biblioteki core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Natywna biblioteka JNI do kryptografii (GMP)

### Misc

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Zarządzanie blokadami sesji I2P za pomocą nftables
- [installer/resources/README.md](installer/resources/README.md) - Zasoby instalatora
- [tools/scripts/README.md](tools/scripts/README.md) - Skrypty narzędziowe do programowania i administrowania
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Skrypty walidacji i testowania



- [docker/README.md](docker/README.md) - Uruchamianie I2P+ w Docker

