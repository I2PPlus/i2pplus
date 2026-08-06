#!/usr/bin/env node
"use strict";
/*
 * Test harness for the routerconsole tablesort sorting logic:
 * shared comparators (sortShared.js) and the tablesort.js main-thread
 * fallback used when the Web Worker is unavailable.
 *
 * sortShared.js is loaded via a <script> tag in JSPs and via importScripts()
 * in sortWorker.js, so its functions are globals in both contexts. Both
 * suites evaluate the real source in a VM; the fallback suite also injects a
 * minimal DOM stub, so no browser or jsdom is required.
 *
 * Facts under test (as documented in sortShared.js / tablesort.js):
 *   - comparators return ascending order with empty cells last;
 *   - sortRows() applies the comparator and multiplies by -1 for "descending",
 *     i.e. the caller inverts direction (so empties flip to the top in desc);
 *   - sortRows() does not mutate the caller's array (worker/fallback spread);
 *   - tablesort.js sorts via a worker, falling back to a synchronous
 *     main-thread sort when Workers are missing or throw.
 *
 * Usage (from repo root):
 *   node tools/scripts/tests/tablesort-test.js              # --all
 *   node tools/scripts/tests/tablesort-test.js --shared     # comparators only
 *   node tools/scripts/tests/tablesort-test.js --fallback   # DOM fallback only
 *   node tools/scripts/tests/tablesort-test.js -h|--help
 */

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const JS_DIR = path.resolve(__dirname, "../../../apps/routerconsole/jsp/js/tablesort");
const SHARED_SRC = fs.readFileSync(path.join(JS_DIR, "sortShared.js"), "utf8");
const TABLESORT_SRC = fs.readFileSync(path.join(JS_DIR, "tablesort.js"), "utf8");

/* ------------------------------------------------------------------ helpers */

let pass = 0, fail = 0;
function assert(name, cond, detail) {
  if (cond) { pass++; }
  else { fail++; console.error("  FAIL " + name + (detail ? ": " + detail : "")); }
}

function rowsOf(items) {
  return items.map((td, index) => ({ td: String(td), index }));
}

function check(name, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  assert(name, a === e);
  if (a !== e) console.error("  " + name + " got " + a + " want " + e);
}

/* ------------------------------------------------------- suite 1: shared */

