/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/morphdom.js — the DOM
 * differ the console's auto-refresh uses to patch rows in place.
 *
 * The console re-renders peer tables every refresh cycle, so the contract that
 * matters is not "the result looks right" but "the result is reached without
 * churning nodes the user is interacting with": keyed rows are moved rather than
 * rebuilt, an unmatched row is discarded once, attribute writes happen only
 * where the two trees disagree, and whitespace-only text nodes never generate
 * insert/remove churn outside <pre>. Every case asserts the resulting markup
 * and the ordered hook trace, because the trace is what shows a node being
 * moved instead of removed and re-added.
 *
 * The to-tree is always a real element in the same document, which is how the
 * console feeds morphdom: it realises the VDOM the SharedWorker sent
 * (refreshElements.js realizeVdom) and passes that element, never a string.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

installBrowserShim();
// morphdom captures `document` at module load, so the shim has to be in place
// before the import.
globalThis.document = parseHTML("<!DOCTYPE HTML><html><body></body></html>").document;

const {default: morphdom} = await import(
  "../../../../apps/routerconsole/jsp/js/morphdom.js"
);

/**
 * Morphs one element into another and reports the resulting markup together
 * with the ordered hook trace. `from` and `to` are the whole element, which is
 * how the console calls it: morphdom(tbody, newTbody).
 *
 * @param {string} from markup the live DOM holds
 * @param {string} to markup the refresh wants
 * @param {Object} [options] morphdom options (childrenOnly, skipFromChildren, ...)
 * @returns {{html: string, trace: string[]}} resulting outerHTML and hook log
 */
function morph(from, to, options = {}) {
  const {document} = parseHTML(`<!DOCTYPE HTML><html><body><div id=wrap>${from}</div></body></html>`);
  globalThis.document = document;
  const root = document.getElementById("wrap").firstChild;
  const freshWrap = document.createElement("div");
  freshWrap.innerHTML = to;
  const fresh = freshWrap.firstChild;
  const trace = [];
  const hooks = {
    onBeforeNodeAdded: (n) => {trace.push(`add:${n.nodeName}`); return n;},
    onNodeAdded: (n) => {trace.push(`added:${n.nodeName}`);},
    onBeforeElUpdated: (a, b) => {
      trace.push(`upd:${a.nodeName}:${b.innerHTML ?? b.textContent ?? ""}`);
      return true;
    },
    onElUpdated: (a) => {trace.push(`updated:${a.nodeName}`);},
    onBeforeNodeDiscarded: (n) => {trace.push(`disc:${n.nodeName}`); return true;},
    onNodeDiscarded: (n) => {trace.push(`discarded:${n.nodeName}`);}
  };
  morphdom(root, fresh, {...hooks, ...options});
  return {html: root.outerHTML, trace};
}

test("morphdom writes a text change in place without touching the element", () => {
  const {html, trace} = morph("<p>text</p>", "<p>other</p>");
  assert.equal(html, "<p>other</p>");
  // the text node is written where it stands: no add, no discard
  assert.deepEqual(trace, ["upd:P:other", "updated:P"]);
});

test("morphdom writes only the attributes that differ", () => {
  const {html, trace} = morph('<div a="1" b="2">t</div>', '<div a="3" c="4">t</div>');
  assert.equal(html, '<div c="4" a="3">t</div>');
  assert.deepEqual(trace, ["upd:DIV:t", "updated:DIV"]);
});

test("morphdom appends a child the old tree did not have", () => {
  const {html, trace} = morph(
    "<ul><li>a</li><li>b</li></ul>",
    "<ul><li>a</li><li>c</li><li>b</li></ul>"
  );
  assert.equal(html, "<ul><li>a</li><li>c</li><li>b</li></ul>");
  assert.deepEqual(trace, [
    "upd:UL:<li>a</li><li>c</li><li>b</li>", "updated:UL",
    "upd:LI:a", "updated:LI",
    "upd:LI:c", "updated:LI",
    "add:LI", "added:LI", "added:#text",
  ]);
});

test("morphdom discards a child the new tree does not have", () => {
  const {html, trace} = morph("<div><span>keep</span><b>drop</b></div>", "<div><span>keep</span></div>");
  assert.equal(html, "<div><span>keep</span></div>");
  assert.deepEqual(trace, [
    "upd:DIV:<span>keep</span>", "updated:DIV",
    "upd:SPAN:keep", "updated:SPAN",
    "disc:B", "discarded:B", "discarded:#text",
  ]);
});

