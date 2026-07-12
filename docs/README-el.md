[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Αυτός είναι ο πηγαίος κώδικας του soft-fork της Java υλοποίησης του I2P.

Τελευταία έκδοση: https://i2pplus.github.io/

## Εγκατάσταση

Δείτε τις οδηγίες εγκατάστασης στο [INSTALL.md](docs/INSTALL.md) ή στο https://i2pplus.github.io/

### Σημείωση για τον εγκαταστάτη Windows

Με Java > 1.8 ή εναλλακτικές διανομές (AdoptOpenJDK κ.λπ.), το exe του εγκαταστάτη μπορεί να αποτύχει με σφάλματα "Java not found" ή "invalid/corrupt". Λύση: εξάγετε το install.jar από το exe και εκτελέστε `java -jar install.jar` από τη γραμμή εντολών.

## Τεκμηρίωση

https://geti2p.net/how

Συχνές ερωτήσεις: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
ή εκτελέστε 'ant javadoc' και μετά ξεκινήστε από build/javadoc/index.html

## Πώς να συνεισφέρετε / Να χακάρετε το I2P

Παρακαλώ ελέγξτε το [HACKING.md](docs/HACKING.md) και άλλα έγγραφα στον κατάλογο docs.

## Δημιουργία πακέτων από τον πηγαίο κώδικα

Για να αποκτήσετε το branch ανάπτυξης από τον έλεγχο πηγαίου κώδικα: https://github.com/I2PPlus/i2pplus/

### Προϋποθέσεις

- Java SDK (κατά προτίμηση Oracle/Sun ή OpenJDK) 1.8.0 ή νεότερο
  - Ορισμένα υποσυστήματα για embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 ή νεότερο
- Εγκατεστημένα εργαλεία xgettext, msgfmt, και msgmerge από το πακέτο GNU gettext
  http://www.gnu.org/software/gettext/
- Το περιβάλλον build πρέπει να χρησιμοποιεί UTF-8 locale.
- Για δημιουργία πακέτων Debian: πακέτα `dpkg-deb` και `fakeroot` (μέσω του διαχειριστή πακέτων σας)

### Διαδικασία build με Ant

Σε συστήματα x86 εκτελέστε το ακόλουθο (θα γίνει build χρησιμοποιώντας IzPack4):

    ant pkg

Σε μη-x86, χρησιμοποιήστε ένα από τα ακόλουθα αντί:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Αν θέλετε να κάνετε build με IzPack5, κατεβάστε από: http://izpack.org/downloads/ και μετά
εγκαταστήστε το, και μετά εκτελέστε την ακόλουθα εντολή(ες):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Για να δημιουργήσετε μια μη υπογεγραμμένη ενημέρωση για μια υπάρχουσα εγκατάσταση, εκτελέστε:

    ant updater

Αν αντιμετωπίζετε προβλήματα με τη δημιουργία ενός πλήρους installer (Java14 και νεότερα μπορεί να δημιουργήσουν σφάλματα build για izpack σχετικά με το pack200),
μπορείτε να δημιουργήσετε ένα πλήρες εγκατάστασης zip που μπορεί να εξαχθεί και να εκτελεστεί επιτόπου:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Εκτελέστε 'ant' χωρίς ορίσματα για να δείτε άλλες επιλογές build.



Για περισσότερες πληροφορίες σχετικά με την εκτέλεση του I2P σε Docker, δείτε το [docker/README.md](docker/README.md)





## Στοιχεία επικοινωνίας
Χρειάζεστε βοήθεια; Επισκεφθείτε το κανάλι IRC #saltR στο δίκτυο I2P IRC

## Στοιχεία επικοινωνίας


## Στοιχεία επικοινωνίας
Αναφορές σφαλμάτων: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues ή https://github.com/I2PPlus/i2pplus/issues

## Στοιχεία επικοινωνίας


## Στοιχεία επικοινωνίας


## Στοιχεία επικοινωνίας
## Άδειες
Το I2P+ έχει άδεια χρήσης υπό την AGPL v.3.

## Στοιχεία επικοινωνίας
## Άδειες


## Στοιχεία επικοινωνίας
## Άδειες
Για τις άδειες των διάφορων υποσυστατικών, δείτε: [README.md](docs/LICENSES.md)

## Στοιχεία επικοινωνίας
## Άδειες


## Στοιχεία επικοινωνίας
## Άδειες


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/README.md](docs/README.md) - Πλήρες ευρετήριο τεκμηρίωσης

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/INSTALL.md](docs/INSTALL.md) - Οδηγός εγκατάστασης

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Εγκατάσταση χωρίς GUI (λειτουργία κονσόλας)

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/HACKING.md](docs/HACKING.md) - Οδηγός προγραμματιστή και συστήματα build

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Διάταξη δέντρου πηγαίου κώδικα
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Εντοπισμός σφαλμάτων σε χρόνο εκτέλεσης με JDWP και άλλα εργαλεία

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Διαχείριση απαγορεύσεων συνεδρίας I2P με nftables

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system
- [docs/LICENSES.md](docs/LICENSES.md) - Άδειες τρίτων μερών

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/history.txt](docs/history.txt) - Πλήρες αρχείο αλλαγών

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
### Υπο-έργα

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/README.md](apps/README.md) - Επισκόπηση εφαρμογών

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/addressbook/README.md](apps/addressbook/README.md) - Εφαρμογή βιβλίου διευθύνσεων

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Εφαρμογή Desktop GUI

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API ελέγχου I2P

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Πελάτης BitTorrent I2PSnark

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Εφαρμογή τούνελ I2P

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/imagegen/README.md](apps/imagegen/README.md) - Εργαλεία δημιουργίας εικόνων

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/jetty/README.md](apps/jetty/README.md) - HTTP διακομιστής Jetty

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/jrobin/README.md](apps/jrobin/README.md) - Βιβλιοθήκη παρακολούθησης JRobin

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Ελάχιστη βιβλιοθήκη streaming

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/pack200/README.md](apps/pack200/README.md) - Συμπίεση Pack200

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy scripts

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Κονσόλα δρομολογητή

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/sam/README.md](apps/sam/README.md) - Απλή Ανώνυμη Ανταλλαγή Μηνυμάτων

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/streaming/README.md](apps/streaming/README.md) - Βιβλιοθήκη streaming

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/susidns/README.md](apps/susidns/README.md) - DNS διακομιστής

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/susimail/README.md](apps/susimail/README.md) - Πελάτης email I2P

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [apps/systray/README.md](apps/systray/README.md) - Εφαρμογή συστημικής θήκης

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [core/README.md](core/README.md) - Τεκμηρίωση βιβλιοθήκης core

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Native JNI βιβλιοθήκη για κρυπτογράφηση (GMP)

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
### Διάφορα

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Διαχείριση απαγορεύσεων συνεδρίας I2P με nftables

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [installer/resources/README.md](installer/resources/README.md) - Πόροι εγκατάστασης

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [tools/scripts/README.md](tools/scripts/README.md) - Βοηθητικά scripts για ανάπτυξη και διαχείριση

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Scripts επικύρωσης και δοκιμής

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση


## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
- [docker/README.md](docker/README.md) - Εκτέλεση I2P+ σε Docker

## Στοιχεία επικοινωνίας
## Άδειες
## Τεκμηρίωση
