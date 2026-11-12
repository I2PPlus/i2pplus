# tools/test/

Test dependencies and test tooling for `ant test`.

The jars are fetched from Maven Central on first use and cached here by
`tools/scripts/ensure-testdeps.sh`. They are gitignored and are not removed by
`ant clean` or `ant distclean`.

## Directories

| Directory   | Contents                                                     |
| ----------- | ------------------------------------------------------------ |
| `hamcrest/` | Hamcrest assertion matchers (1.3)                            |
| `jmh/`      | JMH benchmark jars, fetched by `jmh/download-jmh.sh`         |
| `js/`       | JavaScript test harness — see *JS harness* below             |
| `junit/`    | JUnit 4 test runner (4.13.2)                                 |
| `mockito/`  | Mockito mocking framework (2.28.2)                           |
| `scala/`    | Scala 2.12.18 compiler, library, reflect + ScalaTest 3.0.1   |

## Files

| File                    | Purpose                                                             |
| ----------------------- | ------------------------------------------------------------------- |
| `test-report.css`       | Stylesheet inlined into `dist/test-report.html`                     |
| `test-report.js`        | Filter and column-sort behaviour, inlined into the same report      |
| `unit-tests-to-html.py` | Renders `dist/test-report.html` from the JUnit XML the suites write |

The generator reads `test-report.css` and `test-report.js` at generation time and
inlines both, so the report is one self-contained file that filters and sorts with
no network and no companion assets. `tools/test/js/dom/testReport.test.js` runs the
real generator over a synthetic JUnit tree and pins that the markup, the stylesheet
and the script all arrive — a generator that stops inlining one of them fails it.

## Usage

```
tools/scripts/ensure-testdeps.sh           # download if missing
tools/scripts/ensure-testdeps.sh --force   # re-download all
tools/scripts/ensure-testdeps.sh --check   # verify without downloading
ant test                                   # all suites, then the HTML report
ant test-js                                # the JS harness alone (requires node)
ant testJbigi                              # benchmark native vs Java BigInteger
```

`ant test` is `tools/scripts/run-tests.sh all`: 12 JUnit suites plus the JS
harness. A single suite is `tools/scripts/run-tests.sh <suite>` — `core`,
`ministreaming`, `streaming`, `router`, `i2ptunnel`, `addressbook`,
`routerconsole`, `i2psnark`, `susimail`, `sam`, `pack200`, `jrobin` or `js`. An
unknown name is an error rather than a silent no-op.

The report lands at `dist/test-report.html` with per-module pass/fail counts,
timing, failure traces, pass-rate progress bars and slowest-tests analysis. Its
filter box matches class or method name and every word typed has to match; a column
header sorts on the first click, reverses on the second and restores the original
order on the third.

## JS harness

`tools/test/js/` tests the webapp JavaScript without a browser.

```
cd tools/test/js && node helpers/run.mjs      # the whole harness
cd tools/test/js && node helpers/run.mjs dom/tablesort.test.js
ant test-js                                   # setup.mjs, then run.mjs
tools/scripts/run-tests.sh js                 # the same, echoing the totals
```

Requires node >= 20.6. `run.mjs` defaults to `suite.test.js` and prints one aligned
line per suite plus a single totals line — that totals line is the measurement, so
run it without `ant -q`, which swallows `<exec>` output whole.

| Path                | Contents                                                              |
| ------------------- | --------------------------------------------------------------------- |
| `suite.test.js`     | The one entry point; every suite is imported here                     |
| `pure/`             | Suites that need no DOM — the code under test is plain functions      |
| `dom/`              | Suites that need a DOM (linkedom) or a `vm` sandbox                   |
| `fixtures/`         | Saved page markup a suite parses, e.g. a real torrents list           |
| `helpers/`          | Runner, dependency bootstrap, browser shim, module resolution hook    |
| `package.json`      | Declares the harness ESM and the one dependency, linkedom             |

**Registering a suite.** Add an `import` line to `suite.test.js`. A test file nobody
imports produces no output at all — the totals line just reads lower — so
`pure/suiteCoverage.test.js` pins the import list against the directory listing in
both directions: a file with no import, and an import with no file.

**The smoke gate.** `dom/importAll.test.js` is the harness's whole-corpus coverage:
it walks every `.js` under `apps/` and imports each one in its own child process, so
a new file is covered automatically and anything that breaks parsing, module
resolution or import-time evaluation fails the build. Vendored bundles are excluded
in that file, each with its reason.

**The DOM.** linkedom is the only dependency, declared in `package.json` and
installed into `node_modules/` by `helpers/setup.mjs` with `npm ci` whenever the
tree is missing or older than the lockfile. If npm is unavailable or the install
fails — offline, no network — setup exits 0 with a notice and the suites that need
linkedom skip themselves, so an offline run stays green. Delete `node_modules/` to
force a reinstall.

**ES modules.** The repo root `package.json` declares `"type": "module"`, so any
`.js` file anywhere in the tree is an ES module. A CommonJS script using `require()`
does not fail a test — node refuses to load it at all, and a suite in that state
runs nothing. That is how two suites in `tools/scripts/tests/` went unexecuted for
as long as they existed.

**Two kinds of suite.** *Tier-1* tests are unit tests of decision helpers extracted
into plain functions, imported and called directly. *Tier-2* contract tests pin the
documented behaviour of the script that actually ships, usually by evaluating the
real source in a `vm` context or against a small DOM stub, so a change to the
shipped file — not to a test-local copy — is what fails.

## What the tests cover

`ant test` runs 12 JUnit suites plus the JS harness. The modules with substantial
JUnit coverage:

- **Core** (`core/java`): Crypto (EdDSA, DSA), data structures (Hash, Certificate, SigningPrivateKey), streaming (GZIP, Zlib), utilities (ConvertToHash, DataHelper, ResettableGZIP)
- **MiniStreaming** (`apps/ministreaming/java`): Lightweight streaming library
- **Streaming** (`apps/streaming/java`): Full streaming library
- **Router** (`router/java`): Job queue, tunnel build status, Tuner decision logic, stats coalescing, transport helpers
- **RouterConsole** (`apps/routerconsole/java`): Console helpers, tuning-parameter conformance
- **I2PSnark** (`apps/i2psnark/java`): Byte ranges, magnet handling, escaping and validation, browser API
- **Addressbook**, **I2PTunnel**, **Susimail**, **SAM**, **Pack200**, **JRobin**: module-level unit tests

The JS harness covers the console and webapp scripts: the refresh diff
(`vdomParser.js`, `diffWorker.js`), table sorting (`tablesort/`), the log viewer's
tagging and linkification, graph tooltips, the sidebar control state, morphdom,
progressx, jdenticon, the docdash navigation, and every script under `apps/` through
the smoke gate.
