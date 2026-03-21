# IzPack Installer Toolkit (`lib/izpack/`)

Contains the IzPack installer compiler and all resources needed to build I2P+ installer packages for both IzPack v4 and v5.

## Structure

| Path               | Description                                                      |
| ------------------ | ---------------------------------------------------------------- |
| `4/`               | IzPack 4 standalone compiler and patches                         |
| `5/`               | IzPack 5 language pack patches (Indonesian, Portuguese, Chinese) |
| `resources/`       | Shared installer resources (images, icons, langpacks, shortcuts) |
| `install.xml`      | IzPack 4 installation descriptor                                 |
| `install5.xml`     | IzPack 5 installation descriptor                                 |
| `customicons.xml`  | IzPack 5 icon configuration                                      |
| `i2pinstaller.xml` | Launch4j config for wrapping the installer JAR as a Windows EXE  |

## Shared Resources (`resources/`)

- `images/` - Installer banner images (`i2plogo.png`, `i2plogo2.png`, `console.png`)
- `icons/` - Windows icons (`console.ico`, `start.ico`, `stop.ico`, `uninstall.ico`)
- `shortcutSpec.xml` - Windows desktop/start menu shortcut definitions
- `CustomLangPack.xml_eng` - English custom language pack
- `welcome.html` - IzPack 5 welcome panel content
- `start-i2p.txt` - Post-install info panel text
