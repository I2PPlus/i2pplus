# Reseed (`networkdb/reseed`)

Reseeding: bootstrapping a router into the network by discovering initial peers. Essential for new installations and routers that have lost their network references.

## How It Works

`Reseeder` fetches RouterInfos from multiple sources — cleartext HTTP/HTTPS reseed servers (SU3 or ZIP), web indexes, and local files for offline reseeding. `ReseedChecker` validates the fetched data (signature and integrity of SU3/ZIP files) before the routers are added to the network database. Reseeding runs automatically on first install and when the peer count drops below minimum, and can be triggered manually from the console.

## Key Classes

| Class            | Purpose                                                     |
| ---------------- | ----------------------------------------------------------- |
| `Reseeder`       | Coordinates reseeding: fetch, validate, and install peers   |
| `ReseedChecker`  | Validates reseed data integrity and signatures              |
| `ReseedBundler`  | Bundles and packages reseed data                            |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/router/networkdb/README.md`](../README.md) — networkdb coordination package
