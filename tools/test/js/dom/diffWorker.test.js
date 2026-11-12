/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/diffWorker.js — the
 * SharedWorker the router console asks to diff one refresh of a table against
 * the last.
 *
 * The worker is the whole reason a console page with thousands of rows can
 * refresh on a slow link: it parses the new markup off the main thread and
 * posts back only what changed. These pin the protocol it answers with, in the
 * order a page actually drives it:
 *
 *   - the first tick for a table posts the whole tbody, because there is
 *     nothing to diff against;
 *   - an identical tick posts "unchanged", so a page can skip the DOM work
 *     entirely;
 *   - a changed cell posts that row alone, and a new row posts an insert that
 *     names the key it goes before, which is what keeps the row in the right
 *     place rather than appended at the end;
 *   - a row that disappears posts its key;
 *   - a reorder, or a row with no key at all, falls back to a full post rather
 *     than posting a diff the page cannot apply;
 *   - a request whose tbody is missing posts full with no vdom, and a request
 *     with no tbodyId is parse-only;
 *   - exactly one message per request, so a page waiting on a tick is never
 *     left holding a stale one.
 *
 * These were the worker half of tools/scripts/tests/vdomparser-test.js, a
 * CommonJS script the repo's "type": "module" marker stopped node from loading
 * at all, and which nothing in the build invoked.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const JS_DIR = new URL("../../../../apps/routerconsole/jsp/js/", import.meta.url);
const VDOM_SRC = readFileSync(new URL("vdomParser.js", JS_DIR), "utf8");
const WORKER_SRC = readFileSync(new URL("diffWorker.js", JS_DIR), "utf8");

/** Two keyed rows, the shape a console table arrives as. */
const TABLE = '<table><tbody id="t"><tr data-key="k1"><td>v1</td></tr>' +
              '<tr data-key="k2"><td>v2</td></tr></tbody></table>';

/**
 * Concatenated text under a VDOM node.
 *
 * @param {Object|null} el a node
 * @returns {string} the text below it
 */
function vdomText(el) {
  if (!el) {return "";}
  if (el.nodeName === "#text") {return el.nodeValue;}
  return (el.children || []).map(vdomText).join("");
}

/**
 * Loads the worker in a context that imitates the SharedWorker global scope:
 * self is the global, and importScripts evaluates vdomParser.js first, exactly
 * as the real worker file asks for it.
 *
 * @returns {{post: function(Object): void, next: function(): Object,
 *   pending: function(): number}} a way to send one request, to take the one
 *   message it answered with, and to count what is left over
 */
function openWorker() {
  const ctx = {};
  ctx.self = ctx;
  vm.createContext(ctx);
  ctx.importScripts = () => vm.runInContext(VDOM_SRC, ctx, {filename: "vdomParser.js"});
  vm.runInContext(WORKER_SRC, ctx, {filename: "diffWorker.js"});

  const sent = [];
  const port = {postMessage: (msg) => sent.push(msg)};
  ctx.self.onconnect({ports: [port]});
  return {
    post: (msg) => port.onmessage({data: msg}),
    next: () => sent.shift(),
    pending: () => sent.length
  };
}

/**
 * Drives one connection through the ticks a page would send.
 *
 * @param {Array<Object>} requests the messages to post, in order
 * @returns {Array<Object>} the message each request answered with
 */
function ticks(requests) {
  const worker = openWorker();
  const out = [];
  for (const request of requests) {
    worker.post(request);
    out.push(worker.next());
  }
  return out;
}

test("the first tick for a table posts the whole tbody", () => {
  const [msg] = ticks([{url: "u-a", html: TABLE, tbodyId: "t"}]);
  assert.equal(msg.action, "full");
  assert.equal(msg.vdom.tagName, "tbody", "there is nothing to diff against yet");
});

test("an identical second tick posts unchanged", () => {
  const [, msg] = ticks([{url: "u-a", html: TABLE, tbodyId: "t"}, {url: "u-a", html: TABLE, tbodyId: "t"}]);
  assert.equal(msg.action, "unchanged", "the page can skip the DOM work entirely");
});

test("one changed cell posts that row alone", () => {
  const changed = TABLE.replace(">v1<", ">v1b<");
  const msg = ticks([{url: "u-a", html: TABLE, tbodyId: "t"}, {url: "u-a", html: changed, tbodyId: "t"}])[1];
  assert.equal(msg.action, "rows");
  assert.equal(msg.rows.length, 1, "only the changed row travels");
  assert.equal(msg.rows[0].attributes["data-key"], "k1");
  assert.equal(vdomText(msg.rows[0]), "v1b");
  assert.deepEqual([msg.inserts.length, msg.removed.length], [0, 0]);
});