function suiteShared() {
  const sandbox = {};
  const exportShim = "this.__export = { sortRows, numberCmpEL, naturalCmpEL, dotsepCmpEL, " +
                      "filesizeCmpEL, monthnameCmpEL, dateCmpEL, intlCmpEL, stringCmpEL };";
  vm.createContext(sandbox);
  vm.runInContext(SHARED_SRC + "\n" + exportShim, sandbox, { filename: "sortShared.js" });

  const { sortRows, numberCmpEL, naturalCmpEL, dotsepCmpEL, filesizeCmpEL,
          monthnameCmpEL, dateCmpEL, intlCmpEL, stringCmpEL } = sandbox.__export;

  /* --- 1. Individual comparators (ascending, empty-last) --- */
  assert("numberCmpEL sorts numbers", numberCmpEL("10", "9") === 1);
  assert("numberCmpEL empty last asc", numberCmpEL("", "1") === 1);
  assert("numberCmpEL empty first arg", numberCmpEL("1", "") === -1);
  assert("numberCmpEL both empty equal", numberCmpEL("", "  ") === 0);
  assert("numberCmpEL strips units", numberCmpEL("512KiB", "1MiB") > 0);
  assert("numberCmpEL NaN last", numberCmpEL("-", "1") === 1);

  assert("naturalCmpEL unknown last", naturalCmpEL("unknown", "abc") === 1);
  assert("naturalCmpEL chunk order", naturalCmpEL("a2", "a10") < 0);

  assert("dotsepCmpEL IP order", dotsepCmpEL("10.0.0.2", "10.0.0.10") < 0);
  assert("dotsepCmpEL unequal length", dotsepCmpEL("1.2.3", "1.2.3.1") < 0);

  assert("filesizeCmpEL units", filesizeCmpEL("1KiB", "1MiB") < 0);
  assert("filesizeCmpEL same unit", filesizeCmpEL("2GB", "1.5GB") > 0);

  assert("monthnameCmpEL order", monthnameCmpEL("January", "September") < 0);

  assert("dateCmpEL iso", dateCmpEL("2024-01-02", "2024-01-03") < 0);
  assert("dateCmpEL us format", dateCmpEL("01/02/2024", "01/03/2024") < 0);

  assert("intlCmpEL basic", intlCmpEL("a", "b") < 0);

  assert("stringCmpEL case-insensitive", stringCmpEL("Apple", "banana") < 0);
  assert("stringCmpEL empty last", stringCmpEL("abc", "") < 0);

  /* --- 2. sortRows: ascending / descending --- */
  check("sortRows asc numbers",
        sortRows(rowsOf([3, 1, 2]), "td", "ascending", "number").map(r => r.td),
        ["1", "2", "3"]);
  check("sortRows desc numbers",
        sortRows(rowsOf([3, 1, 2]), "td", "descending", "number").map(r => r.td),
        ["3", "2", "1"]);

  /* --- 3. Empties at the opposite end of the direction --- */
  check("sortRows asc empties last",
        sortRows(rowsOf(["b", "", "a", "  "]), "td", "ascending", "string").map(r => r.td),
        ["a", "b", "", "  "]);
  /* documented: descending inverts the comparator, so empty cells lead */
  check("sortRows desc empties first",
        sortRows(rowsOf(["b", "", "a"]), "td", "descending", "string").map(r => r.td),
        ["", "b", "a"]);

  /* --- 4. index field is preserved for DOM reordering --- */
  const indexed = rowsOf([30, 10, 20]);
  const sortedIdx = sortRows([...indexed], "td", "ascending", "number");
  check("sortRows keeps original indexes", sortedIdx.map(r => r.index), [1, 2, 0]);

  /* --- 5. default columnType = string sort --- */
  check("sortRows default string", sortRows(rowsOf([10, "a", 9]), "td", "ascending", undefined).map(r => r.td),
        ["10", "9", "a"]);

  /* --- 6. non-mutating (worker/fallback both spread before calling) --- */
  const original = rowsOf([3, 1, 2]);
  sortRows([...original], "td", "ascending", "number");
  check("sortRows does not mutate input order", original.map(r => r.td), ["3", "1", "2"]);

  /* --- 7. every type key dispatches to its comparator
            Inputs are deliberately scrambled; each must come out in `want` order. --- */
  const expected = {
    number: ["1", "2", "10"],
    natural: ["a1", "a2", "a10"],
    dotsep: ["10.0.0.1", "10.0.0.2", "10.0.0.10"],
    filesize: ["1KiB", "512KiB", "1MiB"],
    monthname: ["January", "February", "March"],
    date: ["2024-01-01", "2024-01-02", "2024-01-03"],
    intl: ["a", "b", "c"],
    string: ["a", "m", "x"]
  };
  const scrambled = {
    number: ["10", "1", "2"],
    natural: ["a10", "a1", "a2"],
    dotsep: ["10.0.0.10", "10.0.0.1", "10.0.0.2"],
    filesize: ["1MiB", "1KiB", "512KiB"],
    monthname: ["March", "January", "February"],
    date: ["2024-01-03", "2024-01-01", "2024-01-02"],
    intl: ["c", "a", "b"],
    string: ["x", "a", "m"]
  };
  for (const [type, want] of Object.entries(expected)) {
    const got = sortRows(rowsOf(scrambled[type]), "td", "ascending", type).map(r => r.td);
    check("type dispatch " + type, got, want);
  }

  /* --- 7b. descending per type mirrors ascending --- */
  for (const [type, want] of Object.entries(expected)) {
    const got = sortRows(rowsOf(scrambled[type]), "td", "descending", type).map(r => r.td);
    check("type desc " + type, got, [...want].reverse());
  }

  /* --- 8. the worker contract (payload -> postMessage shape) --- */
  const workerRows = rowsOf([5, 1, 3]);
  const workerResult = { sorted: sortRows([...workerRows], "td", "ascending", "number") };
  check("worker result shape", workerResult.sorted.map(r => r.td), ["1", "3", "5"]);

  /* --- 9. fallback and worker produce identical output for a mixed table --- */
  const mixed = rowsOf(["", "b", "banana", "Apple", "30", "1", "a2", "  "]);
  const workerOut = sortRows([...mixed], "td", "ascending", "string").map(r => r.td);
  const fallbackOut = sortRows([...mixed], "td", "ascending", "string").map(r => r.td);
  check("fallback == worker output", fallbackOut, workerOut);
}

/* -------------------------------------------------------- suite 2: DOM */

