[![CodeQL](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml)
[![Java CI](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml)
[![I2P+ Update zip](https://i2pplus.github.io/download.svg)](https://gitlab.com/i2pplus/I2P.Plus/-/jobs/artifacts/master/raw/i2pupdate.zip?job=Java8)
[![I2P+ I2PSnark standalone](https://i2pplus.github.io/i2psnarkdownload.svg)](https://gitlab.com/i2pplus/I2P.Plus/-/jobs/artifacts/master/raw/i2psnark-standalone.zip?job=Java8)
[![I2P+ Javadocs](https://i2pplus.github.io/javadocsdownload.svg)](https://gitlab.com/i2pplus/I2P.Plus/-/jobs/artifacts/master/raw/javadoc.zip?job=Java8)

# I2P+

Это исходный код для мягкого форка реализации I2P на Java.

Последний релиз: https://i2pplus.github.io/

## Установка

Смотрите INSTALL.txt или https://i2pplus.github.io/ для инструкций по установке.

## Документация

https://geti2p.net/how

Часто задаваемые вопросы: https://geti2p.net/faq

API: http://docs.i2p-projekt.de/javadoc/
или запустите «ant javadoc», затем откройте файл index.html в папке build/javadoc.

## Как внести вклад / Взломать I2P

Пожалуйста, ознакомьтесь с [HACKs/HACKING.md) и другими документами в каталоге docs.

## Сборка пакетов из исходного кода

Чтобы получить ветвь разработки из системы управления версиями: https://gitlab.com/i2p.plus/I2P.Plus/

### Предварительные требования

- Java SDK (желательно Oracle/Sun или OpenJDK) 1.8.0 или выше
  - Операционные системы, отличные от Linux, и JVM: см. https://trac.i2p2.de/wiki/java
  - Некоторые подсистемы для встроенных (core, router, mstreaming, streaming, i2ptunnel)
    требуют только Java 1.6
- Apache Ant 1.9.8 или выше
- Установлены инструменты xgettext, msgfmt и msgmerge из пакета GNU gettext
  http://www.gnu.org/software/gettext/
- Среда сборки должна использовать локаль UTF-8.

### Процесс сборки Ant

На системах x86 выполните следующее (это создаст сборку, используя IzPack4):

     ant pkg

На ненормированных системах используйте вместо этого одну из следующих команд:

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

Чтобы создать неподписанное обновление для существующей установки, выполните:

     ant updater

Запустите 'ant' без аргументов, чтобы увидеть другие варианты сборки.

### Docker
Дополнительную информацию о запуске I2P в Docker см.  (Docker.md).
## Контактная информация

Нужна помощь? Смотрите канал IRC #saltR в сети I2P IRC.

Сообщения об ошибках: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues или https://gitlab.com/i2p.plus/I2P.Plus/issues

## Лицензии

I2P+ лицензирована по AGPL v.3.

Для различных подкомпонентов лицензии см.: LICENSE.txt