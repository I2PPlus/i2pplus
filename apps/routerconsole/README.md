# Router Console (`routerconsole/`)

Web-based control panel for monitoring and configuring the I2P router. Provides the main user interface for I2P+.

## Packages

- `net.i2p.router.web` - Console runner, servlets, form handlers
- `net.i2p.router.web.helpers` - Status pages, configuration helpers, summary rendering

## Building

```bash
# Gradle
./gradlew :apps:routerconsole:jar

# Ant (from repo root)
ant buildRouterConsole
```
