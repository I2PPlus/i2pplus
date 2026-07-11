[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Este es el código fuente del fork blando (soft-fork) de la implementación en Java de I2P.

Última versión: https://i2pplus.github.io/

## Instalación

Consulte [INSTALL.md](INSTALL.md) o https://i2pplus.github.io/ para obtener instrucciones de instalación.

### Nota del instalador de Windows

Con Java > 1.8 o distribuciones alternativas (AdoptOpenJDK, etc.), el exe del instalador puede fallar con errores "Java not found" o "invalid/corrupt". Solución alternativa: extrae install.jar del exe y ejecuta `java -jar install.jar` desde la línea de comandos.

## Documentación

https://geti2p.net/how

Preguntas frecuentes: https://geti2p.net/faq

API: https://i2pplus.github.io/javadoc/
o ejecuta 'ant javadoc' y luego inicia en build/javadoc/index.html

## Cómo contribuir / Programar en I2P

Por favor revisa [HACKING.md](docs/HACKING.md) y otros documentos en el directorio docs.

## Construir paquetes desde el código fuente

Para obtener la rama de desarrollo del control de código fuente: https://github.com/I2PPlus/i2pplus/

### Requisitos previos

- Java SDK (preferiblemente Oracle/Sun u OpenJDK) 1.8.0 o superior
  - Ciertos subsistemas para embebido (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 o superior
- Las herramientas xgettext, msgfmt y msgmerge instaladas del paquete GNU gettext
  http://www.gnu.org/software/gettext/
- El entorno de construcción debe usar una configuración regional UTF-8.
- Para construcciones de paquetes Debian: paquetes `dpkg-deb` y `fakeroot` (a través de tu gestor de paquetes)

### Proceso de construcción con Ant

En sistemas x86 ejecuta lo siguiente (esto construirá usando IzPack4):

    ant pkg

En sistemas no x86, usa uno de los siguientes en su lugar:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Si quieres construir con IzPack5, descarga desde: http://izpack.org/downloads/
y luego instálalo, y luego ejecuta el siguiente comando(s):

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Para construir una actualización sin firmar para una instalación existente, ejecuta:

    ant updater

Si tienes problemas al construir un instalador completo (Java14 y posteriores pueden generar errores de construcción para izpack relacionados con pack200),
puedes construir un zip de instalación completa que puede ser extraído y ejecutado in situ:

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Ejecuta 'ant' sin argumentos para ver otras opciones de construcción.

Para crear un paquete Debian independiente para Debian/Ubuntu sin dependencias externas de Jetty/Tomcat:
```bash
ant buildDeb
```

Esto crea un paquete `.deb` independiente que incluye las bibliotecas Jetty y Tomcat agrupadas, sin ninguna dependencia externa.


Para crear un AppImage para Linux:
```bash
ant buildAppImage
```

Ver [tools/appimage/README.md](tools/appimage/README.md) para más detalles.


Para más información sobre cómo ejecutar I2P en Docker, consulta [docker/README.md](docker/README.md)


## Información de contacto

¿Necesitas ayuda? Visita el canal IRC #saltR en la red IRC de I2P

Informes de errores: https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues o https://github.com/I2PPlus/i2pplus/issues

## Licencias

I2P+ está licenciado bajo AGPL v.3.

Para las licencias de los subcomponentes, ver: [README.md](docs/LICENSES.md)

## Ver también

### Documentación
- [docs/README.md](docs/README.md) - Índice completo de documentación
- [docs/INSTALL.md](docs/INSTALL.md) - Guía de instalación
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Instalación sin GUI (modo consola)
- [docs/HACKING.md](docs/HACKING.md) - Guía para desarrolladores y sistemas de compilación
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Diseño del árbol de código fuente
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Depuración en tiempo de ejecución con JDWP y otras herramientas
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestión de prohibiciones de sesión I2P con nftables
- [docs/LICENSES.md](docs/LICENSES.md) - Licencias de terceros
- [docs/history.txt](docs/history.txt) - Registro de cambios completo

### Sub-projects
- [apps/README.md](apps/README.md) - Resumen de aplicaciones
- [apps/addressbook/README.md](apps/addressbook/README.md) - Aplicación de libreta de direcciones
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Aplicación GUI de escritorio
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API de control I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Cliente BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Aplicación de túnel I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Herramientas de generación de imágenes
- [apps/jetty/README.md](apps/jetty/README.md) - Servidor HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Biblioteca de monitorización JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Biblioteca de streaming mínima
- [apps/pack200/README.md](apps/pack200/README.md) - Compresión Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Scripts proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Consola del router
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Biblioteca de streaming
- [apps/susidns/README.md](apps/susidns/README.md) - Servidor DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Cliente de correo I2P
- [apps/systray/README.md](apps/systray/README.md) - Aplicación de bandeja del sistema
- [core/README.md](core/README.md) - Documentación de biblioteca core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Biblioteca JNI nativa para criptografía (GMP)

### MISC
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestión de prohibiciones de sesión I2P con nftables
- [installer/resources/README.md](installer/resources/README.md) - Recursos del instalador incluidos
- [tools/scripts/README.md](tools/scripts/README.md) - Scripts de utilidad para desarrollo y administración
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Scripts de validación y prueba

- [docker/README.md](docker/README.md) -Ejecutar I2P+ en Docker

