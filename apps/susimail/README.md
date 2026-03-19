# SusiMail (`susimail/`)

Webmail interface with POP3/SMTP client protocols and local email caching, designed for privacy-focused communication over I2P.

## Packages

- `i2p.susi.webmail` - Mail cache, message handling, web UI
- `i2p.susi.webmail.pop3` - POP3 client
- `i2p.susi.webmail.smtp` - SMTP client

## Building

```bash
# Gradle
./gradlew :apps:susimail:jar

# Ant (from repo root)
ant buildSusiMail
```
