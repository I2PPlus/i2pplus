# Streaming (`streaming/`)

Implementation of the TCP-like streaming protocol. Handles connection management, packet sequencing, retransmission, and congestion control. The API is in `ministreaming/`.

## Packages

- `net.i2p.client.streaming.impl` - Connection, ConnectionManager, PacketHandler, schedulers

## Building

```bash
# Gradle
./gradlew :apps:streaming:jar

# Ant (from repo root)
ant buildStreaming
```
