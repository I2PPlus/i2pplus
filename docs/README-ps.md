[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="24" height="18" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/bn.svg" width="24" height="18" title="বাংলা">](README-bn.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="24" height="18" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="24" height="18" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="24" height="18" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="24" height="18" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="24" height="18" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="24" height="18" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="24" height="18" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="24" height="18" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="24" height="18" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="24" height="18" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="24" height="18" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="24" height="18" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="24" height="18" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="24" height="18" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="24" height="18" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="24" height="18" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="24" height="18" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="24" height="18" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="24" height="18" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="24" height="18" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="24" height="18" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="24" height="18" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="24" height="18" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="24" height="18" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="24" height="18" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tw.svg" width="24" height="18" title="繁體中文">](README-zh_TW.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/az.svg" width="24" height="18" title="Azerbaijani">](README-az.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/lang_ca.svg" width="24" height="18" title="Català">](README-ca.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/dk.svg" width="24" height="18" title="Dansk">](README-da.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ee.svg" width="24" height="18" title="Eesti">](README-et.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ph.svg" width="24" height="18" title="Filipino">](README-tl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fi.svg" width="24" height="18" title="Suomi">](README-fi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/no.svg" width="24" height="18" title="Norsk (bokmål)">](README-nb.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/af.svg" width="24" height="18" title="پښتو">](README-ps.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/sk.svg" width="24" height="18" title="Slovenčina">](README-sl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/se.svg" width="24" height="18" title="Svenska">](README-sv.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tz.svg" width="24" height="18" title="Kiswahili">](README-sw.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="24" height="18" title="English">](../README.md)

دا د I2P د Java پیاده‌سازۍ د سافت-فورک سورس کوډ دی.

وروستی انتشار: https://i2pplus.github.io/

## لګول

د نصبې لارښود لپاره [INSTALL.md](docs/INSTALL.md) یا https://i2pplus.github.io/ وګورئ.

### د Windows نصب‌کوونکي یادونه

که Java > 1.8 یا بله توزیعات (AdoptOpenJDK، ورته...) کاروي، نصب‌کوونکی exe "Java not found" یا "invalid/corrupt" ترو تلوتو ښکارېږي. ځنډه: له exe څخه install.jar ورشئ او له کماند لاین څخه `java -jar install.jar` چل کړئ.

## اسناد

https://geti2p.net/how

پوښتنې او ځوابونه: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
یا 'ant javadoc' چل کړئ او بیا په build/javadoc/index.html پیل کړئ

## څنګه مرسته ورکول / په I2P+ کې پروګرام کول

لطفاً [HACKING.md](docs/HACKING.md) او په docs پوښښ کې نورې اسناد وګورئ.

## له سورس څخه بستې جوړول

د پرمختیا شاخه له سورس کنټرول څخه وندولو لپاره: https://github.com/I2PPlus/i2pplus

### لازمې توکې

- Java SDK 1.8.0 یا لوړتر
- Apache Ant 1.9.8 یا لوړتر
- xgettext، msgfmt او msgmerge اوزارونه چې له GNU gettext بستې څخه خپل پیکجینډر له لارې یا http://www.gnu.org/software/gettext/ ولى شوي
- د جوړولو محیط باید UTF-8 locale وکاروي.
- د Debian بستو د جوړولو لپاره: `dpkg-deb` او `fakeroot` بستې (خپل پیکجینډر له لارې)
- د IzPack 5 د Windows exe لپاره (`ant installer5`, `ant installer5-windows`): Python 3.
  د IzPack `izpack2exe.py` Python 3 ده، خو د هغه shebang `python` وایي، نو جوړول
  ژباړونکی په خپله له مهال پورې کوي؛ د `izpack5.python` له لارې بدلولی شئ.

### د Ant جوړولو پروسه

په x86 سیستمونو کې لاندې توکه چل کړئ (دا IzPack4 سره جوړېږي):

    ant pkg

په non-x86 کې، پرته له دې یو له لاندې کاروئ:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

که ته غواړې IzPack5 سره جوړول، لاندې کمانډ(ونه) چل کړئ. دا کمانډونه د لومړي کارولو په مهال د IzPack 5 توزیع `installer/lib/izpack/5/` ته ښکته کوي — چې دې لپاره د نیټورک لاسرسی او ~95MB خالي ځای ته اړتیا لري — او پاتې خپله په اوتوماتیک توګه تازه ساتي:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

د نصب‌کوونکي جوړولو پرته، دا ترلاسه کړئ یا نوی کړئ:

    ant download-izpack5

د موجودې نصبې لپاره د نښه‌نشوې نوي ساز (unsigned update) جوړولو لپاره، چل کړئ:

    ant updater

یا Gradle سره:

    ./gradlew updater

که ته په بشپړ نصب‌کوونکي جوړولو کې ستونزې لرئ (Java14 او وروستۍ izpack لپاره د pack200 سمې تېرو تلوتو جوړوي)، ته یو بشپړ نصبې zip جوړ کولی شئ چې څخه ورکړېدلی او په هغه شوېځای کې چلېدلی وي:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

نورې جوړولو اختیارات وینېدلو لپاره 'ant' پرته له دلائل چل کړئ.

د Linux لپاره AppImage جوړولو لپاره:
```bash
ant buildAppImage
```

تفصیلاتو لپاره [tools/appimage/README.md](tools/appimage/README.md) وګورئ.

د Debian/Ubuntu لپاره پرته له بهیرني Jetty/Tomcat اینابونو، خپور Debian بسته جوړولو لپاره:
```bash
ant buildDeb
```

دا خپور `.deb` بسته جوړوي چې لګولې Jetty او Tomcat لایبررۍ منل کوي. یوازې OpenJDK runtime غواړي (له پیکجینډر له لارې پخپله نصبېږي).

Docker کې چلولو لپاره [docker/README.md](docker/README.md) وګورئ

## اړیکې

مرسته غواړئ؟ د I2P IRC شبکې په IRC کینال #saltR ته ورشئ

د باګونو راپورونه: https://github.com/I2PPlus/i2pplus/issues

## جوازونه

I2P+ د AGPL v.3 لاندې جواز لري.

د مختلف فرعي توکوو جوازونو لپاره وګورئ: [README.md](docs/LICENSES.md)

## همدارنګه وګورئ

### اسناد

- [docs/README.md](docs/README.md) - بشپړ د اسنادو فهرست
- [docs/INSTALL.md](docs/INSTALL.md) - د نصبې لارښود
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - بې سرې (کنسول حالت) نصب
- [docs/HACKING.md](docs/HACKING.md) - د پرمختیاکوونکي لارښود او جوړولو سیسټمونه
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - د سورس ډلې بیاځای او څه چېرتا وموندل کیږي
- [router/java/src/net/i2p/README.md](router/java/src/net/i2p/README.md) - د راوتر سورس ډلې عمومي پرت
- [core/java/src/net/i2p/README.md](core/java/src/net/i2p/README.md) - د بنسټ لایبررۍ سورس ډلې عمومي پرت
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - د JDWP او نورو اوزارونو سره د خنډ لیدل
- [docs/THEMING.md](docs/THEMING.md) - د کنسول او وب اپلیکېشنونو بڼه‌ونې سیستم
- [docs/LICENSES.md](docs/LICENSES.md) - د دریوې ټاکړو شرکتونو جوازونه
- [docs/history.txt](docs/history.txt) - بشپړ د بدلونونو لیک

### فرعي پروژې

- [apps/README.md](apps/README.md) - د اپلیکېشن عمومي پرت
- [apps/addressbook/README.md](apps/addressbook/README.md) - د پتې لیکونکي اپلیکېشن
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - د ډیسک‌ټاپ GUI اپلیکېشن
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P Control API
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark BitTorrent کلاینت
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P تونل اپلیکېشن
- [apps/imagegen/README.md](apps/imagegen/README.md) - د انځورونو جوړولو اوزارونه
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty HTTP یاور
- [apps/jrobin/README.md](apps/jrobin/README.md) - JRobin د پلټنې لایبررۍ
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - کمینه streaming لایبررۍ
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 فشردنه
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - پروکسی اسکریپتونه
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - د راوتر کنسول
- [apps/sam/README.md](apps/sam/README.md) - ساده نامرښتینېز پیغام‌وړونه (SAM)
- [apps/streaming/README.md](apps/streaming/README.md) - streaming لایبررۍ
- [apps/susidns/README.md](apps/susidns/README.md) - DNS یاور
- [apps/susimail/README.md](apps/susimail/README.md) - I2P بریښنالیک کلاینت
- [apps/systray/README.md](apps/systray/README.md) - د سیسټم‌ټرې اپلیکېشن
- [core/README.md](core/README.md) - د بنسټ لایبررۍ اسناد
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - د ټاکلي‌کولو لپاره ټاکړه JNI لایبررۍ (GMP)

### نورې

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - د nftables سره د I2P سیشن بنونو اداره کول
- [installer/resources/README.md](installer/resources/README.md) - لګولې د نصب‌کوونکي سرچینې
- [tools/scripts/README.md](tools/scripts/README.md) - د پرمختیا او ادارې لپاره کارګار اسکریپتونه
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - د سمه‌کړنو او ازمېښت اسکریپتونه
