# I2PControl (`i2pcontrol/`)

JSON-RPC 2.0 API for external applications to control and monitor the I2P router.
Enables home automation dashboards, mobile apps, and scripts.

## Overview

I2PControl exposes router functionality over HTTP with JSON-RPC 2.0. The
webapp is staged as `jsonrpc.war` in `webapps/`, so it is served by the I2P
web server on port 7658 under the `/jsonrpc` context:

```
POST http://127.0.0.1:7658/jsonrpc/
{
  "method": "RouterInfo",
  "params": { "Token": "<token>" },
  "id": 1
}
```

## Available Methods

Only the methods below are registered (`JSONRPC2Servlet.init()`); every other
method name returns `METHOD_NOT_FOUND`.

| Method             | Purpose                                                      |
| ------------------ | ------------------------------------------------------------ |
| `Authenticate`     | Exchange `Password` and `API` for a `Token`                  |
| `Echo`             | Round-trip a value, useful as a health check                 |
| `GetRate`          | Read a rate stat by `Stat` and `Period`                      |
| `NetworkSetting`   | Read and change network settings such as bandwidth           |
| `RouterInfo`       | Read router status: netdb size, uptime, peers, version       |
| `RouterManager`    | Router lifecycle: shutdown, restart, reseed                  |
| `I2PControl`       | Change the I2PControl settings themselves, e.g. the password |
| `AdvancedSettings` | Read and change advanced router configuration                |

Every method except `Authenticate` takes a `Token` parameter obtained from
`Authenticate` (`JSONRPC2Helper.USE_AUTH`).

## Configuration

The webapp reads `i2pcontrol.config` from its own webapp directory; the router
plugin reads `I2PControl.conf` from the router directory
(`ConfigurationManager.java`). Requests carrying a `Host` header that is not
allowlisted are rejected with 403, so add the hostname you use:

```
i2pcontrol.allowedhosts=localhost
```

The default password is `itoopie`, the I2P console default. Change it with the
`I2PControl` method by sending `i2pcontrol.password`; the BCrypt hash is then
stored as `auth.password` in the configuration file.

## Use Cases

- **Monitoring dashboards** - Grafana, Home Assistant
- **Mobile apps** - I2P Android controllers
- **Automation** - Tunnel on/off based on schedule
- **Scripts** - Check router health, restart if needed

## Packages

- `net.i2p.i2pcontrol` - Controller, servlets, security, JSON-RPC handlers

## Building

```bash
# Gradle
./gradlew :apps:i2pcontrol:jar

# Ant (from repo root)
ant buildI2PControl
```
