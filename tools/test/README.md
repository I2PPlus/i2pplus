# tools/test/

Test dependencies for `ant test`, downloaded by `tools/scripts/ensure-testdeps.sh`.

Jars are fetched from Maven Central on first use and cached here. They are
gitignored and not removed by `ant clean` or `ant distclean`.

## Directories

| Directory   | Contents                                                    |
| ----------- | ----------------------------------------------------------- |
| `hamcrest/` | Hamcrest assertion matchers (1.3)                           |
| `jmh/`      | JMH benchmark jars, fetched by `jmh/download-jmh.sh`        |
| `js/`       | JavaScript test harness (DOM shims, fixtures, setup script) |
| `junit/`    | JUnit 4 test runner (4.13.2)                                |
| `mockito/`  | Mockito mocking framework (2.28.2)                          |
| `scala/`    | Scala 2.12.18 compiler, library, reflect + ScalaTest 3.0.1  |

## Files

| File                    | Purpose                                          |
| ----------------------- | ------------------------------------------------ |
| `test-report.css`       | Embedded stylesheet for `dist/test-report.html`  |
| `unit-tests-to-html.py` | Generate HTML report from JUnit XML results      |

## Usage

```
tools/scripts/ensure-testdeps.sh           # download if missing
tools/scripts/ensure-testdeps.sh --force   # re-download all
tools/scripts/ensure-testdeps.sh --check   # verify without downloading
ant test                                   # runs script automatically
ant testJbigi                              # benchmark native vs Java BigInteger
```

Running `ant test` auto-generates an HTML report at `dist/test-report.html` with
per-module pass/fail counts, timing, failure traces, pass-rate progress bars and
slowest-tests analysis. The generator also inlines `test-report.js` for sorting
and search if that file is present; it is not shipped, so the report is static.

## What the tests cover

`ant test` runs 12 suites. The modules with substantial JUnit coverage:

- **Core** (`core/java`): Crypto (EdDSA, DSA), data structures (Hash, Certificate, SigningPrivateKey), streaming (GZIP, Zlib), utilities (ConvertToHash, DataHelper, ResettableGZIP)
- **MiniStreaming** (`apps/ministreaming/java`): Lightweight streaming library
- **Streaming** (`apps/streaming/java`): Full streaming library
- **Router** (`router/java`): Job queue, tunnel build status, Tuner decision logic, stats coalescing, transport helpers
- **RouterConsole** (`apps/routerconsole/java`): Console helpers, tuning-parameter conformance
- **I2PSnark** (`apps/i2psnark/java`): Byte ranges, magnet handling, escaping and validation, browser API
- **Addressbook**, **I2PTunnel**, **Susimail**, **SAM**, **Pack200**, **JRobin**: module-level unit tests
