[![Java CI](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml/badge.svg)](https://github.com/I2PPlus/i2pplus/actions/workflows/ant.yml)
[![I2P+ Installer](../tools/badges/installer-badge.svg)](https://i2pplus.github.io/installers/i2pinstall.exe)
[![I2P+ Update zip](../tools/badges/update-badge.svg)](https://i2pplus.github.io/i2pupdate.zip)
[![I2P+ I2PSnark standalone](../tools/badges/i2psnark-badge.svg)](https://i2pplus.github.io/installers/i2psnark-standalone.zip)
[![I2P+ Javadocs](../tools/badges/javadocs-badge.svg)](https://i2pplus.github.io/javadoc.zip)
[![Docker](../tools/badges/docker-badge.svg)](docker/README.md)
[![AppImage](../tools/badges/appimage-badge.svg)](tools/appimage/README.md)

# I2P+

[<img src="../apps/routerconsole/resources/icons/flags_svg/ar.svg" width="28" height="21" title="العربية">](README-ar.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/xt.svg" width="28" height="21" title="བོད་ཡིག">](README-bo.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cz.svg" width="28" height="21" title="Čeština">](README-cs.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/de.svg" width="28" height="21" title="Deutsch">](README-de.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gr.svg" width="28" height="21" title="Ελληνικά">](README-el.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/es.svg" width="28" height="21" title="Español">](README-es.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ir.svg" width="28" height="21" title="فارسی">](README-fa.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/fr.svg" width="28" height="21" title="Français">](README-fr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/il.svg" width="28" height="21" title="עברית">](README-he.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/in.svg" width="28" height="21" title="हिन्दी">](README-hi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/hu.svg" width="28" height="21" title="Magyar">](README-hu.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/id.svg" width="28" height="21" title="Bahasa Indonesia">](README-id.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/it.svg" width="28" height="21" title="Italiano">](README-it.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/jp.svg" width="28" height="21" title="日本語">](README-ja.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/kr.svg" width="28" height="21" title="한국어">](README-ko.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/nl.svg" width="28" height="21" title="Nederlands">](README-nl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pl.svg" width="28" height="21" title="Polski">](README-pl.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pt.svg" width="28" height="21" title="Português">](README-pt.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ro.svg" width="28" height="21" title="Română">](README-ro.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ru.svg" width="28" height="21" title="Русский">](README-ru.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/th.svg" width="28" height="21" title="ภาษาไทย">](README-th.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/tr.svg" width="28" height="21" title="Türkçe">](README-tr.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/ua.svg" width="28" height="21" title="Українська">](README-uk.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/pk.svg" width="28" height="21" title="اردو">](README-ur.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/vn.svg" width="28" height="21" title="Tiếng Việt">](README-vi.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/cn.svg" width="28" height="21" title="中文">](README-zh.md) [<img src="../apps/routerconsole/resources/icons/flags_svg/gb.svg" width="28" height="21" title="English">](README-en.md)

Ceci est le code source du fork logiciel de l'implémentation Java d'I2P.

Dernière version : https://i2pplus.github.io/

## Installation

Voir [INSTALL.md](INSTALL.md) ou https://i2pplus.github.io/ pour les instructions d'installation.

### Note sur l'installateur Windows

Avec Java > 1.8 ou les distributions alternatives (AdoptOpenJDK, etc.), l'exe de l'installateur peut échouer avec des erreurs "Java not found" ou "invalid/corrupt". Solution de contournement : extrayez install.jar de l'exe et exécutez `java -jar install.jar` depuis la ligne de commande.

## Documentation

https://geti2p.net/how

FAQ : https://geti2p.net/faq

API : https://i2pplus.github.io/javadoc/
ou exécutez 'ant javadoc' puis démarrez à build/javadoc/index.html

## Comment contribuer / Hacker sur I2P

Veuillez consulter [HACKING.md](docs/HACKING.md) et d'autres documents dans le répertoire docs.

## Construction des paquets à partir du源代码

Pour obtenir la branche de développement depuis le contrôle de code source : https://github.com/I2PPlus/i2pplus/

### Prérequis

- Java SDK (de préférence Oracle/Sun ou OpenJDK) 1.8.0 ou supérieur
  - Certains sous-systèmes pour embarqué (core, router, mstreaming, streaming, i2ptunnel)
- Apache Ant 1.9.8 ou supérieur
- Les outils xgettext, msgfmt et msgmerge installés depuis le paquet GNU gettext
  http://www.gnu.org/software/gettext/
- L'environnement de construction doit utiliser une locale UTF-8.
- Pour les constructions de paquets Debian : paquets `dpkg-deb` et `fakeroot` (via votre gestionnaire de paquets)

### Processus de construction avec Ant

Sur les systèmes x86, exécutez ce qui suit (cela construira avec IzPack4) :

    ant pkg

Sur les systèmes non-x86, utilisez l'une des alternatives suivantes :

    ant installer-linux
    ant installer-freebsd
    ant installer-osx
    ant installer-windows

Si vous voulez construire avec IzPack5, téléchargez depuis : http://izpack.org/downloads/
puis installez-le, puis exécutez la ou les commandes suivantes :

    ant installer5-linux
    ant installer5-freebsd
    ant installer5-osx
    ant installer5-windows

Pour construire une mise à jour non signée pour une installation existante, exécutez :

    ant updater

Si vous avez des problèmes pour construire un installateur complet (Java14 et versions ultérieures peuvent générer des erreurs de construction pour izpack concernant pack200),
vous pouvez construire un zip d'installation complet qui peut être extrait et exécuté sur place :

     ant zip-linux
     ant zip-freebsd
     ant zip-macos
     ant zip-windows

Exécutez 'ant' sans arguments pour voir les autres options de construction.

Pour créer un package Debian autonome pour Debian/Ubuntu sans dépendances externes à Jetty/Tomcat:
```bash
ant buildDeb
```

Cela crée un package `.deb` autonome qui inclut les bibliothèques Jetty et Tomcat groupées, sans aucune dépendance externe.


Pour créer un AppImage pour Linux :
```bash
ant buildAppImage
```

Voir [tools/appimage/README.md](tools/appimage/README.md) pour les détails.


Pour plus d'informations sur la façon d'exécuter I2P dans Docker, voir [docker/README.md](docker/README.md)


## Informations de contact

Besoin d'aide ? Visitez le canal IRC #saltR sur le réseau IRC I2P

Rapports de bugs : https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues ou https://github.com/I2PPlus/i2pplus/issues

## Licences

I2P+ est sous licence AGPL v.3.

Pour les licences des sous-composants, voir : [README.md](docs/LICENSES.md)

## Voir également

### Documentation

- [docs/README.md](docs/README.md) - Index complet de la documentation
- [docs/INSTALL.md](docs/INSTALL.md) - Guide d'installation
- [docs/INSTALL-headless.md](docs/INSTALL-headless.md) - Installation sans GUI (mode console)
- [docs/HACKING.md](docs/HACKING.md) - Guide du développeur et systèmes de build
- [docs/DIRECTORIES.md](docs/DIRECTORIES.md) - Structure de l'arborescence source et où trouver les choses
- [docs/DEBUGGING.md](docs/DEBUGGING.md) - Débogage en temps réel avec JDWP et autres outils
- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestion des interdictions de session I2P avec nftables
- [docs/THEMING.md](docs/THEMING.md) - Console and webapp theming system
- [docs/LICENSES.md](docs/LICENSES.md) - Licences tierces
- [docs/history.txt](docs/history.txt) - Journal des modifications complet

### Sub-projects

- [apps/README.md](apps/README.md) - Aperçu des applications
- [apps/addressbook/README.md](apps/addressbook/README.md) - Application carnet d'adresses
- [apps/desktopgui/README.md](apps/desktopgui/README.md) - Application GUI de bureau
- [apps/i2pcontrol/README.md](apps/i2pcontrol/README.md) - API de contrôle I2P
- [apps/i2psnark/README.md](apps/i2psnark/README.md) - Client BitTorrent I2PSnark
- [apps/i2ptunnel/README.md](apps/i2ptunnel/README.md) - Application tunnel I2P
- [apps/imagegen/README.md](apps/imagegen/README.md) - Outils de génération d'images
- [apps/jetty/README.md](apps/jetty/README.md) - Serveur HTTP Jetty
- [apps/jrobin/README.md](apps/jrobin/README.md) - Bibliothèque de supervision JRobin
- [apps/ministreaming/README.md](apps/ministreaming/README.md) - Bibliothèque de streaming minimale
- [apps/pack200/README.md](apps/pack200/README.md) - Compression Pack200
- [apps/proxyscript/README.md](apps/proxyscript/README.md) - Scripts proxy
- [apps/routerconsole/README.md](apps/routerconsole/README.md) - Console routeur
- [apps/sam/README.md](apps/sam/README.md) - Simple Anonymous Messaging
- [apps/streaming/README.md](apps/streaming/README.md) - Bibliothèque de streaming
- [apps/susidns/README.md](apps/susidns/README.md) - Serveur DNS
- [apps/susimail/README.md](apps/susimail/README.md) - Client courriel I2P
- [apps/systray/README.md](apps/systray/README.md) - Application barre système
- [core/README.md](core/README.md) - Documentation de la bibliothèque core
- [installer/lib/jbigi/README.md](installer/lib/jbigi/README.md) - Bibliothèque JNI native pour la cryptographie (GMP)

### MISC

- [docs/i2p-sessionban-nftables.md](docs/i2p-sessionban-nftables.md) - Gestion des interdictions de session I2P avec nftables
- [installer/resources/README.md](installer/resources/README.md) - Ressources du programme d'installation groupées
- [tools/scripts/README.md](tools/scripts/README.md) - Scripts utilitaires pour le développement et l'administration
- [tools/scripts/tests/README.md](tools/scripts/tests/README.md) - Scripts de validation et de test



- [docker/README.md](docker/README.md) - Exécuter I2P+ dans Docker

