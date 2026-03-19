# Ministreaming (`ministreaming/`)

API, interfaces, and factory for TCP-like (reliable, authenticated, in-order) sockets that communicate over I2P's unreliable messages. The implementation lives in `streaming/`.

## Packages

- `net.i2p.client.streaming` - `I2PSocketManager`, `I2PSocket`, `I2PServerSocket`

## Building

```bash
# Gradle
./gradlew :apps:ministreaming:jar

# Ant (from repo root)
ant buildMinistreaming
```
