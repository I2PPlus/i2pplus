# Address Book (`addressbook/`)

Fetches `hosts.txt` files from configured subscription URLs via HTTP and adds new entries to the local naming service database. Also packaged as a standalone JAR for Android.

## Packages

- `net.i2p.addressbook` - Subscription fetching, host parsing, and address book management

## Building

```bash
# Gradle
./gradlew :apps:addressbook:jar

# Ant (from repo root)
ant buildAddressbook
```
