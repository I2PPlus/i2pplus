[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

นี่คือซอร์สโค้ดของ soft-fork ของ Java implementation ของ I2P

รุ่นล่าสุด: https://i2pplus.github.io/

## การติดตั้ง

ดู [INSTALL.md](INSTALL.md) หรือ https://i2pplus.github.io/ สำหรับคำแนะนำการติดตั้ง

### หมายเหตุตัวติดตั้ง Windows

เมื่อใช้ Java > 1.8 หรือดิสโตรอื่นๆ (AdoptOpenJDK เป็นต้น) exe ของตัวติดตั้งอาจล้มเหลวด้วยข้อผิดพลาด "Java not found" หรือ "invalid/corrupt" วิธีแก้: แตก install.jar จาก exe แล้วรัน `java -jar install.jar` จากบรรทัดคำสั่ง

## เอกสาร

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
หรือรัน 'ant javadoc' แล้วเริ่มต้นที่ build/javadoc/index.html

## วิธีมีส่วนร่วม / Hack บน I2P

กรุณาดู [HACKING.md](docs/HACKING.md) และเอกสารอื่นๆ ในไดเรกเทอรี docs

## การสร้างแพ็กเกจจากซอร์สโค้ด

เพื่อรับ development branch จาก source control: https://github.com/I2PPlus/i2pplus/

### ข้อกำหนดเบื้องต้น

- Java SDK (preferably Oracle/Sun or OpenJDK) 1.8.0 หรือสูงกว่า
  - บาง subsystem สำหรับ embedded (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 หรือสูงกว่า
- ติดตั้งเครื่องมือ xgettext, msgfmt, และ msgmerge จาก GNU gettext package
  http://www.gnu.org/software/gettext/
- สภาพแวดล้อมการ build ต้องใช้ UTF-8 locale
- สำหรับการสร้างแพ็กเกจ Debian: แพ็กเกจ `dpkg-deb` และ `fakeroot` (ผ่านตัวจัดการแพ็กเกจของคุณ)

### กระบวนการ build ด้วย Ant

บนระบบ x86 ให้รันคำสั่งต่อไปนี้ (จะ build โดยใช้ IzPack4):

    ant pkg

บนระบบที่ไม่ใช่ x86 ให้ใช้คำสั่งใดคำสั่งหนึ่งต่อไปนี้แทน:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

ถ้าต้องการ build ด้วย IzPack5 ให้ดาวน์โหลดจาก: http://izpack.org/downloads/ แล้��ติดตั้ง จากนั้นรันคำสั่งต่อไปนี้:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

เพื่อสร้าง unsigned update สำหรับการติดตั้งที่มีอยู่ ให้รัน:

    ant updater

ถ้ามีปัญหาในการ build installer แบบเต็ม (Java14 ขึ้นไปอาจสร้าง build errors สำหรับ izpack เกี่ยวกับ pack200)
สามารถ build การติดตั้งแบบ zip ที่สามารถแตกไฟล์และรันในสถานที่ได้:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

รัน 'ant' โดยไม่มีอาร์กิวเมนต์เพื่อดูตัวเลือกการ build อื่นๆ

เพื่อสร้างแพ็กเกจ Debian อิสระสำหรับ Debian/Ubuntu โดยไม่มีการพึ่งพา Jetty/Tomcat ภายนอก:
```bash
ant buildDeb
```

สิ่งนี้สร้างแพ็กเกจ `.deb` อิสระที่รวมไลบรารี Jetty และ Tomcat เข้าด้วยกัน โดยไม่มีการพึ่งพาภายนอก


เพื่อสร้าง AppImage สำหรับ Linux:
```bash
ant buildAppImage
```

ดู [tools/appimage/README.md](tools/appimage/README.md) สำหรับรายละเอียด


สำหรับข้อมูลเพิ่มเติมเกี่ยวกับวิธีรัน I2P ใน Docker ให้ดู [docker/README.md](docker/README.md)





## ข้อมูลติดต่อ
ต้องการความช่วยเหลือ? เยี่ยมชมช่อง IRC #saltR บนเครือข่าย I2P IRC

## ข้อมูลติดต่อ


## ข้อมูลติดต่อ
รายงานข้อผิดพลาด: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues หรือ https://github.com/I2PPlus/i2pplus/issues

## ข้อมูลติดต่อ


## ข้อมูลติดต่อ


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
I2P+ ได้รับสิทธิ์อนุญาตภายใต้ AGPL v.3

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
สำหรับสิทธิ์อนุญาตของ sub-component ต่างๆ ดู: [README.md](docs/LICENSES.md)

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
### เอกสาร

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/README.md](docs/README.md) - ดัชนีเอกสารฉบับเต็ม

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/INSTALL.md](docs/INSTALL.md) - คู่มือการติดตั้ง

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - การติดตั้งแบบ headless (console mode)

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/HACKING.md](docs/HACKING.md) - คู่มือนักพัฒนาและระบบ build

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - โครงสร้างต้นไม้ซอร์สโค้ดและที่ที่จะหาสิ่งต่างๆ

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - การจัดการ I2P session bans ด้วย nftables

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/LICENSES.md](docs/LICENSES.md) - สิทธิ์อนุญาตของบุคคลที่สาม

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/history.txt](docs/history.txt) - บันทึกการเปลี่ยนแปลงฉบับเต็ม

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
### โปรเจกต์ย่อย

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/README.md](apps/README.md) - ภาพรวมแอปพลิเคชัน

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/addressbook/README.md](apps/addressbook/README.md) - แอปพลิเคชันสมุดที่อยู่

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - แอปพลิเคชัน Desktop GUI

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - คลient BitTorrent I2PSnark

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - แอปพลิเคชันอุโมงค์ I2P

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/imagegen/README.md](apps/imagegen/README.md) - เครื่องมือสร้างภาพ

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP server

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/jrobin/README.md](apps/jrobin/README.md) - คลังการตรวจสอบ JRobin

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - คลัง streaming ขั้นต่ำ

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/pack200/README.md](apps/pack200/README.md) - การบีบอัด Pack200

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Proxy scripts

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Router console

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/streaming/README.md](apps/streaming/README.md) - คลัง streaming

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/susidns/README.md](apps/susidns/README.md) - DNS server

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/susimail/README.md](apps/susimail/README.md) - คลient email I2P

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [apps/systray/README.md](apps/systray/README.md) - แอปพลิเคชัน system tray

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [core/README.md](core/README.md) - เอกสารคลัง core

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - คลัง JNI เนทีฟสำหรับการเข้ารหัส (GMP)

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
### เบ็ดเตล็ด

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - การจัดการห้ามเซสชัน I2P ด้วย nftables

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [installer/resources/README.md](installer/resources/README.md) - ทรัพยากร installer

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [tools/scripts/README.md](tools/scripts/README.md) - สคริปต์ยูทิลิตี้สำหรับการพัฒนาและการจัดการ

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - สคริปต์ตรวจสอบและทดสอบ

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม


## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
- [docker/README.md](docker/README.md) - รั��� I2P+ ใน Docker

## ข้อมูลติดต่อ
## สิทธิ์อนุญาต
## ดูเพิ่มเติม
