/**
 * Contract tests for apps/routerconsole/jsp/js/vdomParser.js — the VDOM parser
 * behind the auto-refresh row diffing in the router console.
 *
 * These pin the fragment-parse contract that keeps a dedicated tbody such as
 * <tbody id=allPeers> intact across refresh cycles: every <tr> of a fragment
 * must remain a direct child of its explicit <tbody>, and markup emitted with
 * a stray extra close tag must be caught by the server-side renderer (the
 * parser is not expected to compensate for double closes).
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const parserSrc = readFileSync(
  fileURLToPath(new URL("../../../../apps/routerconsole/jsp/js/vdomParser.js", import.meta.url)),
  "utf8"
);

/**
 * Evaluates the parser source in a bare sandbox and returns the VdomParser
 * export exactly as the SharedWorker sees it (self.VdomParser).
 *
 * @returns {Object} the VdomParser parse entry point
 */
function loadParser() {
  const sandbox = { self: {} };
  // eslint-disable-next-line no-new-func
  Function("sandbox", `with (sandbox) { ${parserSrc}\n }`)(sandbox);
  return sandbox.self.VdomParser;
}

const vdom = loadParser();

/**
 * Collects every node reachable from root, deepest-last, recording the parent
 * so containment can be asserted (the parser emits no `parent` links).
 *
 * @param {Object} node - a VDOM node
 * @param {Object[]} out - buffer
 * @param {Object|null} parent - caller for node
 * @returns {Object[]} all descendants plus root
 */
function collect(node, out = [], parent = null) {
  node.parent = parent;
  out.push(node);
  for (const child of node.children || []) {collect(child, out, node);}
  return out;
}

/**
 * Locates the explicit tbody whose id is allPeers and counts the <tr> nodes
 * that are its direct children.
 *
 * @param {Object} root - parser root
 * @returns {number} direct <tr> count, or -1 when the tbody is missing
 */
function directRowsUnderAllPeers(root) {
  const tbody = collect(root).find(
    n => n.tagName === "tbody" && n.attributes && n.attributes.id === "allPeers"
  );
  if (!tbody) {return -1;}
  return tbody.children.filter(n => n.tagName === "tr").length;
}

// One peercount row in the exact shape the router renders for a peer with no
// local tunnels but transit tunnels: identity cells, the accepted-capability
// badge as a fully self-contained nested table, the empty colspan=2 count
// cell, a transit count and bar, and the edit link. <tb>…</tb> wrapper keeps
// the fragment standalone.
const ROW_CAPS_TABLE =
  '<td><table class="rid ric"><tr><td class="rbw X isff">X</td></tr></table></td>';
const ROW_FIXED =
  '<tr data-key="8pkgIP-YKExBOoyB">' +
    "<td><span class=peerFlag>fl</span></td>" +
    "<td><span class=routerHash>8pkg</span></td>" +
    "<td data-sort=0.9.70><span class=version>0.9.70</span></td>" +
    ROW_CAPS_TABLE +
    "<td><span class=ipaddress>1.2.3.4</span></td>" +
    "<td><span class=rlookup>host</span></td>" +
    '<td class=tcount colspan=2 data-sort-column-key=localCount data-sort=0>' +
    "</td>" +
    '<td class=tcount data-sort-column-key=transitCount data-sort=4>4</td>' +
    '<td class=bar><span class=percentBarOuter>bar</span></td>' +
    '<td><a class=configpeer href="/configpeer?peer=h">Edit</a></td>' +
  "</tr>\n";

// The malformed variant of the same row: the colspan local-count cell carries
// an extra closing tag, reproducing the historical TunnelRenderer bug that
// collapsed the whole tbody to one row under the parser.
const ROW_MALFORMED = ROW_FIXED.replace(
  "data-sort=0></td>",
  "data-sort=0></td></td>"
);

const FRAGMENT_FIXED =
  "<tbody id=allPeers>\n" + ROW_FIXED + ROW_FIXED + ROW_FIXED + "</tbody>";
const FRAGMENT_MALFORMED =
  "<tbody id=allPeers>\n" + ROW_MALFORMED + ROW_MALFORMED + ROW_MALFORMED + "</tbody>";

test("vdomParser keeps every fragment row under its explicit tbody", () => {
  const root = vdom.parse(FRAGMENT_FIXED);
  assert.equal(directRowsUnderAllPeers(root), 3);
});

test("vdomParser nests the capability table inside its cell without leaking rows", () => {
  const root = vdom.parse(FRAGMENT_FIXED);
  const all = collect(root);
  const nestedTable = all.find(
    n => n.tagName === "table" && n.attributes && n.attributes.class === "rid ric"
  );
  assert.ok(nestedTable, "nested rid ric table present");
  // The nested table is a child of a td cell, and its <tr> stays inside the
  // table's implied tbody - it must not surface as a direct child of the
  // peer region (tbody#allPeers), or the peer row count would be malformed.
  const cell = nestedTable.parent;
  assert.equal(cell.tagName, "td");
  assert.ok(
    nestedTable.children.some(c => c.tagName === "tbody"),
    "bare <tr> under <table> gets an implied tbody, matching browser DOM"
  );
  assert.ok(
    nestedTable.children.some(c => c.children && c.children.some(n => n.tagName === "tr")),
    "nested row present inside the table's implied tbody"
  );
});

test("vdomParser does NOT compensate for a double-close count cell", () => {
  const root = vdom.parse(FRAGMENT_MALFORMED);
  assert.ok(
    directRowsUnderAllPeers(root) < 3,
    "stray closing </td> must still be corrected at the markup source"
  );
});

test("vdomParser root node shape matches the worker contract", () => {
  const root = vdom.parse(FRAGMENT_FIXED);
  assert.equal(root.nodeName, "#document");
  assert.ok(Array.isArray(root.children));
  assert.ok(root.children.some(n => n.tagName === "tbody"));
});