test("a new row posts an insert naming the key it goes before", () => {
  const changed = TABLE.replace(">v1<", ">v1b<");
  const withInsert = '<table><tbody id="t"><tr data-key="k1"><td>v1b</td></tr>' +
                     '<tr data-key="k3"><td>v3</td></tr><tr data-key="k2"><td>v2</td></tr></tbody></table>';
  const msg = ticks([{url: "u-a", html: TABLE, tbodyId: "t"},
                     {url: "u-a", html: changed, tbodyId: "t"},
                     {url: "u-a", html: withInsert, tbodyId: "t"}])[2];
  assert.equal(msg.action, "rows");
  assert.equal(msg.inserts.length, 1);
  assert.deepEqual({key: msg.inserts[0].key, before: msg.inserts[0].before, text: vdomText(msg.inserts[0].vdom)},
    {key: "k3", before: "k2", text: "v3"},
    "without the neighbour key the row would land at the end of the table");
});

test("a row that disappears posts its key", () => {
  const changed = TABLE.replace(">v1<", ">v1b<");
  const withInsert = '<table><tbody id="t"><tr data-key="k1"><td>v1b</td></tr>' +
                     '<tr data-key="k3"><td>v3</td></tr><tr data-key="k2"><td>v2</td></tr></tbody></table>';
  const withoutK2 = '<table><tbody id="t"><tr data-key="k1"><td>v1b</td></tr>' +
                    '<tr data-key="k3"><td>v3</td></tr></tbody></table>';
  const msg = ticks([{url: "u-a", html: TABLE, tbodyId: "t"},
                     {url: "u-a", html: changed, tbodyId: "t"},
                     {url: "u-a", html: withInsert, tbodyId: "t"},
                     {url: "u-a", html: withoutK2, tbodyId: "t"}])[3];
  assert.equal(msg.action, "rows");
  // The array the worker posts belongs to its own realm, so it has to be
  // copied into a local one before a strict deepEqual will accept it.
  assert.deepEqual([...msg.removed], ["k2"]);
});

test("reordering the rows falls back to a full post", () => {
  const changed = TABLE.replace(">v1<", ">v1b<");
  const withInsert = '<table><tbody id="t"><tr data-key="k1"><td>v1b</td></tr>' +
                     '<tr data-key="k3"><td>v3</td></tr><tr data-key="k2"><td>v2</td></tr></tbody></table>';
  const withoutK2 = '<table><tbody id="t"><tr data-key="k1"><td>v1b</td></tr>' +
                    '<tr data-key="k3"><td>v3</td></tr></tbody></table>';
  const reordered = '<table><tbody id="t"><tr data-key="k3"><td>v3</td></tr>' +
                    '<tr data-key="k1"><td>v1b</td></tr></tbody></table>';
  const msg = ticks([{url: "u-a", html: TABLE, tbodyId: "t"},
                     {url: "u-a", html: changed, tbodyId: "t"},
                     {url: "u-a", html: withInsert, tbodyId: "t"},
                     {url: "u-a", html: withoutK2, tbodyId: "t"},
                     {url: "u-a", html: reordered, tbodyId: "t"}])[4];
  assert.equal(msg.action, "full", "a diff cannot express a move");
  assert.equal(msg.vdom.tagName, "tbody");
});

test("a row with no key falls back to a full post", () => {
  const keyless = '<table><tbody id="t"><tr data-key="k1"><td>v1b</td></tr>' +
                  '<tr><td>no key</td></tr></tbody></table>';
  const msg = ticks([{url: "u-a", html: TABLE, tbodyId: "t"}, {url: "u-a", html: keyless, tbodyId: "t"}])[1];
  assert.equal(msg.action, "full", "an unkeyed row cannot be matched to the previous tick");
  assert.equal(msg.vdom.tagName, "tbody");
});

test("a missing tbody posts full with no vdom to apply", () => {
  const other = "<table><tbody id='other'><tr data-key='k1'><td>x</td></tr></tbody></table>";
  const msg = ticks([{url: "u-b", html: other, tbodyId: "t"}])[0];
  assert.equal(msg.action, "full");
  assert.equal(msg.vdom, undefined, "there is no tbody to hand back");
});

test("a request with no tbodyId is parse-only", () => {
  const msg = ticks([{url: "u-c", html: "<table><tbody id=t><tr data-key=k1><td>x</td></tr></tbody></table>"}])[0];
  assert.equal(msg.action, "parsed", "the page asked for a parse, not a diff");
  assert.equal(msg.vdom.nodeName, "#document");
});

test("every request answers with exactly one message", () => {
  const worker = openWorker();
  for (const html of [TABLE, TABLE, TABLE.replace(">v1<", ">v1b<")]) {
    worker.post({url: "u-a", html, tbodyId: "t"});
    assert.ok(worker.next(), "one message per tick");
  }
  assert.equal(worker.pending(), 0, "nothing is left over to be read as a later tick");
});
