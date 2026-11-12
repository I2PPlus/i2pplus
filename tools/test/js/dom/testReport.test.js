/**
 * Tier-2 contract tests for tools/test/test-report.js, the script
 * unit-tests-to-html.py inlines into dist/test-report.html.
 *
 * The report has shipped sortable headers, a filter box and clickable class
 * rows for its whole life with the script that drives them never written, so
 * every one of those controls was decoration. These pin the three interactions
 * against a report the generator itself produced, not against a hand-written
 * copy of its markup, because the markup is the contract:
 *
 *   - a filter query narrows the class rows by class name or by any method name
 *     inside the class, hides the rest with their detail rows, and opens the
 *     <details> block a match sits in, since every table starts collapsed and a
 *     match nobody can see is not a result;
 *   - a header sorts its own table only. A module with several java packages
 *     emits several tables that all repeat the module id in data-sort-table, so
 *     a sort keyed on that attribute would reorder tables the reader did not
 *     click;
 *   - a class row and its hidden detail row move as one unit, and a third click
 *     on a header returns the table to the order the generator wrote;
 *   - the source survives the generator's inlining, which strips "//" comments
 *     and collapses every run of whitespace to one space.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { parseHTML } from "linkedom";

const SCRIPT = readFileSync(new URL("../../test-report.js", import.meta.url), "utf8");

/** The generator, run for real so the markup under test is the shipped markup. */
const GENERATOR = fileURLToPath(new URL("../../unit-tests-to-html.py", import.meta.url));

/**
 * One JUnit XML file, in the shape ant's junit formatter writes.
 *
 * @param {string} name testsuite name, which becomes the class row's text
 * @param {Array<{m: string, cls: string, t: number}>} cases the test cases
 * @param {Object} [tally] failures, errors and skipped counts to report
 * @returns {string} the XML document
 */
