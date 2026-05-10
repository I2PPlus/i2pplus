[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Это исходный код альтернативной реализации I2P на Java

Последний релиз: https://i2pplus.github.io/

## Установка

Смотрите [INSTALL.md](INSTALL.md) или https://i2pplus.github.io/ для инструкций по установке.

### Примечание для Windows installer

При использовании Java > 1.8 или альтернативных дистрибутивов (AdoptOpenJDK и т.д.), exe-файл инсталлятора может выдать ошибки "Java not found" или "invalid/corrupt". Решение: извлеките install.jar из exe и запустите `java -jar install.jar` из командной строки.

## Документация

https://geti2p.net/how

Часто задаваемые вопросы: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
или запустите «ant javadoc», затем откройте файл index.html в папке build/javadoc.

## Как внести вклад / Разработка для I2P+

Пожалуйста, ознакомьтесь с [HACKING.md](HACKING.md) и другими документами в каталоге docs.

## Сборка пакетов из исходного кода

Чтобы скачать I2P+ через Git: https://github.com/I2PPlus/i2pplus/

### Предварительные требования

- Java SDK (желательно Oracle/Sun или OpenJDK) 1.8.0 или выше
  - Некоторые подсистемы для встроенных (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 или выше
- Установлены инструменты xgettext, msgfmt и msgmerge из пакета GNU gettext
  http://www.gnu.org/software/gettext/
- Среда сборки должна использовать локаль UTF-8.
- Для сборки Debian-пакетов: пакеты `dpkg-deb` и `fakeroot` (через ваш пакетный менеджер)

### Процесс сборки Ant

На системах x86 выполните следующее (это создаст сборку, используя IzPack4):

     ant pkg

на прочих системах используйте вместо этого одну из следующих команд:

     ant installer-linux
     ant installer-freebsd
     ant installer-osx
     ant installer-windows

Если вы хотите собрать с использованием IzPack5, загрузите его с сайта: http://izpack.org/downloads/, а затем
установите его, а затем выполните следующую команду (ы):

     ant installer5-linux
     ant installer5-freebsd
     ant installer5-osx
     ant installer5-windows

Чтобы создать не подписанное обновление для существующей установки, выполните:

     ant updater

Запустите 'ant' без аргументов, чтобы увидеть другие варианты сборки.

Для создания AppImage для Linux:
```bash
ant buildAppImage
```

См. [tools/appimage/README.md](tools/appimage/README.md) для подробностей.

Для создания автономного Debian-пакета для Debian/Ubuntu без внешних зависимостей Jetty/Tomcat:
```bash
ant buildDeb
```

Это создает автономный `.deb`-пакет, который включает объединенные библиотеки Jetty и Tomcat без внешних зависимостей.

## Контактная информация

Нужна помощь? Смотрите канал IRC #saltR в сети I2P IRC.

Сообщения об ошибках: https://github.com/I2PPlus/i2pplus/issues

## Лицензии

I2P+ лицензирована по AGPL v.3.

Для различных подкомпонентов лицензии см.: [README.md](docs/LICENSES.md)

## См. также

### Документация

- [docs/README.md](docs/README.md) - Полный индекс документации
- [docs/INSTALL.md](docs/INSTALL.md) - Руководство по установке
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Установка без GUI (консольный режим)
- [docs/HACKING.md](docs/HACKING.md) - Руководство разработчика и системы сборки
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Структура исходного кода
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Управление bans сессий I2P через nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Лицензии третьих сторон
- [docs/history.txt](docs/history.txt) - Полный список изменений

### Sub-projects

- [apps/README.md](apps/README.md) - Обзор приложений
- [apps/addressbook/README.md](apps/addressbook/README.md) - Приложение адресной книги
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Приложение десктопного GUI
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API управления I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - BitTorrent клиент I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Приложение туннеля I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Инструменты генерации изображений
- [apps/jetty/README.md](apps/jetty/README.md) - HTTP-сервер Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Библиотека мониторинга JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Минимальная библиотека стриминга
- [apps/pack200/README.md](apps/pack200/README.md) - Сжатие Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Прокси-скрипты
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Консоль роутера
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Библиотека стриминга
- [apps/susidns/README.md](apps/susidns/README.md) - DNS-сервер
- [apps/susimail/README.md](apps/susimail/README.md) - Почтовый клиент I2P
- [apps/systray/README.md](apps/systray/README.md) - Приложение системного лотка
- [core/README.md](core/README.md) - Документация библиотеки core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Нативная JNI-библиотека для криптографии (GMP)

### MISC

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Управление bans сессий I2P через nftables
- [installer/resources/README.md](installer/resources/README.md) - Ресурсы установщика
- [tools/scripts/README.md](tools/scripts/README.md) - Утилитные скрипты для разработки и администрирования
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Скрипты проверки и тестирования