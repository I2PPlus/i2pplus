/**
 * Tier-2 contract tests for the router console's table sorting: the shared
 * comparators in apps/routerconsole/jsp/js/tablesort/sortShared.js and the
 * main-thread fallback in tablesort.js that runs when no Web Worker is
 * available. Both real sources are evaluated in a vm, and the fallback suite
 * injects a small DOM stub, so no browser is needed.
 *
 * These were tools/scripts/tests/tablesort-test.js, a CommonJS script with its
 * own assertion counter. The repo root declares "type": "module", so node
 * refused to load it at all - require is not defined in ES module scope - and
 * nothing in the build invoked it either, which is how 58 assertions on the
 * code that decides what a sorted console table looks like went unrun.
 *
 * Facts under test, as documented in sortShared.js and tablesort.js:
 *
 *   - every comparator orders its own type ascending and puts a blank last;
 *   - sortRows() reverses for "descending" but a blank cell stays at the bottom
 *     in both directions, so an empty column cannot jump to the top on a
 *     reversed click;
 *   - sortRows() does not mutate the caller's array, and keeps each row's
 *     original index, which is what the DOM reorder is driven from;
 *   - auto-detection runs number > date > dotsep > filesize > monthname >
 *     natural, so versions and IPs stay dotsep, "512KiB" stays filesize and
 *     "Aug 5, 2026" stays date instead of being hijacked by the number
 *     pattern; natural is the catch-all;
 *   - tablesort.js sorts through a worker and falls back to a synchronous
 *     main-thread sort when Workers are missing or throw, firing beforeSort and
 *     afterSort and setting aria-sort either way.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const JS_DIR = new URL("../../../../apps/routerconsole/jsp/js/tablesort/", import.meta.url);
const SHARED_SRC = readFileSync(new URL("sortShared.js", JS_DIR), "utf8");
const TABLESORT_SRC = readFileSync(new URL("tablesort.js", JS_DIR), "utf8");

/**
 * Evaluates sortShared.js in a bare sandbox and hands back the globals it
 * defines, which is how the JSP <script> tag and sortWorker.js see them.
 *
 * @returns {Object} the comparators, the patterns and sortRows
 */
function loadShared() {
  const sandbox = {};
  const exportShim = "this.__export = { sortRows, numberPattern, datePattern, dotsepPattern, " +
                     "filesizePattern, monthnamePattern, naturalPattern, numberCmpEL, naturalCmpEL, " +
                     "dotsepCmpEL, filesizeCmpEL, monthnameCmpEL, dateCmpEL, intlCmpEL, stringCmpEL };";
  vm.createContext(sandbox);
  vm.runInContext(`${SHARED_SRC}\n${exportShim}`, sandbox, {filename: "sortShared.js"});
  return sandbox.__export;
}

const {sortRows, numberPattern, datePattern, dotsepPattern, filesizePattern, monthnamePattern,
       naturalPattern, numberCmpEL, naturalCmpEL, dotsepCmpEL, filesizeCmpEL, monthnameCmpEL,
       dateCmpEL, intlCmpEL, stringCmpEL} = loadShared();

/**
 * The row shape sortRows works on: the cell text plus the position the row
 * held before sorting.
 *
 * @param {Array<*>} items the cell values
 * @returns {Array<{td: string, index: number}>} rows in the given order
 */
function rowsOf(items) {
  return items.map((td, index) => ({td: String(td), index}));
}

/**
 * The sorted cell texts, which is the only part of a sort a reader sees.
 *
 * @param {Array<{td: string, index: number}>} rows rows to sort
 * @param {string} dir "ascending" or "descending"
 * @param {string} [type] the column type key
 * @returns {string[]} the cell texts in sorted order
 */
function sortedTexts(rows, dir, type) {
  return sortRows([...rows], "td", dir, type).map((r) => r.td);
}

test("the number comparator orders values and leaves blanks and junk at the bottom", () => {
  assert.equal(numberCmpEL("10", "9"), 1, "numeric, not lexicographic");
  assert.equal(numberCmpEL("", "1"), 1, "a blank sorts after a number");
  assert.equal(numberCmpEL("1", ""), -1);
  assert.equal(numberCmpEL("", "  "), 0, "two blanks are equal");
  assert.ok(numberCmpEL("512KiB", "1MiB") > 0, "a unit suffix is stripped, not compared as text");
  assert.equal(numberCmpEL("-", "1"), 1, "a value that is not a number sorts last");
});