function suiteXml(name, cases, tally = {}) {
  const body = cases.map((c) => `  <testcase name="${c.m}" classname="${c.cls}" time="${c.t}"/>`).join("\n");
  const time = cases.reduce((sum, c) => sum + c.t, 0).toFixed(3);
  const f = tally.failures || 0;
  const e = tally.errors || 0;
  const s = tally.skipped || 0;
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="${name}" tests="${cases.length}" ` +
         `failures="${f}" errors="${e}" skipped="${s}" time="${time}">\n${body}\n</testsuite>\n`;
}

/**
 * Runs the generator over a small two-module run and returns its report. The
 * router module holds two packages, which is what makes two tables share one
 * data-sort-table value.
 *
 * @returns {string} the generated HTML
 */
function generateReport() {
  const dir = mkdtempSync(join(tmpdir(), "i2p-test-report-"));
  for (const module of ["core", "router"]) {mkdirSync(join(dir, module));}
  writeFileSync(join(dir, "core", "TEST-net.i2p.data.HashTest.xml"),
    suiteXml("net.i2p.data.HashTest", [{m: "testEncode", cls: "net.i2p.data.HashTest", t: 0.25}]));
  writeFileSync(join(dir, "router", "TEST-net.i2p.router.AlphaTest.xml"),
    suiteXml("net.i2p.router.AlphaTest", [
      {m: "decisionKeepsGoodPeers", cls: "net.i2p.router.AlphaTest", t: 0.1},
      {m: "decisionDropsDead", cls: "net.i2p.router.AlphaTest", t: 0.2},
      {m: "cascadeOrdering", cls: "net.i2p.router.AlphaTest", t: 0.3}
    ]));
  writeFileSync(join(dir, "router", "TEST-net.i2p.router.BetaTest.xml"),
    suiteXml("net.i2p.router.BetaTest", [{m: "oneCase", cls: "net.i2p.router.BetaTest", t: 0.05}]));
  writeFileSync(join(dir, "router", "TEST-net.i2p.router.pool.ZetaTest.xml"),
    suiteXml("net.i2p.router.pool.ZetaTest",
      [{m: "slowCase", cls: "net.i2p.router.pool.ZetaTest", t: 5}], {failures: 1}));
  const out = join(dir, "out.html");
  const run = spawnSync("python3", [GENERATOR, dir, out]);
  assert.equal(run.status, 0, `the generator failed: ${run.stderr}`);
  return readFileSync(out, "utf8");
}

/**
 * The python3 the generator needs, reported as a skip reason rather than a
 * failure, matching the harness's degradation contract for an optional tool.
 */
const NO_PYTHON = spawnSync("python3", ["--version"]).status === 0 ? false : "python3 is not installed";

/**
 * Runs the report script in a fresh vm context whose globals are the page's
 * own, which is how the inlined classic script sees them.
 *
 * @param {string} html the page to run it over
 * @returns {{document: Document, window: Object, helpers: Object,
 *   click: function(Element): void, filter: function(string): void}} the live
 *   page, the script's exports, and ways to click and to type
 */
function loadPage(html) {
  const {window, document} = parseHTML(html);
  const sandbox = {document, console};
  sandbox.window = window;
  sandbox.self = window;
  vm.runInNewContext(SCRIPT, sandbox);
  return {
    document,
    window,
    helpers: sandbox.self.TestReport,
    click: (el) => el.dispatchEvent(new window.Event("click", {bubbles: true})),
    filter: (text) => {
      const bar = document.querySelector(".search-input");
      bar.value = text;
      bar.dispatchEvent(new window.Event("input", {bubbles: true}));
    }
  };
}

/** The helpers, from a page with no report on it at all. */
function loadHelpers() {
  return loadPage("<!DOCTYPE html><html><head></head><body></body></html>").helpers;
}

/** The generated report, wired up. */
function loadReport() {
  return loadPage(generateReport());
}

/**
 * The module table whose package label is pkg. The generator writes each
 * package's table inside a <details> whose title is "package · N tests", which
 * is the only thing that names the package, and the one that survives the
 * class-name prefix a subpackage shares.
 *
 * @param {Document} document the report
 * @param {string} pkg the java package whose table is wanted
 * @returns {Element} that table
 */
function tableFor(document, pkg) {
  const box = [...document.querySelectorAll("details")].find((b) => {
    const title = b.querySelector(".tabletitle");
    return title && title.textContent.startsWith(`${pkg} `);
  });
  assert.ok(box, `no package block for ${pkg}`);
  const table = box.querySelector("table");
  assert.ok(table, `no table for ${pkg}`);
  return table;
}

/**
 * The class rows of one table, in document order, each with the detail row
 * that follows it, so what a sort does to the pairs is visible.
 *
 * @param {Document} document the report
 * @param {string} pkg the java package whose table is wanted
 * @returns {Array<{name: string, hidden: boolean, detail: string|null,
 *   detailHidden: boolean|null}>} the rows in order
 */
function orderedRows(document, pkg) {
  const rows = [...tableFor(document, pkg).querySelector("tbody").children];
  const out = [];
  rows.forEach((row, i) => {
    if (row.classList.contains("detail-row")) {return;}
    const detail = rows[i + 1] && rows[i + 1].classList.contains("detail-row") ? rows[i + 1] : null;
    out.push({
      name: row.querySelector("td").textContent,
      hidden: row.style.display === "none",
      detail: detail ? detail.id : null,
      detailHidden: detail ? detail.style.display === "none" : null
    });
  });
  return out;
}

/**
 * The sortable header of one column of one package's table.
 *
 * @param {Document} document the report
 * @param {string} pkg the java package whose table is wanted
 * @param {number} col the data-sort-col value
 * @returns {Element} that header
 */
function headerFor(document, pkg, col) {
  const th = [...tableFor(document, pkg).querySelectorAll("th.sortable")]
    .find((h) => Number(h.getAttribute("data-sort-col")) === col);
  assert.ok(th, `no header for column ${col} of ${pkg}`);
  return th;
}

test("the script exports its helpers and stays inert on a page with no report", () => {
  const helpers = loadHelpers();
  for (const name of ["cellKey", "compareCells", "sortGroups", "matchesQuery", "nextDir", "init"]) {
    assert.equal(typeof helpers[name], "function", name);
  }
});

test("a duration or count cell sorts as a number and a name as text", () => {
  const cellKey = loadHelpers().cellKey;
  assert.equal(cellKey("12"), 12);
  assert.equal(cellKey("0.123s"), 0.123, "the generator writes durations with an s suffix");
  assert.equal(cellKey(" 4 "), 4, "surrounding space is not part of the value");
  assert.equal(typeof cellKey("net.i2p.data.HashTest"), "string");
  assert.equal(cellKey(""), null, "a blank cell has no key and sorts last");
  assert.equal(cellKey(null), null);
});

test("blank cells sort last in both directions", () => {
  const compareCells = loadHelpers().compareCells;
  for (const dir of ["asc", "desc"]) {
    assert.ok(compareCells("", "3", dir) > 0, `blank after a number, ${dir}`);
    assert.ok(compareCells("3", "", dir) < 0, `number before blank, ${dir}`);
  }
  assert.ok(compareCells("2", "10", "asc") < 0, "numeric, not lexicographic");
  assert.ok(compareCells("2", "10", "desc") > 0, "the direction reverses a real pair");
  assert.ok(compareCells("0.5s", "5s", "asc") < 0, "durations compare as durations");
});

test("sortGroups orders whole groups and never splits a class from its cases", () => {
  const sortGroups = loadHelpers().sortGroups;
  const groups = [
    {rows: ["a1", "a2"], cells: ["A", "3", "0", "0", "1.0s"]},
    {rows: ["b1"], cells: ["B", "1", "0", "0", "9.0s"]},
    {rows: ["c1", "c2", "c3"], cells: ["C", "2", "0", "0", "0.2s"]}
  ];
  assert.deepEqual(sortGroups(groups, 1, "asc").map((g) => g.rows), [["b1"], ["c1", "c2", "c3"], ["a1", "a2"]]);
  assert.deepEqual(sortGroups(groups, 4, "desc").map((g) => g.rows[0]), ["b1", "a1", "c1"]);
  assert.equal(groups[0].rows.length, 2, "the input array is not mutated");
});

test("a filter query matches the class name or any method inside it", () => {
  const matchesQuery = loadHelpers().matchesQuery;
  const needles = ["net.i2p.router.AlphaTest", "decisionKeepsGoodPeers cascadeOrdering"];
  assert.equal(matchesQuery(needles, ""), true, "an empty query hides nothing");
  assert.equal(matchesQuery(needles, "alpha"), true, "a lowercase query reaches a mixed-case name");
  assert.equal(matchesQuery(needles, "ALPHA DECISION"), true, "the query is folded, not the needles alone");
  assert.equal(matchesQuery(needles, "cascade"), true, "a method name reaches its class");
  assert.equal(matchesQuery(needles, "alpha decision"), true, "every term must appear, anywhere in the class");
  assert.equal(matchesQuery(needles, "alpha zzz"), false, "one absent term is enough to exclude");
  assert.equal(matchesQuery(needles, "zzz"), false);
});

test("a header cycles asc, desc, then back to the written order", () => {
  const nextDir = loadHelpers().nextDir;
  assert.equal(nextDir(""), "asc");
  assert.equal(nextDir("asc"), "desc");
  assert.equal(nextDir("desc"), "", "a third click clears the sort rather than repeating desc");
});

test("the filter box narrows class rows and keeps their detail rows with them", {skip: NO_PYTHON}, () => {
  const {document, filter} = loadReport();
  filter("Zeta");
  assert.deepEqual(orderedRows(document, "net.i2p.router.pool").map((r) => r.name),
    ["net.i2p.router.pool.ZetaTest"]);
  assert.equal(orderedRows(document, "net.i2p.router.pool")[0].hidden, false, "the match stays");
  assert.equal(orderedRows(document, "net.i2p.data")[0].hidden, true, "the non-matching class is hidden");
  const bar = document.querySelector(".search-input");
  assert.ok(bar.classList.contains("active"), "the box shows that a filter is on");

  filter("");
  assert.equal(orderedRows(document, "net.i2p.data")[0].hidden, false, "clearing brings every row back");
  assert.equal(bar.classList.contains("active"), false, "and the box stops showing it");
});

test("a match inside a collapsed package block opens it, and clearing closes it", {skip: NO_PYTHON}, () => {
  const {document, filter} = loadReport();
  const blocks = [...document.querySelectorAll("details")];
  assert.ok(blocks.length >= 3, "the fixture has several package blocks");
  assert.ok(blocks.every((b) => !b.hasAttribute("open")), "the report starts collapsed");
  filter("cascade");
  const opened = blocks.filter((b) => b.hasAttribute("open"));
  assert.equal(opened.length, 1, "only the block holding the matching method opens");
  assert.ok(opened[0].textContent.includes("AlphaTest"));
  filter("");
  assert.ok(blocks.every((b) => !b.hasAttribute("open")), "clearing restores the collapsed page");
});

test("clicking a header sorts its own table, not every table of that module", {skip: NO_PYTHON}, () => {
  const {document, click} = loadReport();
  // The router module emits one table per package and stamps data-sort-table
  // with the module id on every one of them, which is the trap: a sort that
  // resolves the header to the module rather than to its own table reaches
  // across to a table the reader never clicked.
  const routerHeaders = [...document.querySelectorAll('th[data-sort-table="router"]')];
  assert.ok(routerHeaders.length > 5, "the module's tables share the attribute value");
  click(headerFor(document, "net.i2p.router.pool", 4));
  assert.deepEqual(orderedRows(document, "net.i2p.router").map((r) => r.name),
    ["net.i2p.router.AlphaTest", "net.i2p.router.BetaTest"],
    "the sibling package's table is untouched");
  click(headerFor(document, "net.i2p.router", 1));
  assert.deepEqual(orderedRows(document, "net.i2p.router").map((r) => r.name),
    ["net.i2p.router.BetaTest", "net.i2p.router.AlphaTest"], "ascending by passed count");
  assert.deepEqual(orderedRows(document, "net.i2p.router.pool").map((r) => r.name),
    ["net.i2p.router.pool.ZetaTest"], "and the other package's table stays put");
  assert.ok(headerFor(document, "net.i2p.router", 1).classList.contains("sort-asc"),
    "the header shows which way it is sorted");
});

test("a sorted table keeps each class row above its own detail rows", {skip: NO_PYTHON}, () => {
  const {document, click} = loadReport();
  const header = headerFor(document, "net.i2p.router", 4);
  click(header);
  click(header);
  const kids = [...tableFor(document, "net.i2p.router").querySelector("tbody").children];
  const at = kids.findIndex((r) => r.classList.contains("test-row") && r.textContent.includes("AlphaTest"));
  assert.ok(at >= 0, "the class row is still in the table");
  assert.ok(kids[at + 1].classList.contains("detail-row"), "its detail row still follows it");
  assert.equal(kids[at + 1].id, kids[at].getAttribute("data-toggle"), "and it is that class's own case list");
});

test("a third click on a header returns the table to the written order", {skip: NO_PYTHON}, () => {
  const {document, click} = loadReport();
  // Column 0 is the class name, which the generator already writes in order, so
  // the descending step is what makes the restore observable.
  const header = headerFor(document, "net.i2p.router", 0);
  click(header);
  assert.deepEqual(orderedRows(document, "net.i2p.router").map((r) => r.name),
    ["net.i2p.router.AlphaTest", "net.i2p.router.BetaTest"], "ascending by name");
  click(header);
  assert.deepEqual(orderedRows(document, "net.i2p.router").map((r) => r.name),
    ["net.i2p.router.BetaTest", "net.i2p.router.AlphaTest"], "descending reverses the written order");
  click(header);
  assert.deepEqual(orderedRows(document, "net.i2p.router").map((r) => r.name),
    ["net.i2p.router.AlphaTest", "net.i2p.router.BetaTest"], "a third click restores it");
  assert.equal(header.classList.contains("sort-asc"), false, "no column is marked sorted");
  assert.equal(header.classList.contains("sort-desc"), false);
});

test("clicking a class row opens its case list, and a filter keeps it open", {skip: NO_PYTHON}, () => {
  const {document, click, filter} = loadReport();
  const row = [...document.querySelectorAll("tr[data-toggle]")].find((r) => r.textContent.includes("AlphaTest"));
  const detail = document.getElementById(row.getAttribute("data-toggle"));
  assert.equal(detail.style.display, "none", "the case list starts hidden");
  click(row);
  assert.equal(detail.style.display, "", "the click opens it");
  filter("Alpha");
  assert.equal(detail.style.display, "", "a filter does not collapse what the reader opened");
  filter("");
  assert.equal(detail.style.display, "", "nor does clearing the filter");
  click(row);
  assert.equal(detail.style.display, "none", "clicking again closes it");
});

test("the generator's inlining leaves a script that still parses", () => {
  // load_js() strips every "//" comment and collapses all whitespace, so the
  // source may not rely on a line break nor put "//" inside a string.
  const mangled = SCRIPT.replace(/\/\/.*?\n/g, "\n").replace(/\s+/g, " ").trim();
  new vm.Script(mangled);
  assert.ok(!mangled.includes("//"), "no // pair survives into the payload");
  assert.ok(!mangled.includes("</script>"), "the payload cannot close its own tag");
  assert.ok(mangled.length < SCRIPT.length, "the comments are stripped, as the generator does");
});

test("the report ant test writes carries the script inline", {skip: NO_PYTHON}, () => {
  const html = generateReport();
  const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  assert.equal(blocks.length, 1, "one inline script");
  assert.ok(blocks[0].includes("TestReport"), "it is test-report.js, not an empty tag");
  assert.ok(html.includes('class="search-input"'), "and the control it drives is still there");
});
