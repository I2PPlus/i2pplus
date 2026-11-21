/**
 * Tier-2 DOM tests for apps/susidns/src/js/toggleAllHosts.js — the select-all
 * box the SusiDNS address book writes into its dead-hosts header cell.
 *
 * The address book emits that cell, and this script, only while the dead-hosts
 * filter is active, so the contract these tests pin is the narrow one:
 *
 *   - with the cell, the master box appears in it;
 *   - without the cell the script stays silent — a page with no dead-hosts
 *     filter is the address book's normal state, not a failure to report;
 *   - the master box marks and clears every row box, and is left checked,
 *     never indeterminate, after doing so;
 *   - marking some but not all rows leaves the master indeterminate, all of
 *     them leaves it checked, none of them leaves it clear.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { parseHTML } from "linkedom";

const SCRIPT = readFileSync(
  new URL("../../../../apps/susidns/src/js/toggleAllHosts.js", import.meta.url),
  "utf8"
);

/**
 * @param {number} rows hosts the page lists
 * @param {boolean} dead true for the dead-hosts filter, which is the only view
 *   that carries the cell the script fills in
 * @returns {string} the table the address book emits
 */
function book(rows, dead) {
  const cell = dead
    ? `<th class=checkbox id=deadHosts title="Select hosts for deletion from addressbook">
</th>`
    : `<th class=checkbox title="Select hosts for deletion from addressbook">
</th>`;
  const listed = Array.from({ length: rows }, (_, i) =>
    `<tr><td class=names>host${i}.b32.i2p</td>` +
    `<td class=checkbox><input type=checkbox class=optbox name="checked" value="host${i}.b32.i2p" title="Mark for deletion"></td></tr>`
  ).join("");
  return `<table class=book id=host_list><tr class=head>${cell}</tr>${listed}</table>`;
}

/**
 * Loads the script into a window holding the given markup and lets its
 * DOMContentLoaded handler run, the way the page at the foot of body does.
 *
 * @param {string} markup what the page ships
 * @returns {{document: Object, window: Object, warnings: string[]}} the live
 *   document and window, and everything the script wrote to the console
 */
function load(markup) {
  const { document, window } = parseHTML(`<!DOCTYPE HTML><html><body>${markup}</body></html>`);
  const warnings = [];
  const sandbox = {
    document,
    console: { warn: (...a) => warnings.push(a.join(" ")), log() {}, error() {} },
    Event: window.Event,
    HTMLInputElement: window.HTMLInputElement,
    setTimeout: (fn) => fn(),
  };
  sandbox.window = sandbox;
  vm.runInNewContext(SCRIPT, sandbox, { filename: "toggleAllHosts.js" });
  document.dispatchEvent(new window.Event("DOMContentLoaded"));
  return { document, window, warnings };
}

/**
 * @param {Object} el element to click
 * @param {Object} window the window to build the event from
 * @returns {void}
 */
function click(el, window) {
  el.checked = !el.checked;
  el.dispatchEvent(new window.Event("change", { bubbles: true }));
}

test("the master box is written into the dead-hosts cell", () => {
  const { document, window, warnings } = load(book(3, true));
  const master = document.getElementById("toggleAllHosts");
  assert.ok(master, "expected a checkbox with id toggleAllHosts");
  assert.equal(master.type, "checkbox");
  assert.equal(master.parentElement.id, "deadHosts", "the box belongs in the header cell");
  assert.deepEqual(warnings, [], "a page that has the cell must not complain");
});

test("a page with no dead-hosts cell is left alone, silently", () => {
  const { document, window, warnings } = load(book(3, false));
  assert.equal(document.getElementById("toggleAllHosts"), null);
  assert.deepEqual(warnings, [], "no dead-hosts filter is the address book's normal state");
});

test("the master box marks and clears every row", () => {
  const { document, window } = load(book(4, true));
  const master = document.getElementById("toggleAllHosts");
  const rows = [...document.querySelectorAll("td.checkbox input[type=checkbox]")];
  assert.equal(rows.length, 4);

  click(master, window);
  assert.ok(rows.every((box) => box.checked), "every host marked");
  assert.equal(master.checked, true);
  assert.equal(master.indeterminate, false, "all marked is not an indeterminate state");

  click(master, window);
  assert.ok(rows.every((box) => !box.checked), "every host cleared");
  assert.equal(master.checked, false);
  assert.equal(master.indeterminate, false);
});

test("the master box follows the rows", () => {
  const { document, window } = load(book(3, true));
  const master = document.getElementById("toggleAllHosts");
  const rows = [...document.querySelectorAll("td.checkbox input[type=checkbox]")];

  click(rows[0], window);
  assert.equal(master.checked, false);
  assert.equal(master.indeterminate, true, "one of three marked");

  click(rows[1], window);
  click(rows[2], window);
  assert.equal(master.checked, true, "all three marked");
  assert.equal(master.indeterminate, false);

  click(rows[0], window);
  click(rows[1], window);
  click(rows[2], window);
  assert.equal(master.checked, false, "none marked");
  assert.equal(master.indeterminate, false);
});

test("marking a row outside the deletion column is ignored", () => {
  const { document, window } = load(`${book(2, true)}<input type=checkbox id=elsewhere>`);
  const master = document.getElementById("toggleAllHosts");
  click(document.getElementById("elsewhere"), window);
  assert.ok(!master.checked, "the master box was never ticked");
  assert.equal(master.indeterminate, undefined, "the master box was never touched");
});
