[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Ini adalah kode sumber untuk soft-fork dari implementasi Java I2P.

Rilis terbaru: https://i2pplus.github.io/

## Instalasi

Lihat [INSTALL.md](INSTALL.md) atau https://i2pplus.github.io/ untuk instruksi instalasi.

### Catatan installer Windows

Dengan Java > 1.8 atau distribusi alternatif (AdoptOpenJDK, dll), exe installer mungkin gagal dengan kesalahan "Java not found" atau "invalid/corrupt". Solusi: ekstrak install.jar dari exe dan jalankan `java -jar install.jar` dari baris perintah.

## Dokumentasi

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
atau jalankan 'ant javadoc' kemudian mulai di build/javadoc/index.html

## Cara Berkontribusi / Mengembangkan I2P

Silakan periksa [HACKING.md](docs/HACKING.md) dan dokumen lain di direktori docs.

## Membangun Paket dari Sumber

Untuk mendapatkan cabang pengembangan dari kontrol sumber: https://github.com/I2PPlus/i2pplus/

### Prasyarat

- Java SDK (lebih disukai Oracle/Sun atau OpenJDK) 1.8.0 atau lebih tinggi
  - Subsistem tertentu untuk embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 atau lebih tinggi
- Alat xgettext, msgfmt, dan msgmerge terinstal dari paket GNU gettext
  http://www.gnu.org/software/gettext/
- Lingkungan build harus menggunakan locale UTF-8.
- Untuk build paket Debian: paket `dpkg-deb` dan `fakeroot` (melalui package manager Anda)

### Proses Build dengan Ant

Pada sistem x86 jalankan berikut ini (akan membangun menggunakan IzPack4):

    ant pkg

Pada non-x86, gunakan salah satu alternatif berikut:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Jika Anda ingin membangun dengan IzPack5, unduh dari: http://izpack.org/downloads/
lalu instal, lalu jalankan perintah berikut:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Untuk membangun update unsigned untuk instalasi yang sudah ada, jalankan:

    ant updater

Jika Anda mengalami masalah dalam membangun installer lengkap (Java14 dan yang lebih baru mungkin menghasilkan kesalahan build untuk izpack terkait pack200),
Anda dapat membuat zip instalasi lengkap yang dapat diekstrak dan dijalankan di tempat:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Jalankan 'ant' tanpa argumen untuk melihat opsi build lainnya.

Untuk membuat paket Debian mandiri untuk Debian/Ubuntu tanpa dependensi eksternal Jetty/Tomcat:
```bash
ant buildDeb
```

Ini membuat paket `.deb` mandiri yang menyertakan pustaka Jetty dan Tomcat yang dibundel tanpa dependensi eksternal.


Untuk membuat AppImage untuk Linux:
```bash
ant buildAppImage
```

Lihat [tools/appimage/README.md](tools/appimage/README.md) untuk detailnya.


Untuk informasi lebih lanjut tentang cara menjalankan I2P di Docker, lihat [docker/README.md](docker/README.md)


## Info Kontak

Butuh bantuan? Kunjungi channel IRC #saltR di jaringan IRC I2P

Laporan bug: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues atau https://github.com/I2PPlus/i2pplus/issues

## Lisensi

I2P+ dilisensikan di bawah AGPL v.3.

Untuk lisensi sub-komponen lainnya, lihat: [README.md](docs/LICENSES.md)

## Lihat Juga

### Dokumentasi

- [docs/README.md](docs/README.md) - Indeks dokumentasi lengkap
- [docs/INSTALL.md](docs/INSTALL.md) - Panduan instalasi
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Instalasi headless (mode konsol)
- [docs/HACKING.md](docs/HACKING.md) - Panduan pengembang dan sistem build
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Tata letak pohon sumber
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Debugging runtime dengan JDWP dan alat lainnya
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Mengelola pelarangan sesi I2P dengan nftables
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system
- [docs/LICENSES.md](docs/LICENSES.md) - Lisensi pihak ketiga
- [docs/history.txt](docs/history.txt) - Log perubahan lengkap

### Sub-proyek

- [apps/README.md](apps/README.md) - Ikhtisar aplikasi
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplikasi buku alamat
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Aplikasi GUI desktop
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API kontrol I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Klien BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplikasi terowongan I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Alat pembuatan gambar
- [apps/jetty/README.md](apps/jetty/README.md) - Server HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Pustaka pemantauan JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Pustaka streaming minimal
- [apps/pack200/README.md](apps/pack200/README.md) - Kompresi Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Skrip proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Konsol router
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Pustaka streaming
- [apps/susidns/README.md](apps/susidns/README.md) - Server DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Klien email I2P
- [apps/systray/README.md](apps/systray/README.md) - Aplikasi baki sistem
- [core/README.md](core/README.md) - Dokumentasi pustaka core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Pustaka JNI native untuk kriptografi (GMP)

### Lainnya

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Mengelola pelarangan sesi I2P dengan nftables
- [installer/resources/README.md](installer/resources/README.md) - Sumber daya installer
- [tools/scripts/README.md](tools/scripts/README.md) - Skrip utilitas untuk pengembangan dan administrasi
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Skrip validasi dan pengujian



- [docker/README.md](docker/README.md) - Menjalankan I2P+ di Docker