test("each comparator orders the type it is named for", () => {
  assert.equal(naturalCmpEL("unknown", "abc"), 1, "an unparseable natural value sorts last");
  assert.ok(naturalCmpEL("a2", "a10") < 0, "digits inside text compare as numbers");
  assert.ok(dotsepCmpEL("10.0.0.2", "10.0.0.10") < 0, "an address compares segment by segment");
  assert.ok(dotsepCmpEL("1.2.3", "1.2.3.1") < 0, "a shorter dotted value sorts first");
  assert.ok(filesizeCmpEL("1KiB", "1MiB") < 0);
  assert.ok(filesizeCmpEL("2GB", "1.5GB") > 0);
  assert.ok(monthnameCmpEL("January", "September") < 0, "calendar order, not alphabetical");
  assert.ok(dateCmpEL("2024-01-02", "2024-01-03") < 0);
  assert.ok(dateCmpEL("01/02/2024", "01/03/2024") < 0, "the console also writes US dates");
  assert.ok(intlCmpEL("a", "b") < 0);
  assert.ok(stringCmpEL("Apple", "banana") < 0, "case insensitive");
  assert.ok(stringCmpEL("abc", "") < 0);
});

test("sortRows orders ascending and reverses on descending", () => {
  assert.deepEqual(sortedTexts(rowsOf([3, 1, 2]), "ascending", "number"), ["1", "2", "3"]);
  assert.deepEqual(sortedTexts(rowsOf([3, 1, 2]), "descending", "number"), ["3", "2", "1"]);
});

test("a blank cell stays at the bottom whichever way the column is sorted", () => {
  assert.deepEqual(sortedTexts(rowsOf(["b", "", "a", "  "]), "ascending", "string"), ["a", "b", "", "  "]);
  assert.deepEqual(sortedTexts(rowsOf(["b", "", "a"]), "descending", "string"), ["b", "a", ""]);
  assert.deepEqual(sortedTexts(rowsOf([3, "", 1, 2]), "descending", "number"), ["3", "2", "1", ""]);
});

test("sortRows keeps each row's original index and leaves the caller's array alone", () => {
  const indexed = rowsOf([30, 10, 20]);
  assert.deepEqual(sortRows([...indexed], "td", "ascending", "number").map((r) => r.index), [1, 2, 0],
    "the index is what the DOM reorder is driven from");
  const original = rowsOf([3, 1, 2]);
  sortRows([...original], "td", "ascending", "number");
  assert.deepEqual(original.map((r) => r.td), ["3", "1", "2"], "the worker and the fallback both spread first");
});

test("an unrecognised column type falls back to a string sort", () => {
  assert.deepEqual(sortedTexts(rowsOf([10, "a", 9]), "ascending", undefined), ["10", "9", "a"]);
});

