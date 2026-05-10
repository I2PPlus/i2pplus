[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Este é o código fonte do fork suave (soft-fork) da implementação Java do I2P.

Último lançamento: https://i2pplus.github.io/

## Instalação

Consulte [INSTALL.md](INSTALL.md) ou https://i2pplus.github.io/ para instruções de instalação.

### Nota do instalador do Windows

Com Java > 1.8 ou distribuições alternativas (AdoptOpenJDK, etc.), o exe do instalador pode falhar com erros "Java not found" ou "invalid/corrupt". Solução alternativa: extraia install.jar do exe e execute `java -jar install.jar` a partir da linha de comando.

## Documentação

https://geti2p.net/how

FAQ: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
ou execute 'ant javadoc' e depois inicie em build/javadoc/index.html

## Como contribuir / Programar no I2P

Por favor, verifique [HACKING.md](docs/HACKING.md) e outros documentos no diretório docs.

## Compilando pacotes a partir do código fonte

Para obter o branch de desenvolvimento do controle de código fonte: https://github.com/I2PPlus/i2pplus/

### Pré-requisitos

- Java SDK (preferencialmente Oracle/Sun ou OpenJDK) 1.8.0 ou superior
  - Sistemas operacionais não-linux e JVMs: Veja 
  - Certos subsistemas para embarcado (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 ou superior
- As ferramentas xgettext, msgfmt e msgmerge instaladas do pacote GNU gettext
  http://www.gnu.org/software/gettext/
- O ambiente de compilação deve usar uma localização UTF-8.
- Para compilação de pacotes Debian: pacotes `dpkg-deb` e `fakeroot` (através do seu gerenciador de pacotes)

### Processo de compilação com Ant

Em sistemas x86 execute o seguinte (isso irá compilar usando IzPack4):

    ant pkg

Em sistemas não-x86, use uma das seguintes alternativas:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Se você quiser compilar com IzPack5, baixe de: http://izpack.org/downloads/
e então instale, e então execute o(s) seguinte(s) comando(s):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Para compilar uma atualização não assinada para uma instalação existente, execute:

    ant updater

Se você tiver problemas ao compilar um instalador completo (Java14 e versões posteriores podem gerar erros de compilação para izpack relacionados a pack200),
você pode compilar um zip de instalação completo que pode ser extraído e executado no local:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Execute 'ant' sem argumentos para ver outras opções de compilação.

Para criar um pacote Debian autônomo para Debian/Ubuntu sem dependências externas de Jetty/Tomcat:
```bash
ant buildDeb
```

Isso cria um pacote `.deb` autônomo que inclui as bibliotecas Jetty e Tomcat agrupadas, sem dependências externas.


Para criar um AppImage para Linux:
```bash
ant buildAppImage
```

Veja [tools/appimage/README.md](tools/appimage/README.md) para detalhes.


Para mais informações sobre como executar o I2P no Docker, veja [docker/README.md](docker/README.md)


## Informações de contato

Precisa de ajuda? Visite o canal IRC #saltR na rede IRC do I2P

Relatórios de bugs: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues ou https://github.com/I2PPlus/i2pplus/issues

## Licenças

I2P+ é licenciado sob a AGPL v.3.

Para as licenças dos vários subcomponentes, veja: [README.md](docs/LICENSES.md)

## Veja também

### Documentação

- [docs/README.md](docs/README.md) - Índice completo de documentação
- [docs/INSTALL.md](docs/INSTALL.md) - Guia de instalação
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Instalação headless (modo console)
- [docs/HACKING.md](docs/HACKING.md) - Guia do desenvolvedor e sistemas de build
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Layout da árvore de código fonte
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gerenciamento de proibições de sessão I2P com nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Licenças de terceiros
- [docs/history.txt](docs/history.txt) - Registro completo de alterações

### Sub-projects

- [apps/README.md](apps/README.md) - Visão geral dos aplicativos
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplicativo de catálogo de endereços
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Aplicativo GUI de desktop
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API de controle I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Cliente BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplicativo de túnel I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Ferramentas de geração de imagem
- [apps/jetty/README.md](apps/jetty/README.md) - Servidor HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Biblioteca de monitoramento JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Biblioteca de streaming mínima
- [apps/pack200/README.md](apps/pack200/README.md) - Compressão Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Scripts proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Console do router
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Biblioteca de streaming
- [apps/susidns/README.md](apps/susidns/README.md) - Servidor DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Cliente de email I2P
- [apps/systray/README.md](apps/systray/README.md) - Aplicativo de bandeja do sistema
- [core/README.md](core/README.md) - Documentação da biblioteca core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Biblioteca JNI nativa para criptografia (GMP)

### Misc

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gerenciamento de proibições de sessão I2P com nftables
- [installer/resources/README.md](installer/resources/README.md) - Recursos do instalador agrupados
- [tools/scripts/README.md](tools/scripts/README.md) - Scripts utilitários para desenvolvimento e administração
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Scripts de validação e teste



- [docker/README.md](docker/README.md) - Executar I2P+ no Docker

