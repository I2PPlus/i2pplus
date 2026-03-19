# Jetty Integration (`jetty/`)

Integration layer that embeds the Jetty web server, manages its lifecycle, configures connectors for the I2P network, and routes all I2P web applications (router console, i2ptunnel, i2psnark, etc.) through it.

## Packages

- `net.i2p.jetty` - Server bootstrap, I2P request logging, logger integration

## Building

```bash
# Gradle
./gradlew :apps:jetty:jar

# Ant (from repo root)
ant buildJetty
```