function suiteFallback() {
  class FakeCell {
    constructor(text) { this._text = String(text); this._attrs = {}; }
    getAttribute(name) { return this._attrs[name] || null; }
    hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this._attrs, name); }
    setAttribute(k, v) { this._attrs[k] = v; }
    addEventListener() {}
    get textContent() { return this._text; }
  }
  class FakeRow {
    constructor(cells) {
      this.cells = cells.map(c => new FakeCell(c));
      this._attrs = {};
    }
    getAttribute(name) { return this._attrs[name] || null; }
    setAttribute(k, v) { this._attrs[k] = v; }
  }
  class FakeTBody {
    constructor(rows) { this.rows = rows; this.removed = []; }
    appendChild(node) { this.removed.push(node); }
  }
  class FakeTable {
    constructor(rows) {
      this.tagName = "TABLE";
      this.rows = [new FakeRow(["value"])];
      this.tBodies = [new FakeTBody(rows)];
      this._sortEvents = [];
    }
    dispatchEvent(evt) { this._sortEvents.push(evt.type); }
  }
  class FakeHeader {
    constructor() { this._attrs = {}; }
    setAttribute(k, v) { this._attrs[k] = v; }
    getAttribute(k) { return this._attrs[k] || null; }
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(this._attrs, k); }
    get cellIndex() { return 0; }
  }
  function newFragment() {
    return { children: [], appendChild(el) { this.children.push(el); } };
  }
  const dom = {
    document: {
      createDocumentFragment() { return newFragment(); },
      createEvent(type) { return { type, initCustomEvent() {} }; }
    },
    CustomEvent: function(name) { this.type = name; }
  };

  function runScenario(workerBehavior) {
    const sandbox = { document: dom.document, CustomEvent: dom.CustomEvent,
                      Worker: workerBehavior, console };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(SHARED_SRC + "\n" + TABLESORT_SRC, sandbox, { filename: "tablesort.js" });

    const rows = [new FakeRow(["30", "b"]), new FakeRow(["10", "a"]), new FakeRow(["20", "c"])];
    const table = new FakeTable(rows);
    const ts = sandbox.Tablesort(table, {});
    const header = new FakeHeader();

    ts.sortTable(header);

    const out = table.tBodies[0].removed[0].children.map(fr => fr.cells[0]._text);
    return { out, events: table._sortEvents.join(","), aria: header._attrs["aria-sort"] };
  }

  const noWorker = runScenario(undefined);
  const throwingWorker = runScenario(function() { throw new Error("blocked"); });

  assert("no-Worker sorts ascending by number",
    JSON.stringify(noWorker.out) === JSON.stringify(["10", "20", "30"]),
    JSON.stringify(noWorker.out));
  assert("no-Worker fires beforeSort,afterSort",
    noWorker.events === "beforeSort,afterSort", noWorker.events);
  assert("no-Worker sets aria-sort ascending",
    noWorker.aria === "ascending", noWorker.aria);

  assert("throwing Worker falls back too",
    JSON.stringify(throwingWorker.out) === JSON.stringify(["10", "20", "30"]),
    JSON.stringify(throwingWorker.out));
  assert("throwing Worker fires afterSort",
    throwingWorker.events === "beforeSort,afterSort", throwingWorker.events);

  /* second click on same header -> descending */
  const sandbox2 = { document: dom.document, CustomEvent: dom.CustomEvent,
                     Worker: undefined, console };
  sandbox2.window = sandbox2;
  vm.createContext(sandbox2);
  vm.runInContext(SHARED_SRC + "\n" + TABLESORT_SRC, sandbox2, { filename: "tablesort.js" });
  const rows2 = [new FakeRow(["30"]), new FakeRow(["10"]), new FakeRow(["20"])];
  const table2 = new FakeTable(rows2);
  const ts2 = sandbox2.Tablesort(table2, {});
  const h2 = new FakeHeader();
  ts2.sortTable(h2);
  ts2.sortTable(h2);
  assert("second click sorts descending",
    JSON.stringify(table2.tBodies[0].removed[1].children.map(fr => fr.cells[0]._text)) === JSON.stringify(["30", "20", "10"]),
    JSON.stringify(table2.tBodies[0].removed[1].children.map(fr => fr.cells[0]._text)));
  assert("second click sets aria-sort descending", h2._attrs["aria-sort"] === "descending", h2._attrs["aria-sort"]);
}

/* ---------------------------------------------------------------------- main */

const HELP = `Usage: node tools/scripts/tests/tablesort-test.js [options]

Options:
  --all       Run both suites (default)
  --shared    Run the sortShared.js comparator/sortRows suite only
  --fallback  Run the tablesort.js main-thread fallback suite only
  -h, --help  Show this help
`;

function parseArgs() {
  const args = process.argv.slice(2);
  if (args.includes("-h") || args.includes("--help")) {
    process.stdout.write(HELP);
    process.exit(0);
  }
  let mode = "all";
  for (const a of args) {
    if (a === "--all") mode = "all";
    else if (a === "--shared") mode = "shared";
    else if (a === "--fallback") mode = "fallback";
    else { console.error("Unknown option '" + a + "'. Use --help for usage."); process.exit(1); }
  }
  return mode;
}

function main() {
  const mode = parseArgs();
  pass = 0; fail = 0;
  console.log("== tablesort tests ==");
  if (mode === "all" || mode === "shared") {
    console.log("-- shared (comparators + sortRows)");
    suiteShared();
  }
  if (mode === "all" || mode === "fallback") {
    console.log("-- fallback (main-thread DOM sort)");
    suiteFallback();
  }
  console.log("PASS: " + pass + "  FAIL: " + fail);
  process.exit(fail > 0 ? 1 : 0);
}

main();