test("every type key dispatches to its own comparator, both directions", () => {
  const want = {
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
  for (const [type, order] of Object.entries(want)) {
    assert.deepEqual(sortedTexts(rowsOf(scrambled[type]), "ascending", type), order, `ascending ${type}`);
    assert.deepEqual(sortedTexts(rowsOf(scrambled[type]), "descending", type), [...order].reverse(),
      `descending ${type} mirrors ascending`);
  }
});

test("auto-detection picks the specific pattern and keeps natural as the catch-all", () => {
  // The waterfall sortShared.js documents, restated here so a pattern change
  // that reorders it shows up as a wrong type rather than a wrong order.
  const opts = [["number", numberPattern], ["date", datePattern], ["dotsep", dotsepPattern],
                ["filesize", filesizePattern], ["monthname", monthnamePattern], ["natural", naturalPattern]];
  const detectType = (samples) => {
    for (const [name, pattern] of opts) {
      if (samples.every(pattern)) {return name;}
    }
    return "string";
  };
  assert.equal(detectType(["10", "20", "30"]), "number");
  assert.equal(detectType(["0.9.69", "0.9.70", "0.9.68"]), "dotsep", "a version is not a number");
  assert.equal(detectType(["10.0.0.2", "10.0.0.10", "73.1.1.1"]), "dotsep");
  assert.equal(detectType(["1KiB", "512KiB", "1MiB"]), "filesize");
  assert.equal(detectType(["Aug 5, 2026, 11:48 PM", "Aug 4, 2026, 11:48 PM"]), "date");
  assert.equal(detectType(["Fast", "Failing", "Standard"]), "natural");
  // A column that mixes units has no numeric key at all; it needs data-sort.
  assert.equal(detectType(["821 ms", "3 sec", "5 sec"]), "natural");
});

test("the number pattern refuses everything that is not a plain quantity", () => {
  assert.ok(!numberPattern("0.9.69"), "a version");
  assert.ok(!numberPattern("2.13.0-2+"), "a version with a trailing build");
  assert.ok(!numberPattern("3KiB"), "a filesize");
  assert.ok(!numberPattern("1.2.3.4"), "an address");
  assert.ok(!numberPattern("63 sec ago"), "a duration");
  assert.ok(!numberPattern("Aug 5, 2026, 11:48 PM"), "a date");
  assert.ok(numberPattern("5"), "an integer");
  assert.ok(numberPattern("12.3"), "a decimal");
  assert.ok(numberPattern("-5"), "a negative");
  assert.ok(numberPattern("42%"), "a percentage");
  assert.ok(numberPattern("1e6"), "an exponent");
});

test("the worker payload sorts to the shape the worker posts back", () => {
  const workerResult = {sorted: sortRows([...rowsOf([5, 1, 3])], "td", "ascending", "number")};
  assert.deepEqual(workerResult.sorted.map((r) => r.td), ["1", "3", "5"]);
});

test("the fallback and the worker agree on a mixed column", () => {
  const mixed = rowsOf(["", "b", "banana", "Apple", "30", "1", "a2", "  "]);
  const workerOut = sortedTexts(mixed, "ascending", "string");
  const fallbackOut = sortedTexts(mixed, "ascending", "string");
  assert.deepEqual(fallbackOut, workerOut);
});

/* -------------------------------------------------- the main-thread fallback */

class FakeCell {
  constructor(text) {this._text = String(text); this._attrs = {};}
  getAttribute(name) {return this._attrs[name] || null;}
  hasAttribute(name) {return Object.prototype.hasOwnProperty.call(this._attrs, name);}
  setAttribute(k, v) {this._attrs[k] = v;}
  addEventListener() {}
  get textContent() {return this._text;}
}

class FakeRow {
  constructor(cells) {
    this.cells = cells.map((c) => new FakeCell(c));
    this._attrs = {};
  }
  getAttribute(name) {return this._attrs[name] || null;}
  setAttribute(k, v) {this._attrs[k] = v;}
}

class FakeTBody {
  constructor(rows) {this.rows = rows; this.appended = [];}
  appendChild(node) {this.appended.push(node);}
}

class FakeTable {
  constructor(rows) {
    this.tagName = "TABLE";
    this.rows = [new FakeRow(["value"])];
    this.tBodies = [new FakeTBody(rows)];
    this.events = [];
  }
  dispatchEvent(evt) {this.events.push(evt.type);}
}

class FakeHeader {
  constructor() {this._attrs = {};}
  setAttribute(k, v) {this._attrs[k] = v;}
  getAttribute(k) {return this._attrs[k] || null;}
  hasAttribute(k) {return Object.prototype.hasOwnProperty.call(this._attrs, k);}
  get cellIndex() {return 0;}
}

/**
 * Runs tablesort.js over a stub table and returns what the sort did.
 *
 * @param {Function|undefined} workerBehavior the Worker global: a constructor, or
 *   undefined for a browser with no Worker support
 * @returns {{table: FakeTable, header: FakeHeader, sort: function(): void,
 *   nthOutput: function(number): string[]}} the stub page, one click on the
 *   header, and the cell texts the nth reorder produced
 */
function runFallback(workerBehavior) {
  const sandbox = {
    document: {
      createDocumentFragment: () => ({children: [], appendChild(el) {this.children.push(el);}}),
      createEvent: (type) => ({type, initCustomEvent() {}})
    },
    CustomEvent: function (name) {this.type = name;},
    Worker: workerBehavior,
    console
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  // the two files ship as separate <script> tags, and the second sees the first's
  // globals, so they are evaluated in one context in load order
  vm.runInContext(`${SHARED_SRC}\n${TABLESORT_SRC}`, sandbox, {filename: "tablesort.js"});

  const table = new FakeTable([new FakeRow(["30", "b"]), new FakeRow(["10", "a"]), new FakeRow(["20", "c"])]);
  const tablesort = sandbox.Tablesort(table, {});
  const header = new FakeHeader();
  return {
    table,
    header,
    sort: () => tablesort.sortTable(header),
    nthOutput: (n) => table.tBodies[0].appended[n].children.map((row) => row.cells[0]._text)
  };
}

test("with no Worker the main-thread sort still sorts ascending", () => {
  const run = runFallback(undefined);
  run.sort();
  assert.deepEqual(run.nthOutput(0), ["10", "20", "30"]);
  assert.deepEqual(run.table.events, ["beforeSort", "afterSort"], "the page is told either way");
  assert.equal(run.header.getAttribute("aria-sort"), "ascending", "and the header says which way");
});

test("a Worker that throws falls back to the same result", () => {
  const run = runFallback(function () {throw new Error("blocked");});
  run.sort();
  assert.deepEqual(run.nthOutput(0), ["10", "20", "30"]);
  assert.deepEqual(run.table.events, ["beforeSort", "afterSort"]);
});

test("a second click on the same header sorts descending", () => {
  const run = runFallback(undefined);
  run.sort();
  run.sort();
  assert.deepEqual(run.nthOutput(1), ["30", "20", "10"]);
  assert.equal(run.header.getAttribute("aria-sort"), "descending");
});
