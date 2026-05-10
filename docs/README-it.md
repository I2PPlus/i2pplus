[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Questo è il codice sorgente del soft-fork dell'implementazione Java di I2P.

Ultima release: https://i2pplus.github.io/

## Installazione

Vedere [INSTALL.md](INSTALL.md) o https://i2pplus.github.io/ per le istruzioni di installazione.

### Nota per l'installer di Windows

Con Java > 1.8 o distribuzioni alternative (AdoptOpenJDK, ecc.), l'exe dell'installer potrebbe non funzionare con errori "Java not found" o "invalid/corrupt". Soluzione alternativa: estrai install.jar dall'exe ed esegui `java -jar install.jar` dalla riga di comando.

## Documentazione

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
oppure esegui 'ant javadoc' e poi inizia da build/javadoc/index.html

## Come contribuire / Hack su I2P

Consulta [HACKING.md](docs/HACKING.md) e altri documenti nella directory docs.

## Creazione di pacchetti dal codice sorgente

Per ottenere il branch di sviluppo dal controllo sorgente: https://github.com/I2PPlus/i2pplus/

### Prerequisiti

- Java SDK (preferibilmente Oracle/Sun o OpenJDK) 1.8.0 o superiore
  - Alcuni sottosistemi per embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 o superiore
- Strumenti xgettext, msgfmt e msgmerge installati dal pacchetto GNU gettext
  http://www.gnu.org/software/gettext/
- L'ambiente di build deve usare una locale UTF-8.
- Per build di pacchetti Debian: pacchetti `dpkg-deb` e `fakeroot` (tramite il gestore pacchetti)

### Processo di build Ant

Sui sistemi x86 esegui il seguente (verrà buildato usando IzPack4):

    ant pkg

Sui non-x86, usa uno dei seguenti:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Se vuoi buildare con IzPack5, scarica da: http://izpack.org/downloads/ e poi
installalo, poi esegui i seguenti comandi:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Per buildare un update non firmato per un'installazione esistente, esegui:

    ant updater

Se hai problemi a buildare un installer completo (Java14 e successivi potrebbero generare errori di build per izpack relativi a pack200),
puoi buildare un file zip di installazione completo che può essere estratto e eseguito sul posto:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Esegui 'ant' senza argomenti per vedere altre opzioni di build.

Per creare un pacchetto Debian autonomo per Debian/Ubuntu senza dipendenze esterne Jetty/Tomcat:
```bash
ant buildDeb
```

Questo crea un pacchetto `.deb` autonomo che include le librerie Jetty e Tomcat incluse senza dipendenze esterne.


Per creare un AppImage per Linux:
```bash
ant buildAppImage
```

Vedi [tools/appimage/README.md](tools/appimage/README.md) per i dettagli.


Per maggiori informazioni su come eseguire I2P in Docker, vedi [docker/README.md](docker/README.md)


## Informazioni di contatto

Hai bisogno di aiuto? Visita il canale IRC #saltR sulla rete I2P IRC

Segnalazione bug: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues o https://github.com/I2PPlus/i2pplus/issues

## Licenze

I2P+ è rilasciato sotto licenza AGPL v.3.

Per le licenze dei vari sub-componenti, vedi: [README.md](docs/LICENSES.md)

## Vedi anche

### Documentazione
- [docs/README.md](docs/README.md) - Indice completo della documentazione
- [docs/INSTALL.md](docs/INSTALL.md) - Guida all'installazione
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Installazione headless (modalità console)
- [docs/HACKING.md](docs/HACKING.md) - Guida per sviluppatori e sistemi di build
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Layout dell'albero dei sorgenti
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestione divieti di sessione I2P con nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Licenze di terze parti
- [docs/history.txt](docs/history.txt) - Registro completo delle modifiche

### Sub-projects
- [apps/README.md](apps/README.md) - Panoramica delle applicazioni
- [apps/addressbook/README.md](apps/addressbook/README.md) - Applicazione rubrica
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Applicazione GUI desktop
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API di controllo I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Client BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Applicazione tunnel I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Strumenti generazione immagini
- [apps/jetty/README.md](apps/jetty/README.md) - Server HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Libreria monitoraggio JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Libreria streaming minima
- [apps/pack200/README.md](apps/pack200/README.md) - Compressione Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Script proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Console router
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Libreria streaming
- [apps/susidns/README.md](apps/susidns/README.md) - Server DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Client email I2P
- [apps/systray/README.md](apps/systray/README.md) - Applicazione system tray
- [core/README.md](core/README.md) - Documentazione libreria core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Libreria JNI nativa per crittografia (GMP)

### MISC
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestione divieti di sessione I2P con nftables
- [installer/resources/README.md](installer/resources/README.md) - Risorse del programma di installazione
- [tools/scripts/README.md](tools/scripts/README.md) - Script di utilità per sviluppo e amministrazione
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Script di validazione e test

- [docker/README.md](docker/README.md) - Esegui I2P+ in Docker

