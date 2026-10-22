# Jetty Integration (`jetty/`)

Embedded Jetty web server for I2P web applications.

## What It Does

- Starts Jetty servlet container
- Configures HTTP listeners on I2P ports
- Routes requests to webapps (console, i2psnark, etc.)
- Manages SSL for I2P HTTPS

## Structure

| Class                         | Purpose                                          |
| ----------------------------- | ------------------------------------------------ |
| `JettyStart`                  | Client app that boots the Jetty server           |
| `JettyXmlConfigurationParser` | Applies the `jetty.xml` configuration            |
| `I2PRequestLog`               | I2P request logging                              |
| `I2PLogger`                   | Bridges Jetty logging to the I2P log manager     |

## Default Ports

Jetty serves the router console and the web server. The HTTP proxy, I2PSnark
and the other client-facing ports belong to I2PTunnel, not to Jetty.

| Port   | Application                                     |
| ------ | ----------------------------------------------- |
| 7657   | Router console (http://)                        |
| 7658   | I2P web server (webapps, eepsite)               |
| 7667   | Router console (https://, disabled by default)  |
| 4444   | HTTP proxy — I2PTunnel, not served by Jetty     |

## Packages

- `net.i2p.jetty` - Server bootstrap, I2P request logging, logger integration
- `net.i2p.servlet` - Servlet and filter support: `I2PDefaultServlet`,
  `ErrorServlet`, `RequestWrapper`, XSS and location filters

## Building

```bash
# Gradle
./gradlew :apps:jetty:jar

# Ant (from repo root)
ant buildJetty
```
