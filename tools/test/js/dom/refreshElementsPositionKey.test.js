/**
 * Contract tests for positionKey() in apps/routerconsole/jsp/js/refreshElements.js
 * — the key that pairs a live element with the response element standing in the
 * same place, instead of pairing them by index within a selector's match list.
 *
 * This exists because index pairing corrupted the tunnels page. One tunnel
 * opening or closing shifted every later match, so each cell was morphed against
 * its neighbour's data, and cells with no measurement were written over cells
 * that had one. Because the morph also moves the response's own text nodes into
 * the page, the page's structural fallback then reinstalled that drained fragment
 * and every refresh emptied a little more of the page.
 *
 * The keys must therefore be local to a section: a row appearing in one pool has
 * to leave the keys of every other pool untouched, and equal structure has to
 * produce equal keys.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const refreshSrc = readFileSync(
  fileURLToPath(new URL("../../../../apps/routerconsole/jsp/js/refreshElements.js", import.meta.url)),
  "utf8"
);

/**
 * Evaluates the module body with its morphdom import stubbed and returns
 * positionKey. The real import is an absolute URL that Node cannot resolve, and
 * positionKey is pure, so a stub is enough to reach it.
 *
 * @returns {Function} positionKey
 */
function loadPositionKey() {
  const withoutModuleSyntax = refreshSrc
    .replace(/^import .*$/gm, "")
    .replace(/^export /gm, "");
  const sandbox = { morphdom: null };
  // eslint-disable-next-line no-new-func
  Function(
    "sandbox",
    `with (sandbox) { ${withoutModuleSyntax}\n; sandbox.__positionKey = positionKey; }`
  )(sandbox);
  return sandbox.__positionKey;
}

const positionKey = loadPositionKey();

/** One pool with the given number of body rows, as the tunnels page renders it. */
const poolsHtml = rows =>
  `<!DOCTYPE HTML><html><body><div id=tunnelsContainer>` +
  [1, 2, 3]
    .map(
      pool =>
        `<div class=tablewrap><table class=tunneldisplay tunnels_client>` +
        `<tbody>${"<tr><td class=latency>1 ms</td></tr>".repeat(pool)}</tbody>` +
        `<tfoot class=statusnotes><tr class=bwUsage><td colspan=9 class=center><b>Lifetime 1 MB in</b></td></tr></tfoot>` +
        `</table></div>`
    )
    .slice(0, rows) +
  `</div></body></html>`;

/**
 * Returns the position keys of every td.latency in document order.
 *
 * @param {number} pools how many pool tables to render
 * @returns {string[]} the keys, in document order
 */
function latencyKeys(pools) {
  installBrowserShim();
  const { document } = parseHTML(poolsHtml(pools));
  return [...document.querySelectorAll("td.latency")].map(positionKey);
}

test("the same structural position yields the same key in two documents", () => {
  assert.deepEqual(latencyKeys(3), latencyKeys(3));
});

test("cells in different pool tables never share a key", () => {
  const keys = latencyKeys(3);
  assert.equal(new Set(keys).size, keys.length, "every latency cell must be uniquely keyed");
});

test("a row added to one pool leaves the other pools' keys alone", () => {
  // Pool 1 grows; pools 2 and 3 must keep the keys they had, or the morph pairs
  // every later cell against its neighbour.
  const before = latencyKeys(2);
  const after = latencyKeys(3);
  const laterCellsUnchanged = after.slice(before.length).length > 0 &&
    after.slice(-1)[0] !== before[before.length - 1];
  assert.ok(laterCellsUnchanged, "a key must not be reused by a different cell after a row is added");
});

test("a footer and a body cell are keyed apart", () => {
  installBrowserShim();
  const { document } = parseHTML(poolsHtml(3));
  const foot = document.querySelector(".statusnotes");
  const cell = document.querySelector("td.latency");
  assert.notEqual(positionKey(foot), positionKey(cell));
});

test("a key ignores identity so a re-render of the same shape still pairs", () => {
  // The key is positional only: it must not encode text, so a cell whose value
  // changed still pairs with the same key and is updated rather than skipped.
  installBrowserShim();
  const a = parseHTML(poolsHtml(2));
  const b = parseHTML(poolsHtml(2));
  b.document.querySelectorAll("td.latency").forEach(td => { td.textContent = "999 ms"; });
  const ka = [...a.document.querySelectorAll("td.latency")].map(positionKey);
  const kb = [...b.document.querySelectorAll("td.latency")].map(positionKey);
  assert.deepEqual(ka, kb);
});