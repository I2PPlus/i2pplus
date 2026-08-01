# Naming Service (`client/naming`)

Naming service for resolving human-readable hostnames to I2P destinations — a standard interface without JNDI complexity.

## How It Works

The default implementation (`HostsTxtNamingService`) is driven by `hosts.txt`; alternatives are pluggable via the `i2p.naming.impl` property. `EepGetNamingService` and `EepGetAndAddNamingService` resolve names on demand over I2P and add results to the addressbook; `MetaNamingService` combines multiple backends; `NamingServiceUpdater` refreshes the addressbook from subscriptions.

## Key Classes

| Class                          | Purpose                                                   |
| ------------------------------ | --------------------------------------------------------- |
| `NamingService`                | Hostname-to-destination resolution interface              |
| `HostsTxtNamingService`        | Default `hosts.txt`-backed implementation                 |
| `MetaNamingService`            | Multi-backend lookup, falling back across services        |
| `EepGetNamingService`          | On-demand resolution over I2P                             |
| `EepGetAndAddNamingService`    | On-demand resolution that adds results to the addressbook |
| `NamingServiceUpdater`         | Refreshes the addressbook from subscriptions              |
| `SingleFileNamingService`      | Single-file (import/export) naming service                |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
