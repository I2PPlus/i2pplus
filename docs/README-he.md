[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

זהו קוד המקור של הפריקה הרכה (soft-fork) של יישום ה-Java של I2P.

הגרסה האחרונה: https://i2pplus.github.io/

## התקנה

עיין ב-[INSTALL.md](INSTALL.md) או ב-https://i2pplus.github.io/ לקבלת הוראות התקנה.

### הערה לגבי תוכנית ההתקנה של Windows

עם Java > 1.8 או הפצות חלופיות (AdoptOpenJDK וכו'), קובץ ה-exe של תוכנית ההתקנה עלול להיכשל עם שגיאות "Java not found" או "invalid/corrupt". פתרון: חלץ את install.jar מה-exe והרץ `java -jar install.jar` משורת הפקודה.

## תיעוד

https://geti2p.net/how

שאלות נפוצות: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
או הרץ 'ant javadoc' ואז התחל ב-build/javadoc/index.html

## כיצד לתרום / לפרוץ ב-I2P

עיין ב-[HACKING.md](docs/HACKING.md) ובמסמכים הנוספים בתיקיית docs.

## בניית חבילות מקוד המקור

כדי לקבל את ענף הפיתוח מבקרת המקור: https://github.com/I2PPlus/i2pplus/

### דרישות מוקדמות

- Java SDK (רצוי Oracle/Sun או OpenJDK) 1.8.0 ומעלה
  - תת-מערכות מסוימות למשובצים (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 ומעלה
- התקן את הכלים xgettext, msgfmt, ו-msgmerge מחבילת GNU gettext
  http://www.gnu.org/software/gettext/
- סביבת הבנייה חייבת להשתמש במיקום UTF-8.
- לבניית חבילות Debian: חבילות `dpkg-deb` ו-`fakeroot` (דרך מנהל החבילות שלך)

### תהליך הבנייה עם Ant

במערכות x86 הרץ את הבא (זה יבנה באמצעות IzPack4):

    ant pkg

במערכות שאינן x86, השתמש באחת מהאפשרויות הבאות במקום:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

אם ברצונך לבנות עם IzPack5, הורד מ: http://izpack.org/downloads/ ואז
התקן אותו, ואז הרץ את הפקודה(ות) הבאות:

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

כדי לבנת עדכון לא חתום להתקנה קיימת, הרץ:

    ant updater

אם יש לך בעיות בבניית מתקין מלא (Java14 ומעלה עלולים ליצור שגיאות בנייה עבור izpack הקשורות ל-pack200),
תוכל לבנות zip התקנה מלא שניתן לחלץ ולהפעיל במקום:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

הרץ 'ant' ללא ארגומנטים כדי לראות אפשרויות בנייה נוספות.

כדי ליצור חבילת Debian עצמאית עבור Debian/Ubuntu ללא תלויות חיצוניות Jetty/Tomcat:
```bash
ant buildDeb
```

זה יוצר חבילת `.deb` עצמאית הכוללת את ספריות Jetty ו-Tomcat המקובצות ללא תלויות חיצוניות.


כדי לבנות AppImage עבור Linux:
```bash
ant buildAppImage
```

ראה [tools/appimage/README.md](tools/appimage/README.md) לפרטים.


למידע נוסף על הפעלת I2P ב-Docker, עיין ב-[docker/README.md](docker/README.md)





## פרטי קשר
צריך עזרה? בקר בערוץ IRC #saltR ברשת I2P IRC

## פרטי קשר


## פרטי קשר
דיווח על באגים: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues או https://github.com/I2PPlus/i2pplus/issues

## פרטי קשר


## פרטי קשר


## פרטי קשר
## רישיונות
I2P+ מורשית תחת AGPL v.3.

## פרטי קשר
## רישיונות


## פרטי קשר
## רישיונות
עבור רישיונות תת-רכיבים שונים, עיין ב: [README.md](docs/LICENSES.md)

## פרטי קשר
## רישיונות


## פרטי קשר
## רישיונות


## פרטי קשר
## רישיונות
## ראה גם
### תיעוד

## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם
- [docs/README.md](docs/README.md) - אינדקס תיעוד מלא

## פרטי קשר
## רישיונות
## ראה גם
- [docs/INSTALL.md](docs/INSTALL.md) - מדריך התקנה

## פרטי קשר
## רישיונות
## ראה גם
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - התקנה ללא ממשק גרפי (מצב קונסולה)

## פרטי קשר
## רישיונות
## ראה גם
- [docs/HACKING.md](docs/HACKING.md) - מדריך מפתחים ומערכות בנייה

## פרטי קשר
## רישיונות
## ראה גם
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - פריסת עץ המקור והיכן למצוא דברים
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - איפוס באגים בזמן אמת עם JDWP וכלי נוספים

## פרטי קשר
## רישיונות
## ראה גם
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - ניהול איסורי הפעלות I2P עם nftables

## פרטי קשר
## רישיונות
## ראה גם
- [docs/LICENSES.md](docs/LICENSES.md) - רישיונות צד שלישי

## פרטי קשר
## רישיונות
## ראה גם
- [docs/history.txt](docs/history.txt) - יומן שינויים מלא

## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם
### פרויקטים משנה

## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם
- [apps/README.md](apps/README.md) - סקירת יישומים

## פרטי קשר
## רישיונות
## ראה גם
- [apps/addressbook/README.md](apps/addressbook/README.md) - יישום ספר כתובות

## פרטי קשר
## רישיונות
## ראה גם
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - יישום ממשק משתמש

## פרטי קשר
## רישיונות
## ראה גם
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API בקרה I2P

## פרטי קשר
## רישיונות
## ראה גם
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - לקוח BitTorrent I2PSnark

## פרטי קשר
## רישיונות
## ראה גם
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - יישום מנהרת I2P

## פרטי קשר
## רישיונות
## ראה גם
- [apps/imagegen/README.md](apps/imagegen/README.md) - כלי יצירת תמונות

## פרטי קשר
## רישיונות
## ראה גם
- [apps/jetty/README.md](apps/jetty/README.md) - שרת HTTP Jetty

## פרטי קשר
## רישיונות
## ראה גם
- [apps/jrobin/README.md](apps/jrobin/README.md) - ספריית ניטור JRobin

## פרטי קשר
## רישיונות
## ראה גם
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - ספריית streaming מינימלית

## פרטי קשר
## רישיונות
## ראה גם
- [apps/pack200/README.md](apps/pack200/README.md) - דחיסת Pack200

## פרטי קשר
## רישיונות
## ראה גם
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - סקריפטים פרוקסי

## פרטי קשר
## רישיונות
## ראה גם
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - מסוף נתב

## פרטי קשר
## רישיונות
## ראה גם
- [apps/sam/README.md](apps/sam/README.md) - הודעות אנונימיות פשוטות

## פרטי קשר
## רישיונות
## ראה גם
- [apps/streaming/README.md](apps/streaming/README.md) - ספריית streaming

## פרטי קשר
## רישיונות
## ראה גם
- [apps/susidns/README.md](apps/susidns/README.md) - שרת DNS

## פרטי קשר
## רישיונות
## ראה גם
- [apps/susimail/README.md](apps/susimail/README.md) - לקוח דואר I2P

## פרטי קשר
## רישיונות
## ראה גם
- [apps/systray/README.md](apps/systray/README.md) - יישום מגש מערכת

## פרטי קשר
## רישיונות
## ראה גם
- [core/README.md](core/README.md) - תיעוד ספריית הליבה

## פרטי קשר
## רישיונות
## ראה גם
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - ספריית JNI מקורית להצפנה (GMP)

## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם
### שונות

## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - ניהול איסורי הפעלות I2P עם nftables

## פרטי קשר
## רישיונות
## ראה גם
- [installer/resources/README.md](installer/resources/README.md) - משאבי התקנה

## פרטי קשר
## רישיונות
## ראה גם
- [tools/scripts/README.md](tools/scripts/README.md) - סקריפטים לפיתוח וניהול

## פרטי קשר
## רישיונות
## ראה גם
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - סקריפטים לאימות ובדיקה

## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם


## פרטי קשר
## רישיונות
## ראה גם
- [docker/README.md](docker/README.md) - הפעלת I2P+ ב-Docker

## פרטי קשר
## רישיונות
## ראה גם
