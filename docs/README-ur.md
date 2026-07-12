[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

یہ I2P کے جاوا نفاذ کا سافٹ فورک سورس کوڈ ہے۔

تازہ ترین ریلیز: https://i2pplus.github.io/

## انسٹالیشن

انسٹالیشن ہدایات کے لیے [INSTALL.md](docs/INSTALL.md) یا https://i2pplus.github.io/ دیکھیں۔

### ونڈوز انسٹالر نوٹ

Java > 1.8 یا متبادل تقسیمیں (AdoptOpenJDK وغیرہ) کے ساتھ، انسٹالر exe "Java not found" یا "invalid/corrupt" غلطیوں کے ساتھ ناکام ہو سکتا ہے۔ حل: exe سے install.jar نکالیں اور کمانڈ لائن سے `java -jar install.jar` چلائیں۔

## دستاویز

https://geti2p.net/how

/faq

اے پی آئی: https://i2pplus.github.io/javadoc/
یا 'ant javadoc' چلایں پھر build/javadoc/index.html سے شروع کریں۔

## حصہ لینے کا طریقہ / I2P پر ہیک کرنا

براہ کرم [HACKING.md](docs/HACKING.md) اور docs ڈائریکٹری میں دیگر دستاویزات دیکھیں۔

## سورس کوڈ سے پیکجز بنانا

سورس کنٹرول سے ڈویلپمنٹ برانچ حاصل کرنے کے لیے: https://github.com/I2PPlus/i2pplus/

### تقاضے

- جاوا ایس ڈی کے (بہترینOracle/Sun یا OpenJDK) 1.8.0 یا اس سے زیادہ
- اپاچی اینٹ 1.9.8 یا اس سے زیادہ
- GNU gettext پیکج سے نصب xgettext, msgfmt, اور msgmerge ٹولز
  http://www.gnu.org/software/gettext/
- بلڈ اینوائرمنٹ کو UTF-8 لوکیل کا usage کرنا چاہیے۔
- Debian پیکیج بنانے کے لیے: `dpkg-deb` اور `fakeroot` پیکیجز (آپ کے پیکیج مینیجر کے ذریعے)

### اینٹ بلڈ پروسیس

x86 سسٹم پر درج ذیل چلایں (یہ IzPack4 کا استعمال کرکے بلڈ کرے گا):

    ant pkg

غیر x86 پر، اس کے بجائے درج ذیل میں سے ایک کا استعمال کریں:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

اگر آپ IzPack5 کے ساتھ بلڈ کرنا چاہتے ہیں، اس سے ڈاؤن لوڈ کریں: http://izpack.org/downloads/ پھر انسٹال کریں، اور پھر درج ذیل کمانڈ(ز) چلایں:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

موجودہ انسٹالیشن کے لیے غیر سائن اپڈیٹ بنانے کے لیے، چلایں:

    ant updater

اگر آپ کو پورے انسٹالر کو بلڈ کرنے میں مسائل ہیں (Java14 اور اس کے بعد izpack سے متعلقہ pack200 کے باعث بلڈ ایررز ہو سکتے ہیں)،
آپ ایک پوری انسٹالیشن zip بناسکتے ہیں جو نکالی جا سکتی ہے اور原地 پر چلایا جا سکتا ہے:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

دوسرے بلڈ اختیارات دیکھنے کے لیے 'ant' کو بغیر دلائل کے چلایں۔

Debian/Ubuntu کے لیے بغير externo Jetty/Tomcat انحصار کے ایک آزاد Debian پیکیج بنانے کے لیے:
```bash
ant buildDeb
```

یہ ایک آزاد `.deb` پیکیج بناتا ہے جو جیٹی اور ٹومکیٹ لائبریریوں کو بغير کسی بیرونی انحصار کے گروپ میں شامل کرتا ہے


Linux کے لیے AppImage بنانے کے لیے:
```bash
ant buildAppImage
```

تفصیلات کے لیے [tools/appimage/README.md](tools/appimage/README.md) دیکھیں۔


I2P کو ڈاکر میں چلانے کے بارے میں مزید معلومات کے لیے [docker/README.md](docker/README.md) دیکھیں

## رابطہ کی معلومات

مدد کی ضرورت ہے؟ I2P IRC نیٹ ورک پر IRC چینل #saltR پر جائیں

بگ رپورٹس: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues or https://github.com/I2PPlus/i2pplus/issues

## لائسنسز

I2P+ کو AGPL v.3 کے تحت لائسنس دیا گیا ہے۔

variousسابقہ اجزا کے لائسنسز کے لیے دیکھیں: [README.md](docs/LICENSES.md)

## یہ بھی دیکھیں

### دستاویزات

- [docs/README.md](docs/README.md) - مکمل دستاویزات انڈیکس
- [docs/INSTALL.md](docs/INSTALL.md) - انسٹالیشن گائیڈ
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - ہیڈلیس (کنسول موڈ) انسٹالیشن
- [docs/HACKING.md](docs/HACKING.md) - ڈیولپر گائیڈ اور بلڈ سسٹمز
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - سورس ٹری لے آؤٹ اور کہاں چیزیں تلاش کرنی ہیں
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - JDWP اور دیگر ٹولز کے ساتھ رن ٹائم ڈیبگنگ
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - nftables کے ساتھ I2P سیشن پابندیاں کا انتظام
- [docs/history.txt](docs/history.txt) - مکمل چینج لاگ

### سب پروجیکٹ ریڈمیز

- [apps/addressbook/README.md](apps/addressbook/README.md) - ایڈریس بک ایپلی کیشن
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - ڈیسکٹاپ گوئی ایپلی کیشن
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - I2P کنٹرول اے پی آئی
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - I2PSnark بٹ ٹورنٹ کلائنٹ
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - I2P ٹنل ایپلی کیشن
- [apps/imagegen/README.md](apps/imagegen/README.md) - امیج جنریشن ٹولز
- [apps/jetty/README.md](apps/jetty/README.md) - Jetty ایچ ٹی پی سرور
- [apps/jrobin/README.md](apps/jrobin/README.md) - جی روبن مانیٹرنگ لائبریری
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - منیمل اسٹریمنگ لائبریری
- [apps/pack200/README.md](apps/pack200/README.md) - Pack200 کمپریشن
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - پراکسی اسکرپٹس
- [apps/README.md](apps/README.md) - ایپلی کیشن اوور ویو
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - روٹر کنسول
- [apps/sam/README.md](apps/sam/README.md) - سیمپل اینینیمس میسیجنگ
- [apps/streaming/README.md](apps/streaming/README.md) - اسٹریمنگ لائبریری
- [apps/susidns/README.md](apps/susidns/README.md) - ڈی این ایس سرور
- [apps/susimail/README.md](apps/susimail/README.md) - I2P ای میل کلائنٹ
- [apps/systray/README.md](apps/systray/README.md) - سسٹرے ایپلی کیشن
- [core/c/jbigi/README.md](core/c/jbigi/README.md) - نیٹیو بگ انٹیجر لائبریری (GMP)
- [core/c/jcpuid/README.md](core/c/jcpuid/README.md) - سی پی یو ڈیٹیکشن نیٹیو لائبریری
- [core/README.md](core/README.md) - کور لائبریری دستاویزات
- [docker/README.md](docker/README.md) - ڈاکر میں I2P+ چلانا
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system
- [docs/LICENSES.md](docs/LICENSES.md) - تھرڈ پارٹ�� لائسنسز
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - کرپٹوگرافی (GMP) کے لیے نیٹیو جے این آئی لائبریری
- [installer/resources/README.md](installer/resources/README.md) - بنڈلڈ انسٹالر ریسورسز
- [scripts/README.md](scripts/README.md) - ڈویلپمنٹ اور ایڈمنسٹریشن کے لیے یوٹیلیٹی اسکرپٹس
- [scripts/tests/README.md](scripts/tests/README.md) - ویلیڈیشن اور ٹیسٹنگ اسکرپٹس