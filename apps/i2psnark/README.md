# I2PSnark (`i2psnark/`)

I2P-enhanced BitTorrent client with web UI, DHT, magnet links, PEX, and swarm management for anonymous file sharing over I2P. Fork of Snark.

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