test("morphdom moves a keyed row to the new order instead of rebuilding it", () => {
  const {html, trace} = morph(
    '<ul><li id="a">1</li><li id="b">2</li></ul>',
    '<ul><li id="b">2</li><li id="a">1</li></ul>'
  );
  assert.equal(html, '<ul><li id="b">2</li><li id="a">1</li></ul>');
  // both rows survive the swap: no LI is created or discarded, and each is
  // updated exactly once, in the new order
  assert.deepEqual(trace, [
    'upd:UL:<li id="b">2</li><li id="a">1</li>', "updated:UL",
    "upd:LI:2", "updated:LI",
    "upd:LI:1", "updated:LI",
  ]);
});

test("morphdom rotates three keyed rows to the wanted order", () => {
  const {html} = morph(
    '<ul><li id="a">1</li><li id="b">2</li><li id="c">3</li></ul>',
    '<ul><li id="c">3</li><li id="a">1</li><li id="b">2</li></ul>'
  );
  assert.equal(html, '<ul><li id="c">3</li><li id="a">1</li><li id="b">2</li></ul>');
});

test("morphdom removes one keyed row from the middle without disturbing the others", () => {
  const {html, trace} = morph(
    '<ul><li id="a">1</li><li id="b">2</li><li id="c">3</li></ul>',
    '<ul><li id="a">1</li><li id="c">3</li></ul>'
  );
  assert.equal(html, '<ul><li id="a">1</li><li id="c">3</li></ul>');
  assert.equal(trace.filter(t => t === "disc:LI").length, 1, "exactly one row is discarded");
});

test("morphdom inserts a keyed row between two existing keyed rows", () => {
  const {html} = morph(
    '<ul><li id="a">1</li><li id="b">2</li></ul>',
    '<ul><li id="a">1</li><li id="x">9</li><li id="b">2</li></ul>'
  );
  assert.equal(html, '<ul><li id="a">1</li><li id="x">9</li><li id="b">2</li></ul>');
});

test("morphdom replaces a keyed row whose key is gone with the new keyed row", () => {
  const {html} = morph('<ul><li id="a">1</li></ul>', '<ul><li id="b">2</li></ul>');
  assert.equal(html, '<ul><li id="b">2</li></ul>');
});

test("morphdom keeps keyed rows across an interleaved unkeyed row", () => {
  const {html} = morph(
    '<ul><li id="a">1</li><li>2</li><li id="c">3</li></ul>',
    '<ul><li id="c">3</li><li>2</li><li id="a">1</li></ul>'
  );
  assert.equal(html, '<ul><li id="c">3</li><li>2</li><li id="a">1</li></ul>');
});

test("morphdom honours skipFromChildren and removes nothing from the old tree", () => {
  const {html, trace} = morph(
    '<ul><li id="a">1</li><li id="b">2</li></ul>',
    '<ul><li id="b">2</li><li id="a">1</li></ul>',
    {skipFromChildren: (el) => el.nodeName === "UL"}
  );
  assert.ok(!trace.includes("disc:LI"), "skipFromChildren discards nothing");
  // the from-tree is walked but never reordered, so the rows stay in the order
  // the page already had them and only their content is written
  assert.equal(html, '<ul><li id="a">1</li><li id="b">2</li></ul>');
  assert.deepEqual(trace, [
    'upd:UL:<li id="b">2</li><li id="a">1</li>', "updated:UL",
    "upd:LI:2", "updated:LI",
    "upd:LI:1", "updated:LI",
  ]);
});

test("morphdom with childrenOnly replaces the children and keeps the root", () => {
  const {html} = morph("<div><b>old</b></div>", "<div><i>new</i></div>", {childrenOnly: true});
  assert.equal(html, "<div><i>new</i></div>");
});

test("morphdom writes an input's value when it changes", () => {
  const {html} = morph('<section><input value="old"></section>', '<section><input value="new"></section>');
  assert.equal(html, '<section><input value="new"></section>');
});

test("morphdom handles an empty from-tree and an empty to-tree", () => {
  assert.equal(morph("<div></div>", "<div>only new</div>").html, "<div>only new</div>");
  assert.equal(morph("<div>only old</div>", "<div></div>").html, "<div></div>");
});

test("morphdom recurses into nested structure without re-ordering siblings", () => {
  const {html} = morph(
    "<div><i>a</i><em><b>deep</b></em></div>",
    "<div><i>a</i><em><b>deeper</b></em><u>new</u></div>"
  );
  assert.equal(html, "<div><i>a</i><em><b>deeper</b></em><u>new</u></div>");
});

test("morphdom updates a table section in place rather than moving it", () => {
  const {html, trace} = morph(
    "<table><tr><td>1</td></tr></table>",
    "<table><tr><td>1</td><td>2</td></tr></table>"
  );
  assert.equal(html, "<table><tr><td>1</td><td>2</td></tr></table>");
  assert.ok(!trace.includes("add:TABLE"), "the table itself is not rebuilt");
});
