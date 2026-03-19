# SAM Bridge (`sam/`)

SAM (Simple Anonymous Messaging) bridge allowing applications written in any language to communicate over I2P via a simple TCP-based protocol.

## Packages

- `net.i2p.sam` - SAM bridge, v3 handler, stream and datagram sessions

## Building

```bash
# Gradle
./gradlew :apps:sam:jar

# Ant (from repo root)
ant buildSAM
```
