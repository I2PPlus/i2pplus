# Address Book (`addressbook/`)

Subscription-based destination management. Fetches hosts.txt files and updates the local naming service.

## What It Does

- Fetch remote hosts.txt files via HTTP
- Parse and validate destinations
- Update local address book with new entries
- Ping destinations and report which ones answer

## How It Works

1. Configure subscription URLs (e.g., `http://example.i2p/hosts.txt`)
2. Periodic fetch (configurable interval)
3. Parse destinations from file
4. Add to local database

## hosts.txt Format

```
# Comments start with #
b32addr.b32.i2p=Base64Destination
```

## Packages

- `net.i2p.addressbook` - Subscription fetching, host parsing, and address book management
- `net.i2p.router.naming` - `BlockfileNamingService`, the local address book store
- `net.metanotion.io` - Vendored random-access file layer used by the store

## Building

```bash
# Gradle
./gradlew :apps:addressbook:jar

# Ant (from repo root)
ant buildAddressbook
```
