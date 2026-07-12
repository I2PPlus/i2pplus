[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Це вихідний код програмного форку Java-реалізації I2P.

Останній випуск: https://i2pplus.github.io/

## Встановлення

Див. [INSTALL.md](INSTALL.md) або https://i2pplus.github.io/ для інструкцій зі встановлення.

### Примітка щодо інсталятора Windows

Якщо використовується Java > 1.8 або альтернативні дистрибутиви (AdoptOpenJDK тощо), exe-файл інсталятора може не працювати з помилками "Java not found" або "invalid/corrupt". Обхідний шлях: витягніть install.jar з exe та запустіть `java -jar install.jar` з командного рядка.

## Документація

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
або запустіть 'ant javadoc', а потім відкрийте build/javadoc/index.html

## Як долучитися / Розробляти I2P

Будь ласка, перегляньте [HACKING.md](docs/HACKING.md) та інші документи в директорії docs.

## Збірка пакетів з вихідного коду

Для отримання гілки розробки з системи контролю версій: https://github.com/I2PPlus/i2pplus/

### Попередні вимоги

- Java SDK (бажано Oracle/Sun або OpenJDK) 1.8.0 або вище
  - Деякі підсистеми для вбудовування (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 або вище
- Інструменти xgettext, msgfmt та msgmerge встановлені з пакету GNU gettext
  http://www.gnu.org/software/gettext/
- Середовище збірки повинно використовувати локаль UTF-8.
- Для збирання Debian-пакетів: пакети `dpkg-deb` та `fakeroot` (через ваш менеджер пакетів)

### Процес збірки з Ant

На системах x86 виконайте наступне (це буде зібрано за допомогою IzPack4):

    ant pkg

На не-x86 системах використовуйте один з наступних варіантів:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Якщо ви хочете збирати з IzPack5, завантажте з: http://izpack.org/downloads/
потім встановіть, а потім виконайте наступну команду(и):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Для збірки непідписаного оновлення для існуючої установки виконайте:

    ant updater

Якщо у вас виникли проблеми зі збиранням повного інсталятора (Java14 та новіші версії можуть генерувати помилки збірки для izpack, пов'язані з pack200),
ви можете зібрати повний zip-архів для встановлення, який можна розпакувати та запустити на місці:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Запустіть 'ant' без аргументів, щоб побачити інші параметри збірки.

Для створення автономного Debian-пакета для Debian/Ubuntu без зовнішніх залежностей Jetty/Tomcat:
```bash
ant buildDeb
```

Це створює автономний `.deb`-пакет, який включає об'єднані бібліотеки Jetty та Tomcat без зовнішніх залежностей.


Для створення AppImage для Linux:
```bash
ant buildAppImage
```

Див. [tools/appimage/README.md](tools/appimage/README.md) для деталей.


Для отримання додаткової інформації про запуск I2P в Docker див. [docker/README.md](docker/README.md)





## Контактна інформація
Потрібна допомога? Відвідайте IRC-канал #saltR в IRC-мережі I2P

## Контактна інформація


## Контактна інформація
Звіти про помилки: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues або https://github.com/I2PPlus/i2pplus/issues

## Контактна інформація


## Контактна інформація


## Контактна інформація
## Ліцензії
I2P+ ліцензується за AGPL v.3.

## Контактна інформація
## Ліцензії


## Контактна інформація
## Ліцензії
Для ліцензій різних підкомпонентів див.: [README.md](docs/LICENSES.md)

## Контактна інформація
## Ліцензії


## Контактна інформація
## Ліцензії


## Контактна інформація
## Ліцензії
## Див. також
### Документація

## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також
- [docs/README.md](docs/README.md) - Повний індекс документації

## Контактна інформація
## Ліцензії
## Див. також
- [docs/INSTALL.md](docs/INSTALL.md) - Посібник зі встановлення

## Контактна інформація
## Ліцензії
## Див. також
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Встановлення без GUI (консольний режим)

## Контактна інформація
## Ліцензії
## Див. також
- [docs/HACKING.md](docs/HACKING.md) - Посібник для розробників та системи збірки

## Контактна інформація
## Ліцензії
## Див. також
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Структура дерева вихідного коду та де шукати
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Налагодження в середовищі виконання за допомогою JDWP та інших інструментів
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system

## Контактна інформація
## Ліцензії
## Див. також
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Керування блокуванням сесій I2P за допомогою nftables

## Контактна інформація
## Ліцензії
## Див. також
- [docs/history.txt](docs/history.txt) - Повний журнал змін

## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також
### Sub-projects

## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також
- [apps/README.md](apps/README.md) - Огляд додатків

## Контактна інформація
## Ліцензії
## Див. також
- [apps/addressbook/README.md](apps/addressbook/README.md) - Додаток адресної книги

## Контактна інформація
## Ліцензії
## Див. також
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Десктопний GUI-додаток

## Контактна інформація
## Ліцензії
## Див. також
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API керування I2P

## Контактна інформація
## Ліцензії
## Див. також
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - BitTorrent-клієнт I2PSnark

## Контактна інформація
## Ліцензії
## Див. також
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Додаток тунелю I2P

## Контактна інформація
## Ліцензії
## Див. також
- [apps/imagegen/README.md](apps/imagegen/README.md) - Інструменти генерації зображень

## Контактна інформація
## Ліцензії
## Див. також
- [apps/jetty/README.md](apps/jetty/README.md) - HTTP-сервер Jetty

## Контактна інформація
## Ліцензії
## Див. також
- [apps/jrobin/README.md](apps/jrobin/README.md) - Бібліотека моніторингу JRobin

## Контактна інформація
## Ліцензії
## Див. також
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Мінімальна бібліотека стрімінгу

## Контактна інформація
## Ліцензії
## Див. також
- [apps/pack200/README.md](apps/pack200/README.md) - Стиснення Pack200

## Контактна інформація
## Ліцензії
## Див. також
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Проксі-скрипти

## Контактна інформація
## Ліцензії
## Див. також
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Консоль маршрутизатора

## Контактна інформація
## Ліцензії
## Див. також
- [apps/sam/README.md](apps/sam/README.md) - Простий анонімний обмін повідомленнями

## Контактна інформація
## Ліцензії
## Див. також
- [apps/streaming/README.md](apps/streaming/README.md) - Бібліотека стрімінгу

## Контактна інформація
## Ліцензії
## Див. також
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-сервер

## Контактна інформація
## Ліцензії
## Див. також
- [apps/susimail/README.md](apps/susimail/README.md) - Поштовий клієнт I2P

## Контактна інформація
## Ліцензії
## Див. також
- [apps/systray/README.md](apps/systray/README.md) - Додаток системного лотка

## Контактна інформація
## Ліцензії
## Див. також
- [core/README.md](core/README.md) - Документація основної бібліотеки

## Контактна інформація
## Ліцензії
## Див. також
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Нативна JNI-бібліотека для криптографії (GMP)

## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також
### MISC

## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Керування блокуванням сесій I2P за допомогою nftables

## Контактна інформація
## Ліцензії
## Див. також
- [installer/resources/README.md](installer/resources/README.md) - Ресурси інсталятора

## Контактна інформація
## Ліцензії
## Див. також
- [tools/scripts/README.md](tools/scripts/README.md) - Утилітні скрипти для розробки та адміністрування

## Контактна інформація
## Ліцензії
## Див. також
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Скрипти валідації та тестування

## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також


## Контактна інформація
## Ліцензії
## Див. також
- [docker/README.md](docker/README.md) - Запуск I2P+ в Docker

## Контактна інформація
## Ліцензії
## Див. також
