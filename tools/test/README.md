# tools/test/

Test dependencies for `ant test`, downloaded by `scripts/ensure-testdeps.sh`.

Jars are fetched from Maven Central on first use and cached here. They are
gitignored and not removed by `ant clean` or `ant distclean`.

## Directories

| Directory   | Contents                                                   |
| ----------- | ---------------------------------------------------------- |
| `hamcrest/` | Hamcrest assertion matchers (1.3)                          |
| `junit/`    | JUnit 4 test runner (4.13.2)                               |
| `mockito/`  | Mockito mocking framework (2.28.2)                         |
| `scala/`    | Scala 2.12.18 compiler, library, reflect + ScalaTest 3.0.1 |

## Files

| File                    | Purpose                                          |
| ----------------------- | ------------------------------------------------ |
| `test-report.css`       | Embedded stylesheet for `dist/test-report.html`  |
| `test-report.js`        | Embedded JavaScript for sorting/toggling/search  |
| `unit-tests-to-html.py` | Generate HTML report from JUnit XML results      |

## Usage

```
scripts/ensure-testdeps.sh           # download if missing
scripts/ensure-testdeps.sh --force   # re-download all
scripts/ensure-testdeps.sh --check   # verify without downloading
ant test                             # runs script automatically
ant testJbigi                        # benchmark native vs Java BigInteger
```

Running `ant test` auto-generates an HTML report at `dist/test-report.html` with per-module pass/fail counts, timing, failure traces, pass rate progress bars, sortable columns, test search filtering, and slowest-tests analysis.

## What the tests cover

- **Core** (`core/java`): Crypto (EdDSA, DSA), data structures (Hash, Certificate, SigningPrivateKey), streaming (GZIP, Zlib), utilities (ConvertToHash, DataHelper, ResettableGZIP)
- **MiniStreaming** (`apps/ministreaming/java`): Lightweight streaming library
- **Streaming** (`apps/streaming/java`): Full streaming library
