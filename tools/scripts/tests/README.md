# tools/scripts/tests/

Validation and testing scripts for I2P+ builds.

## Unit tests

| Command                           | Description                                                    |
| --------------------------------- | -------------------------------------------------------------- |
| `ant test`                        | Run all unit tests (12 suites, see below)                      |
| `ant testJbigi`                   | Benchmark native vs pure Java BigInteger performance           |
| `tools/scripts/run-tests.sh`      | The test entry point — dep downloads, build order, HTML report |
| `tools/scripts/run-tests.sh core` | One suite only (`router`, `js`, … — see `--help`)              |

`run-tests.sh` with no suite argument runs everything: the 12 JUnit suites — Core,
MiniStreaming, Streaming, Router, I2PTunnel, Addressbook, RouterConsole, I2PSnark,
Susimail, SAM, Pack200 and JRobin — then the node JS harness. Each name above is also
a suite argument; an unknown name is an error, not a full run.

`ant test` auto-downloads test dependencies (JUnit, Hamcrest, Mockito, Scala) to `tools/test/` on first run via `tools/scripts/ensure-testdeps.sh`. The build compiles to Java 8 bytecode, so any newer JDK can run Ant; the extra prerequisite for the wrapper is `python3` with `markdown-it-py`, used to render the HTML report. The test JVM ant forks is Java 8, so a test may not use APIs or providers newer than that.

An HTML report is generated at `dist/test-report.html` after each run. Report includes per-module pass/fail counts, timing, and failure traces.

An HTML report is generated at `dist/test-report.html` after each run. Report includes per-module pass/fail counts, timing, and failure traces.

## Certificate checks

- **`checkcerts.sh`** — Validate all bundled `.crt` certificate files. Checks expiration, key size, and signature type. Requires `openssl` or `certtool`.

- **`checkremotecerts.sh`** — Connect to I2P reseed hosts over HTTPS and validate their SSL certificates against bundled certs and fingerprints. Requires `gnutls-cli` or `openssl`. Detects Tor and uses `torsocks` if available.

## Source checks

- **`checkpo.sh`** — Run `msgfmt --check-format` on all `.po` translation files.

- **`checkscripts.sh`** — Run `sh -n` syntax check on all project shell scripts. Optionally checks for bashisms with `checkbashisms`.

- **`checkutf8.sh`** — Validate UTF-8 encoding across locale files, Java, and Scala sources. Also checks that getopt properties files use ISO-8859-1.

- **`checkxml.sh`** — Validate XML and HTML files using `xmllint`. Note: HTML mode does not return error codes; review output for errors.

## Container checks

- **`checkdockerconfig.sh`** — Exercise the config-override helpers in `docker/rootfs/startapp.sh` against a fake install in a temp directory. Pins that `EXTERNAL_PORT` and `IP_ADDR` reach the config directory the router reads, not only the base-directory template the router copies on the first start of an empty volume, that a key the file lacks is appended, that a missing file is reported and not created, and that the script stays Bourne-compatible for busybox ash.

## Performance

- **`benchmark.sh`** — Run JMH benchmarks on I2P+. Optionally loads the jbigi native library. Pass `-h` to see JMH options.

- **`testjbigi.sh`** — Test each `libjbigi-linux-*.so` native library by symlinking and running CPUID/NativeBigInteger checks. Requires a built `i2p.jar`.

- **`ant testJbigi`** — Benchmark `NativeBigInteger` (JNI/GMP) vs `BigInteger` (pure Java) at 256-bit (X25519) and 2048-bit (ElGamal) sizes. Tests modPow, modPowCT, and modInverse.
