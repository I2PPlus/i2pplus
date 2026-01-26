[![CodeQL](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml)
[![Java CI](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml)
[![I2P+ Update zip](https://i2pplus.github.io/download.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](https://i2pplus.github.io/i2psnarkdownload.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](https://i2pplus.github.io/javadocsdownload.svg)](https://i2pplus.github.io/javadoc.zip)

# I2P+

[English](README.md) | [Русский](README-ru.md) | [日本語](README-ja.md) | [中文](README-zh.md) | [हिन्दी](README-hi.md) | [བོད་ཡིག](README-bo.md) | [فارسی](README-fa.md)

این کد منبع نرم-فورک پیاده‌سازی جاوا I2P است.

آخرین نسخه: https://i2pplus.github.io/

## نصب

به INSTALL.txt یا https://i2pplus.github.io/ برای دستورالعمل‌های نصب مراجعه کنید.

## مستندات

https://geti2p.net/how

سوالات متداول: https://geti2p.net/faq

API: http://docs.i2p-projekt.de/javadoc/
یا دستور 'ant javadoc' را اجرا کنید سپس از build/javadoc/index.html شروع کنید

## چگونه مشارکت کنیم / کار کردن روی I2P

لطفاً [HACKING.md](docs/HACKING.md) و سایر اسناد در پوشه docs را بررسی کنید.

## ساخت بسته‌ها از منبع

برای گرفتن شاخه توسعه از کنترل منبع: https://gitlab.com/i2p.plus/I2P.Plus/

### پیش‌نیازها

- Java SDK (ترجیحاً Oracle/Sun یا OpenJDK) 1.8.0 یا بالاتر
  - سیستم‌عامل‌های غیر لینوکس و JVMها: به https://trac.i2p2.de/wiki/java مراجعه کنید
  - زیرسیستم‌های خاص برای سیستم‌های توکار (core, router, mstreaming, streaming, i2ptunnel)
    فقط به Java 1.6 نیاز دارند
- Apache Ant 1.9.8 یا بالاتر
- ابزارهای xgettext, msgfmt, و msgmerge نصب شده از بسته GNU gettext
  http://www.gnu.org/software/gettext/
- محیط ساخت باید از یک محلی UTF-8 استفاده کند.

### فرآیند ساخت Ant

روی سیستم‌های x86 دستورات زیر را اجرا کنید (این با استفاده از IzPack4 ساخته می‌شود):

    ant pkg

روی سیستم‌های غیر x86، به جای آن یکی از موارد زیر را استفاده کنید:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

اگر می‌خواهید با IzPack5 بسازید، از http://izpack.org/downloads/ دانلود کنید سپس
آن را نصب کنید و سپس دستور(ات) زیر را اجرا کنید:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

برای ساخت یک به‌روزرسانی امضا نشده برای نصب موجود، دستور زیر را اجرا کنید:

    ant updater

اگر در ساخت یک نصب‌کننده کامل مشکلی دارید (Java14 و بالاتر ممکن است خطاهای ساخت برای izpack مربوط به pack200 تولید کنند)،
می‌توانید یک فایل فشرده نصب کامل بسازید که قابل استخراج و اجرا در محل است:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

'ant' را بدون آرگومان اجرا کنید تا گزینه‌های ساخت دیگر را ببینید.

### Docker
برای اطلاعات بیشتر در مورد نحوه اجرای I2P در Docker، به [Docker.md](Docker.md) مراجعه کنید

## اطلاعات تماس

به کمک نیاز دارید؟ به کانال #saltR در شبکه IRC I2P مراجعه کنید

گزارش اشکال: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues یا https://github.com/I2PPlus/i2pplus/issues

## مجوزها

I2P+ تحت AGPL v.3 مجوز دارد.

برای مجوزهای اجزای فرعی مختلف، به: LICENSE.txt مراجعه کنید