[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

هذا هو الكود المصدري للفرع الناعم (soft-fork) للتنفيذ بلغة Java لـ I2P.

أحدث إصدار: https://i2pplus.github.io/

## التثبيت

راجع [INSTALL.md](INSTALL.md) أو https://i2pplus.github.io/ للحصول على تعليمات التثبيت.

### ملاحظة حول مثبت Windows

عند استخدام Java > 1.8 أو التوزيعات البديلة (AdoptOpenJDK، وما إلى ذلك)، قد يفشل ملف exe الخاص بالمثبت出现خطأ "Java not found" أو "invalid/corrupt". الحل البديل: استخرج install.jar من exe وقم بتشغيل `java -jar install.jar` من سطر الأوامر.

## التوثيق

https://geti2p.net/how

الأسئلة الشائعة: https://geti2p.net/faq

واجهة برمجة التطبيقات: https://i2pplus.github.io/javadoc/
أو قم بتشغيل 'ant javadoc' ثم ابدأ بـ build/javadoc/index.html

## كيفية المساهمة / البرمجة على I2P

يرجى مراجعة [HACKING.md](docs/HACKING.md) والمستندات الأخرى في دليل المستندات.

## بناء الحزم من المصدر

للحصول على فرع التطوير من نظام التحكم في الكود المصدري: https://github.com/I2PPlus/i2pplus/

### المتطلبات الأساسية

- مجموعة تطوير Java (يفضل Oracle/Sun أو OpenJDK) 1.8.0 أو أعلى
  - أنظمة التشغيل غير Linux وآلات Java الافتراضية
  - بعض الأنظمة الفرعية المدمجة (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 أو أعلى
- يجب تثبيت أدوات xgettext و msgfmt و msgmerge من حزمة GNU gettext
  http://www.gnu.org/software/gettext/
- يجب أن يستخدم بيئة البناء موقع UTF-8.
- لبناء حزم Debian: حزم `dpkg-deb` و `fakeroot` (عبر مدير الحزم الخاص بك)

### عملية البناء باستخدام Ant

على أنظمة x86 قم بتشغيل ما يلي (سيتم البناء باستخدام IzPack4):

    ant pkg

على الأنظمة غير x86، استخدم أحد البدائل التالية:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

إذا كنت تريد البناء باستخدام IzPack5، قم بتنزيله من: http://izpack.org/downloads/
ثم قم بتثبيته، ثم قم بتشغيل الأمر (الأوامر) التالية:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

لبناء تحديث غير موقع لتثبيت موجود، قم بتشغيل:

    ant updater

إذا واجهت مشاكل في بناء المثبت الكامل (Java14 والإصدارات الأحدث قد تُنشئ أخطاء في البناء لـ izpack المتعلقة بـ pack200)،
يمكنك بناء ملف zip للتثبيت الكامل والذي يمكن استخراجه وتشغيله في مكانه:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

قم بتشغيل 'ant' بدون وسيطات لرؤية خيارات البناء الأخرى.

لإنشاء حزمة Debian مستقلة لـ Debian/Ubuntu بدون تبعيات Jetty/Tomcat الخارجية:
```bash
ant buildDeb
```

يؤدي هذا إلى إنشاء حزمة `.deb` مستقلة تتضمن مكتبات Jetty و Tomcat المجمعة بدون تبعيات خارجية.


لإنشاء AppImage لنظام Linux:
```bash
ant buildAppImage
```

انظر [tools/appimage/README.md](tools/appimage/README.md) للتفاصيل.


لمزيد من المعلومات حول كيفية تشغيل I2P في Docker، انظر [docker/README.md](docker/README.md)





## معلومات الاتصال
تحتاج إلى مساعدة؟ قم بزيارة قناة IRC #saltR على شبكة I2P IRC

## معلومات الاتصال


## معلومات الاتصال
تقارير الأخطاء: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues أو https://github.com/I2PPlus/i2pplus/issues

## معلومات الاتصال


## معلومات الاتصال


## معلومات الاتصال
## التراخيص
مرخص I2P+ تحت AGPL v.3.

## معلومات الاتصال
## التراخيص


## معلومات الاتصال
## التراخيص
للتراخيص الفرعية للمكونات المختلفة، انظر: [README.md](docs/LICENSES.md)

## معلومات الاتصال
## التراخيص


## معلومات الاتصال
## التراخيص


## معلومات الاتصال
## التراخيص
## انظر أيضًا
### التوثيق

## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/README.md](docs/README.md) - فهرس التوثيق الكامل

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/INSTALL.md](docs/INSTALL.md) - دليل التثبيت

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - التثبيت بدون واجهة رسومية (وضع وحدة التحكم)

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/HACKING.md](docs/HACKING.md) - دليل المطور وأنظمة البناء

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - تخطيط شجرة المصدر وأين تجد الأشياء

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - إدارة حظر جلسات I2P باستخدام nftables

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/LICENSES.md](docs/LICENSES.md) - تراخيص الأطراف الثالثة

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/history.txt](docs/history.txt) - سجل التغييرات الكامل

## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا
### Sub-projects

## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/README.md](apps/README.md) - نظرة عامة على التطبيقات

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/addressbook/README.md](apps/addressbook/README.md) - تطبيق دفتر العناوين

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - تطبيق واجهة المستخدم الرسومية للسطح المكتب

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - واجهة برمجة تطبيقات تحكم I2P

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - عميل BitTorrent I2PSnark

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - تطبيق نفق I2P

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/imagegen/README.md](apps/imagegen/README.md) - أدوات إنشاء الصور

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/jetty/README.md](apps/jetty/README.md) - خادم HTTP Jetty

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/jrobin/README.md](apps/jrobin/README.md) - مكتبة المراقبة JRobin

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - مكتبة البث المصغرة

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/pack200/README.md](apps/pack200/README.md) - ضغط Pack200

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - نصوص الوكيل

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - وحدة تحكم الموجه

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/sam/README.md](apps/sam/README.md) - المراسلة المجهولة البسيطة

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/streaming/README.md](apps/streaming/README.md) - مكتبة البث

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/susidns/README.md](apps/susidns/README.md) - خادم DNS

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/susimail/README.md](apps/susimail/README.md) - عميل البريد الإلكتروني I2P

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [apps/systray/README.md](apps/systray/README.md) - تطبيق علبة النظام

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [core/README.md](core/README.md) - توثيق المكتبة الأساسية

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - مكتبة JNI الأصلية للتشفير (GMP)

## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا
### MISC

## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - إدارة حظر جلسات I2P باستخدام nftables

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [installer/resources/README.md](installer/resources/README.md) - موارد المثبت المجمعة

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [tools/scripts/README.md](tools/scripts/README.md) - نصوص مساعدة للتطوير والإدارة

## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - نصوص التحقق والاختبار

## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا


## معلومات الاتصال
## التراخيص
## انظر أيضًا
- [docker/README.md](docker/README.md) - تشغيل I2P+ في Docker

## معلومات الاتصال
## التراخيص
## انظر أيضًا
