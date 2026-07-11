[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Acesta este codul sursă pentru fork-ul soft al implementării Java a I2P.

Ultima versiune: https://i2pplus.github.io/

## Instalare

Consulta [INSTALL.md](INSTALL.md) sau https://i2pplus.github.io/ pentru instrucțiuni de instalare.

### Notă privind installerul Windows

Cu Java > 1.8 sau distribuții alternative (AdoptOpenJDK etc.), exe-ul installerului poate eșua cu erori "Java not found" sau "invalid/corrupt". Soluție alternativă: extrageți install.jar din exe și rulați `java -jar install.jar` din linia de comandă.

## Documentație

https://geti2p.net/how

Întrebări frecvente: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
sau rulați 'ant javadoc' și apoi începeți de la build/javadoc/index.html

## Cum să contribuiți / Hacker pe I2P

Vă rugăm să consultați [HACKING.md](docs/HACKING.md) și alte documente din directorul docs.

## Construirea pachetelor din codul sursă

Pentru a obține branch-ul de dezvoltare din controlul sursă: https://github.com/I2PPlus/i2pplus/

### Cerințe prealabile

- Java SDK (de preferință Oracle/Sun sau OpenJDK) 1.8.0 sau mai mare
  - Anumite sub sisteme pentru embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 sau mai mare
- Instrumente xgettext, msgfmt, și msgmerge instalate din pachetul GNU gettext
  http://www.gnu.org/software/gettext/
- Mediul de build trebuie să folosească o locație UTF-8.
- Pentru construirea pachetelor Debian: pachetele `dpkg-deb` și `fakeroot` (prin managerul de pachete)

### Procesul de build Ant

Pe sistemele x86 rulați următorul (va construi folosind IzPack4):

    ant pkg

Pe sistemele non-x86, folosiți una dintre următoarele în schimb:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Dacă doriți să construiți cu IzPack5, descărcați de la: http://izpack.org/downloads/ și apoi
instalați-l, apoi rulați următoarea(e) comandă(e):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Pentru a construi o actualizare nesemnată pentru o instalare existentă, rulați:

    ant updater

Dacă aveți probleme la construirea unui installer complet (Java14 și versiunile ulterioare pot genera erori de build pentru izpack legate de pack200),
puteți construi un zip de instalare complet care poate fi extras și executat pe loc:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Rulați 'ant' fără argumente pentru a vedea alte opțiuni de build.

Pentru a crea un pachet Debian autonom pentru Debian/Ubuntu fără dependențe externe Jetty/Tomcat:
```bash
ant buildDeb
```

Aceasta creează un pachet `.deb` autonom care include bibliotecile Jetty și Tomcat grupate, fără dependențe externe.


Pentru a construi un AppImage pentru Linux:
```bash
ant buildAppImage
```

Consultaţi [tools/appimage/README.md](tools/appimage/README.md) pentru detalii.


Pentru mai multe informații despre cum să rulați I2P în Docker, consultați [docker/README.md](docker/README.md)


## Informații de contact

Aveți nevoie de ajutor? Vizitați canalul IRC #saltR pe rețeaua I2P IRC

Rapoarte de erori: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues sau https://github.com/I2PPlus/i2pplus/issues

## Licențe

I2P+ este licențiat sub AGPL v.3.

Pentru licențele diferitelor sub-componente, consultați: [README.md](docs/LICENSES.md)

## Vezi și

### Documentație

- [docs/README.md](docs/README.md) - Index complet documentație
- [docs/INSTALL.md](docs/INSTALL.md) - Ghid de instalare
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Instalare headless (mod consolă)
- [docs/HACKING.md](docs/HACKING.md) - Ghid pentru dezvoltatori și sisteme de build
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Structura arborelui sursă și unde să găsiți lucrurile
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Depanare în timpul rulării cu JDWP și alte instrumente
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestionarea interdicțiilor de sesiune I2P cu nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Licențe terțe părți
- [docs/history.txt](docs/history.txt) - Jurnal complet de modificări

### Sub-proiecte

- [apps/README.md](apps/README.md) - Prezentare generală aplicații
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplicație agendă
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Aplicație GUI desktop
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API control I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Client BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplicație tunnel I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Instrumente generare imagini
- [apps/jetty/README.md](apps/jetty/README.md) - Server HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Librărie monitorizare JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Librărie streaming minimă
- [apps/pack200/README.md](apps/pack200/README.md) - Compresie Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Scripturi proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Consolă router
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Librărie streaming
- [apps/susidns/README.md](apps/susidns/README.md) - Server DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Client email I2P
- [apps/systray/README.md](apps/systray/README.md) - Aplicație bară de sistem
- [core/README.md](core/README.md) - Documentație librărie core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Librărie JNI nativă pentru criptografie (GMP)

### Diverse

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestionare interdicții sesiuni I2P cu nftables
- [installer/resources/README.md](installer/resources/README.md) - Resurse installer
- [tools/scripts/README.md](tools/scripts/README.md) - Scripturi utilitare pentru dezvoltare și administrare
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Scripturi de validare și testare



- [docker/README.md](docker/README.md) - Rulare I2P+ în Docker

