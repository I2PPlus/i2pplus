/**
 * Tier-2 contract tests for the markup rules of apps/routerconsole/jsp/js/
 * vdomParser.js — the parser the auto-refresh diff runs inside a SharedWorker.
 *
 * The other vdomParser suite pins what a refresh payload has to come out as
 * (rows stay under their explicit tbody, nesting does not leak, the root shape
 * matches the worker contract). These pin the parsing rules that make that
 * possible, which is also what makes a diff worth sending:
 *
 *   - unquoted attribute values are read, and a value glued to a self-closing
 *     slash keeps the slash, because the console writes markup without quotes;
 *   - character references are decoded in text and in attributes, so a diff
 *     compares what the reader sees rather than the markup;
 *   - whitespace is preserved verbatim, including whitespace-only text nodes,
 *     because the console indents its rows and a lost newline changes the text;
 *   - an unclosed <td> closes at the next cell rather than nesting, and a
 *     <table> without a tbody gets an implied one, which is what the row diff
 *     walks;
 *   - <script> content is kept raw and a comment survives as a comment node.
 *
 * These were the parser half of tools/scripts/tests/vdomparser-test.js, a
 * CommonJS script the repo's "type": "module" marker stopped node from loading
 * at all, and which nothing in the build invoked.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const VDOM_SRC = readFileSync(
  new URL("../../../../apps/routerconsole/jsp/js/vdomParser.js", import.meta.url), "utf8");

/**
 * Evaluates the parser the way the SharedWorker does — in a bare context whose
 * self is the global — and returns its parse entry point.
 *
 * @returns {function(string): Object} parse, returning the document root
 */
function loadParser() {
  const ctx = {};
  ctx.self = ctx;
  vm.createContext(ctx);
  vm.runInContext(VDOM_SRC, ctx, {filename: "vdomParser.js"});
  return (html) => ctx.self.VdomParser.parse(html);
}

const parse = loadParser();

/**
 * Concatenated text under a node, which is what a reader of the row sees.
 *
 * @param {Object|null} el a VDOM node
 * @returns {string} the text below it
 */
function vdomText(el) {
  if (!el) {return "";}
  if (el.nodeName === "#text") {return el.nodeValue;}
  return (el.children || []).map(vdomText).join("");
}

test("unquoted attribute values are read, as the console writes them", () => {
  const td = parse("<td class=right data-x=1>5</td>").children[0];
  assert.equal(td.attributes.class, "right");
  assert.equal(td.attributes["data-x"], "1");
});

test("character references are decoded in text and in attributes", () => {
  assert.equal(vdomText(parse("<td>52&nbsp;sec</td>").children[0]), "52\u00a0sec",
    "the console separates readouts with a non-breaking space");
  assert.equal(parse('<td title="a &amp; b">x</td>').children[0].attributes.title, "a & b");
});

test("a table with no tbody gets an implied one", () => {
  const table = parse("<table><tr><td>x</td></tr></table>").children[0];
  const tbody = table.children[0];
  assert.equal(tbody.tagName, "tbody", "the row diff walks a tbody, so it has to be there");
  assert.equal(tbody.children[0].tagName, "tr");
});

test("an unclosed cell closes at the next cell instead of nesting", () => {
  const tr = parse("<tr><td class=queue><td class=edit>E</td></tr>").children[0];
  assert.equal(tr.children.length, 2, "two sibling cells, not one inside the other");
  assert.equal(tr.children[0].attributes.class, "queue");
  assert.equal(tr.children[0].children.length, 0, "the first cell is empty");
  assert.equal(tr.children[1].attributes.class, "edit");
  assert.equal(vdomText(tr.children[1]), "E");
});

test("a value glued to a self-closing slash keeps the slash", () => {
  const polygon = parse('<polygon points="1,1" class=spark-area/>').children[0];
  assert.equal(polygon.attributes.class, "spark-area/",
    "the parser does not invent an element boundary it was not given");
});

test("whitespace is preserved, including the text nodes between elements", () => {
  assert.equal(vdomText(parse("<td> 12.5 </td>").children[0]), " 12.5 ",
    "trimming would make every indented row look changed on every refresh");
  const divKids = parse("<div>\n<span>x</span>\n</div>").children[0].children;
  assert.equal(divKids.length, 3, "the newlines around the span are nodes of their own");
  assert.equal(divKids[0].nodeValue, "\n");
  assert.equal(divKids[2].nodeValue, "\n");
});

test("script content stays raw and an empty script gets no text node", () => {
  const div = parse("<div>a<script src=x></script>b</div>").children[0];
  assert.equal(div.children.length, 3);
  assert.equal(vdomText(div), "ab");
  const script = div.children[1];
  assert.equal(script.tagName, "script");
  assert.equal(script.children.length, 0, "no empty text node to diff against");

  const raw = parse('<script>if (a < b && c > d) { x = "&amp;"; }</script>').children[0];
  assert.equal(raw.children.length, 1);
  assert.equal(raw.children[0].nodeValue, 'if (a < b && c > d) { x = "&amp;"; }',
    "markup characters inside a script are not parsed as markup");
});

test("a comment survives as a comment node", () => {
  const comment = parse("<div><!-- hi -->x</div>").children[0].children[0];
  assert.equal(comment.nodeName, "#comment");
  assert.equal(comment.nodeValue, " hi ");
});

test("a real console row round-trips through the parser", () => {
  const tbody = parse('<tbody id=t><tr data-key="k1"><td class=right>1.2</td></tr>' +
                      '<tr data-key="k2"><td> 52&nbsp;sec </td></tr></tbody>').children[0];
  assert.equal(tbody.tagName, "tbody");
  assert.equal(tbody.attributes.id, "t");
  assert.equal(tbody.children[0].attributes["data-key"], "k1", "the row key the diff matches on");
  assert.equal(tbody.children[1].attributes["data-key"], "k2");
  assert.equal(vdomText(tbody.children[1]), " 52\u00a0sec ");
});
