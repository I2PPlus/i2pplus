# scripts/tests/

Validation and testing scripts for I2P+ builds.

## Certificate checks

- **`checkcerts.sh`** — Validate all bundled `.crt` certificate files. Checks expiration, key size, and signature type. Requires `openssl` or `certtool`.

- **`checkremotecerts.sh`** — Connect to I2P reseed hosts over HTTPS and validate their SSL certificates against bundled certs and fingerprints. Requires `gnutls-cli` or `openssl`. Detects Tor and uses `torsocks` if available.

## Source checks

- **`checkpo.sh`** — Run `msgfmt --check-format` on all `.po` translation files.

- **`checkscripts.sh`** — Run `sh -n` syntax check on all project shell scripts. Optionally checks for bashisms with `checkbashisms`.

- **`checkutf8.sh`** — Validate UTF-8 encoding across locale files, Java, and Scala sources. Also checks that getopt properties files use ISO-8859-1.

- **`checkxml.sh`** — Validate XML and HTML files using `xmllint`. Note: HTML mode does not return error codes; review output for errors.

## Performance

- **`benchmark.sh`** — Run JMH benchmarks on I2P+. Optionally loads the jbigi native library. Pass `-h` to see JMH options.

- **`testjbigi.sh`** — Test each `libjbigi-linux-*.so` native library by symlinking and running CPUID/NativeBigInteger checks. Requires a built `i2p.jar`.
