[![CodeQL](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/codeql-analysis.yml)
[![Java CI](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/vituperative/i2pplus/actions/workflows/ant.yml)
[![I2P+ Update zip](https://i2pplus.github.io/download.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](https://i2pplus.github.io/i2psnarkdownload.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](https://i2pplus.github.io/javadocsdownload.svg)](https://i2pplus.github.io/javadoc.zip)

# I2P+

[Русский](README-ru.md) | [日本語](README-ja.md) | [中文](README-zh.md) | [हिन्दी](README-hi.md) | [བོད་ཡིག](README-bo.md) | [فارسی](README-fa.md)

འདི་ནི་Javaགྱི་I2Pལག་བསྟར་གྱི་soft-forkགྱི་འབྱུང་ཁུངས་ཀོད་ལས་རེད།

ཐོན་རིམ་གསར་ཤོས། https://i2pplus.github.io/

## གཞི་བཙུགས་བྱེད་པ།

INSTALL.txtཡང་ན་https://i2pplus.github.io/ ནང་གཞི་བཙུགས་བྱེད་ཐབས་ལ་གཟིགས་རོགས།

## ཡིག་ཆ་

https://geti2p.net/how

དོགས་གནས་དྲི་བའི་དྲ་ངོས། https://geti2p.net/faq

API: http://docs.i2p-projekt.de/javadoc/
ཡང་ན་'ant javadoc'ལག་བསྟར་བྱས་རྗེས་build/javadoc/index.htmlནས་འགོ་བརྩམ་རོགས།

## ཇུས་འགོད་འམ་I2Pལ་བཟོ་བཅོས་བྱེད་ཐབས།

ཁྱེད་ཀྱིས་[HACKING.md](docs/HACKING.md)དང་docsཐོག་གི་ཡིག་ཆ་གཞན་དག་ལ་གཟིགས་རོགས།

## འབྱུང་ཁུངས་ནས་ཐུམ་སྒྲིལ་བཟོ་བ།

འཕེལ་རྒྱས་ཡན་ལག་འབྱུང་ཁུངས་ཚོད་འཛིན་ནས་ལེན་ཐབས། https://gitlab.com/i2p.plus/I2P.Plus/

### མདུན་ལྗིད་ཆ་རྐྱེན།

- Java SDK (སྟེར་ཐོན་Oracle/Sunཡང་ན་OpenJDK) 1.8.0ཡང་ན་དེ་ལས་མཐོ་བ།
  - Linuxམ་ཡིན་པའི་བཀོལ་སྤྱོད་མ་ལག་དང་JVMs: https://trac.i2p2.de/wiki/java ལ་གཟིགས་རོགས།
  - བཅུད་འདུས་མ་ལག་གི་ལྟེ་བའི་ཆ་ལག (core, router, mstreaming, streaming, i2ptunnel)
    ལ་Java 1.6ཙམ་དགོས།
- Apache Ant 1.9.8ཡང་ན་དེ་ལས་མཐོ་བ།
- GNU gettextཐུམ་སྒྲིལ་ནས་གཞི་བཙུགས་བྱས་པའི་xgettext, msgfmt, དང་msgmergeལག་ཆ།
  http://www.gnu.org/software/gettext/
- བཟོ་སྐྲུན་ཁོར་ཡུག་གིས་UTF-8སྐད་རིགས་བེད་སྤྱོད་བྱེད་དགོས།

### Antབཟོ་སྐྲུན་བརྒྱུད་རིམ།

x86མ་ལག་ཐོག་གཤམ་གསལ་ལག་བསྟར་བྱོས། (འདིས་IzPack4བེད་སྤྱད་ནས་བཟོ་རྒྱག་རྒྱུ།)

    ant pkg

x86མ་ཡིན་ན། དེའི་ཚབ་གཤམ་གསལ་གང་རུང་ཞིག་བེད་སྤྱོད་བྱོས།

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

གལ་ཏེ་ཁྱེད་ཀྱིས་IzPack5བེད་སྤྱད་ནས་བཟོ་འདོད་ན། http://izpack.org/downloads/ ནས་ཕབ་ལེན་བྱས་རྗེས་
གཞི་བཙུགས་བྱས་ནས་གཤམ་གསལ་བརྡ་བཀོད་ལག་བསྟར་བྱོས།

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

གཞི་བཙུགས་བྱས་ཟིན་པའི་གྱི་དུས་མཐུན་བརྙན་བཀོད་མི་བྱས་པའི་དུས་མཐུན་བཟོ་དུས། གཤམ་གསལ་ལག་བསྟར་བྱོས།

    ant updater

གལ་ཏེ་ཁྱེད་ལ་ཆ་ཚང་བའི་གཞི་བཙུགས་འཕྲུལ་ཆས་བཟོ་སྐྲུན་བྱེད་པར་དཀའ་ངལ་ཡོད་ན། (Java14དང་དེ་ཕན་izpackདང་pack200འབྲེལ་བའི་བཟོ་སྐྲུན་ནོར་འཁྲུལ་འབྱུང་སྲིད།)
ཁྱེད་ཀྱིས་ཆ་ཚང་བའི་གཞི་བཙུགས་zipབཟོ་ཆོག་པ་དེ་ནི་སྦྱོར་བརྗེ་བྱས་ནས་གནས་ཡུལ་དུ་ལག་བསྟར་བྱེད་ཐུབ།

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

'ant'ལ་སྒྲུབ་རྟགས་མེད་པར་ལག་བསྟར་བྱས་ན་བཟོ་སྐྲུན་གདམ་ག་གཞན་དག་མཐོང་ཐུབ།

### Docker
Dockerནང་I2Pཇི་ལྟར་ལག་བསྟར་བྱ་ཐབས་ཀྱི་ཆེད་དུ། [Docker.md](Docker.md) ལ་གཟིགས་རོགས།

## འབྲེལ་གཏུག་ཆ་འཕྲིན།

རོགས་རམ་དགོས་སམ། I2P IRCདྲ་བའི་ཐོག་#saltRཁང་བརྡ་ལ་གཟིགས་རོགས།

ནོར་འཁྲུལ་སྙན་ཞུ། https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues ཡང་ན་ https://github.com/I2PPlus/i2pplus/issues

## ཆོག་འཕྲལ་ཁང་།

I2P+ནི་AGPL v.3འོག་ཆོག་འཕྲལ་བཞག་ཡོད།

ལྟེ་བའི་ཆ་ལག་སོ་སོའི་ཆོག་འཕྲལ་ཁང་གི་ཆེད་དུ། LICENSE.txtལ་གཟིགས་རོགས།