# HTTP Proxy Error Pages (`proxy/`)

HTTP proxy error page templates with prepended HTTP headers. These are displayed to users when proxy requests fail.

## Format

All files must be DOS formatted with `\r\n` (CRLF) line endings for HTTP header compliance.

## Files

| File                         | Description                              |
| ---------------------------- | ---------------------------------------- |
| `ahelper-conflict-header.ht` | Address helper conflict                  |
| `ahelper-new-header.ht`      | Address helper new                       |
| `ahelper-notfound-header.ht` | Address helper not found                 |
| `auth-header.ht`             | Authentication required                  |
| `b32-auth-header.ht`         | Base32 address with authentication error |
| `b32-header.ht`              | Base32 address error                     |
| `baduri-header.ht`           | Bad URI request                          |
| `blacklist-header.ht`        | URL blocked by blacklist                 |
| `crossnet-header.ht`         | Cross-network sub-resource blocked       |
| `denied-header.ht`           | Access denied                            |
| `dnfb-header.ht`             | Destination not found (blocked)          |
| `dnf-header.ht`              | Destination not found                    |
| `dnfh-header.ht`             | Destination not found (hidden)           |
| `dnfp-header.ht`             | Destination not found (proxy)            |
| `enc-header.ht`              | Encoding error                           |
| `encp-header.ht`             | Encoding error (proxy)                   |
| `localhost-header.ht`        | Localhost request                        |
| `nols-header.ht`             | No LS (local service)                    |
| `nolsp-header.ht`            | No LS (proxy)                            |
| `noproxy-header.ht`          | No proxy                                 |
| `protocol-header.ht`         | Protocol error                           |
| `reset-header.ht`            | Connection reset                         |
| `resetp-header.ht`           | Connection reset (proxy)                 |

## Notes

- Files ending in `-p` typically denote proxy-specific variants
- Address helper (`ahelper-`) pages relate to I2P address resolution