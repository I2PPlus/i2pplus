# I2P+ Source Directory Guide

A quick reference to the I2P+ source tree layout.

| Directory                                                               | Description                                                        |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `apps`                                                                  | Applications and clients that ship with I2P.                       |
| `apps/addressbook`                                                      | Headless addressbook management code.                              |
| `apps/apparmor`                                                         | AppArmor ruleset.                                                  |
| `apps/BOB`                                                              | BOB service code.                                                  |
| `apps/desktopgui`                                                       | System tray application.                                           |
| `apps/i2psnark`                                                         | Torrent client component in the web console.                       |
| `apps/i2ptunnel`                                                        | Hidden Service Manager and its GUI in the web console.             |
| `apps/imagegen`                                                         | Image generator webapp.                                            |
| `apps/jetty`                                                            | Jetty webserver code.                                              |
| `apps/jrobin`                                                           | Graph package for the console.                                     |
| `apps/ministreaming`                                                    | Streaming (TCP-like socket) interface.                             |
| `apps/routerconsole`                                                    | Router console code.                                               |
| `apps/routerconsole/java/src/net/i2p/router/news`                       | News feed subsystem.                                               |
| `apps/routerconsole/java/src/net/i2p/router/update`                     | Automatic update subsystem.                                        |
| `apps/routerconsole/java/src/net/i2p/router/web`                        | Console Java code, including plugin support.                       |
| `apps/routerconsole/jsp`                                                | JSPs for the console.                                              |
| `apps/sam`                                                              | SAM service.                                                       |
| `apps/streaming`                                                        | Streaming (TCP-like socket) implementation.                        |
| `apps/susidns`                                                          | Addressbook component in the web console.                          |
| `apps/susimail`                                                         | Mail client component in the web console.                          |
| `apps/systray`                                                          | Legacy system tray application (removed) and related utilities.    |
| `installer`                                                             | Installer code.                                                    |
| `installer/lib/izpack`                                                  | Installer libraries.                                               |
| `installer/lib/jbigi`                                                   | jbigi and jcpuid DLLs.                                             |
| `installer/lib/launch4j`                                                | Windows jar-to-exe binary.                                         |
| `installer/lib/wrapper`                                                 | Wrapper binaries and libraries.                                    |
| `installer/resources`                                                   | Static files bundled with I2P.                                     |
| `core/java`                                                             | Common core code used by both the router and apps.                 |
| `core/java/src/net/i2p/app`                                             | App interface code.                                                |
| `core/java/src/net/i2p/client`                                          | Low-level client I2CP interface (I2PClient, I2PSession, etc.).     |
| `core/java/src/net/i2p/client/impl`                                     | Client-side I2CP implementation.                                   |
| `core/java/src/net/i2p/client/naming`                                   | Addressbook interfaces and base implementation.                    |
| `core/java/src/net/i2p/crypto`                                          | Most of the crypto code.                                           |
| `core/java/src/net/i2p/data`                                            | Common data structures and data-related utilities.                 |
| `core/java/src/net/i2p/internal`                                        | Internal socketless I2CP connections.                              |
| `core/java/src/net/i2p/kademlia`                                        | Base Kademlia implementation used by the router and i2psnark.      |
| `core/java/src/net/i2p/socks`                                           | SOCKS client implementation.                                       |
| `core/java/src/net/i2p/stat`                                            | Statistics subsystem.                                              |
| `core/java/src/net/i2p/time`                                            | Internal time representation.                                      |
| `core/java/src/net/i2p/update`                                          | Parts of the update code.                                          |
| `core/java/src/net/i2p/util`                                            | Utility code (Log, FileUtil, EepGet, HexDump, etc.).               |
| `router/java`                                                           | I2P router code.                                                   |
| `router/java/src/net/i2p/data/i2np`                                     | I2NP code, the inner protocol for I2P.                             |
| `router/java/src/net/i2p/data/router`                                   | Router data structures such as RouterInfo.                         |
| `router/java/src/net/i2p/router/client`                                 | Router-side I2CP implementation.                                   |
| `router/java/src/net/i2p/router/crypto`                                 | Router crypto not needed in the core library.                      |
| `router/java/src/net/i2p/router/dummy`                                  | Dummy implementations of subsystems for testing.                   |
| `router/java/src/net/i2p/router/message`                                | Garlic message creation and parsing.                               |
| `router/java/src/net/i2p/router/networkdb/kademlia`                     | DHT (Kademlia) code.                                               |
| `router/java/src/net/i2p/router/networkdb/reseed`                       | Reseed code.                                                       |
| `router/java/src/net/i2p/router/peermanager`                            | Peer profile tracking and storage.                                 |
| `router/java/src/net/i2p/router/startup`                                | Startup sequence code.                                             |
| `router/java/src/net/i2p/router/transport`                              | Transport implementation code (NTCP, SSU).                         |
| `router/java/src/net/i2p/router/transport/crypto`                       | Transport crypto (DH).                                             |
| `router/java/src/net/i2p/router/tasks`                                  | Small router helpers, run periodically.                            |
| `router/java/src/net/i2p/router/tunnel`                                 | Tunnel implementation code.                                        |
| `router/java/src/net/i2p/router/util`                                   | Router utilities.                                                  |

