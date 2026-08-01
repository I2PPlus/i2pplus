# I2PSnark (`i2psnark/`)

I2P-enhanced BitTorrent client with web UI, DHT, magnet links, PEX, and swarm management for anonymous file sharing over I2P. Fork of Snark.

## Overview

I2PSnark brings BitTorrent to I2P with privacy built-in:
- All peers are I2P destinations (anonymous)
- No tracker needed (DHT or local)
- Integrated in router console

## How It Differs from Regular BitTorrent

| Aspect   | Regular BitTorrent | I2PSnark             |
| -------- | ------------------ | -------------------- |
| Peers    | IP addresses       | I2P destinations     |
| Trackers | Centralized        | DHT or local         |
| Announce | Public tracker     | I2P router (private) |
| Metadata | Centralized        | DHT/magnet           |

## Key Features

- **DHT** - Distributed hash table for peer discovery without trackers
- **PEX** - Peer exchange from connected peers
- **Magnet links** - Share `.i2p` torrent links
- **Auto-scaling tunnels** - Tunnel count adjusts based on activity

## Packages

- `org.klomp.snark` - Torrent engine, peer coordination, storage, tracker, DHT
- `org.klomp.snark.web` - Web UI servlets

## Building

```bash
# Gradle
./gradlew :apps:i2psnark:jar

# Ant (from repo root)
ant buildI2PSnark
```

## Standalone Build

Creates a standalone ZIP with bundled Jetty that can run independently of the router:

```bash
ant i2psnark        # ZIP: dist/i2psnark-standalone.zip
ant i2psnark7zip    # 7z: dist/i2psnark-standalone.7z
ant i2psnark_nozip   # Directory: dist/i2psnark_standalone/
```
## Upstream & History

I2PSnark is an I2P port of [Snark](http://klomp.org/snark/), a GPLv2 BitTorrent
client written by Mark J. Wielaard (Copyright (C) 2003, `<mark@klomp.org>`),
originally released as the "Hunting of the Snark" project. The port adds a web
UI and support for multitorrent, magnet links, PEX and DHT, and is packaged as
a webapp running in the router console.

Protocol specifications for BitTorrent over I2P:
- http://i2p-projekt.i2p/en/docs/applications/bittorrent
- https://geti2p.net/en/docs/applications/bittorrent

See `docs/LICENSES.md` for license and attribution details.
