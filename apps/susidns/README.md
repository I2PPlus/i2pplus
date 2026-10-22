# SusiDNS (`susidns/`)

Web-based address book and DNS service for .i2p domains.

## What It Does

SusiDNS provides the naming service that translates `.i2p` destinations:
- User-managed address book entries
- Subscription-based hosts.txt fetching
- Local .i2p domain resolution

## How It Works

1. **Local entries** - User-added destinations with base32 addresses
2. **Subscriptions** - Periodic fetch of remote hosts.txt files
3. **Resolution** - When accessing `.i2p`, looks up in local database

## Key Concepts

| Concept        | Description                               |
| -------------- | ----------------------------------------- |
| Destination    | I2P identity (like IP + port)             |
| Base32 address | SHA256 of destination, `.b32.i2p`         |
| hosts.txt      | Text file of `host.i2p=destination` lines |
| Subscription   | URL to fetch hosts.txt from               |

## Use Cases

- Manage personal contacts
- Share address book via HTTP
- Resolve custom `.i2p` domains

## Packages

- `i2p.susi.dns` - Addressbook, config, naming service, and subscription beans

## Building

```bash
# Gradle
./gradlew :apps:susidns:jar

# Ant (from repo root)
ant buildSusiDNS
```
