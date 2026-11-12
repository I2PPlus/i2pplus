/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/jdenticon.js — the
 * identicon renderer the susidns address book and details pages load.
 *
 * Those pages never call the library: this fork dropped the UMD export, so the
 * only reachable path is the declarative one — <svg data-jdenticon-value=...>
 * markup plus a window.jdenticon_config, painted by the script's own startup
 * pass. That is what these tests pin, because it is exactly what susidns
 * exercises:
 *
 *   - a value renders the same icon every time, and two values render
 *     different ones (the icon is the address's visual identity);
 *   - the element is flagged as painted (a JS property, not an attribute) so a
 *     second pass does not repaint it;
 *   - window.jdenticon_config reaches the paint — the pages set padding 0 and
 *     full saturation through it, and a fork that loses that fallback silently
 *     renders every icon with the library defaults;
 *   - an element with no value and no hash is left alone rather than painted
 *     blank.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const SCRIPT = readFileSync(
  new URL("../../../../apps/routerconsole/jsp/js/jdenticon.js", import.meta.url),
  "utf8"
);

/**
 * Loads jdenticon into a fresh window whose body holds the given markup, and
 * lets its startup pass run.
 *
 * @param {string} body markup the page ships
 * @param {Object} [config] value of window.jdenticon_config, if the page sets one
 * @returns {{html: string, els: Element[], repaint: function(): string}} body
 *   markup after the startup pass, the icon elements, and a way to run the pass
 *   again over the same page
 */
function render(body, config) {
  installBrowserShim();
  const {document} = parseHTML(`<!DOCTYPE HTML><html><head></head><body>${body}</body></html>`);
  const sandbox = {
    document,
    navigator: {userAgent: "node"},
    console,
    setTimeout: (fn) => fn(),
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  if (config !== undefined) {sandbox.jdenticon_config = config;}
  // the file is an IIFE over the global object, so it runs the way a
  // <script> tag runs it, with the sandbox as the global
  const pass = () => {
    // the file is an IIFE over the global object, so it runs the way a <script>
    // tag runs it: a fresh vm context whose global object is the sandbox
    vm.runInNewContext(SCRIPT, sandbox);
    return document.body.innerHTML;
  };
  pass();
  return {html: document.body.innerHTML, els: [...document.querySelectorAll("svg, canvas")], repaint: pass};
}

/**
 * One identicon element as the susidns pages render it.
 *
 * @param {string} value the address the icon stands for
 * @param {number} size pixel edge
 * @returns {string} the element markup
 */
function icon(value, size = 24) {
  return `<svg class=identicon width=${size} height=${size} ` +
    `data-jdenticon-value="${value}" xmlns=http://www.w3.org/2000/svg></svg>`;
}

/**
 * Extracts the painted geometry, which is what a config change moves.
 *
 * @param {string} html rendered body markup
 * @returns {string} every path's d attribute, joined
 */
function shapes(html) {
  return [...html.matchAll(/<path d="([^"]+)"/g)].map(m => m[1]).join("|");
}

/**
 * Extracts the painted colours.
 *
 * @param {string} html rendered body markup
 * @returns {string} every path's fill attribute, joined
 */
function fills(html) {
  return [...html.matchAll(/fill="([^"]+)"/g)].map(m => m[1]).join("|");
}

test("jdenticon paints a data-jdenticon-value element with an SVG shape", () => {
  const {html, els} = render(icon("8pkgIP-YKExBOoyB"));
  assert.match(html, /<svg[^>]*viewBox="0 0 24 24"/, "the icon keeps its 24px box");
  assert.match(html, /<path d="[^"]+"/, "a shape is painted");
  assert.match(html, /fill="#[0-9a-f]{6}"/, "with a resolved colour");
  assert.equal(els[0].jdenticonRendered, true, "the element is flagged as painted");
});

test("jdenticon renders the same value identically and different values differently", () => {
  const a = render(icon("e3b2c1d4f5a6b7c8d9e0f1a2b3c4d5e6.b32.i2p")).html;
  const again = render(icon("e3b2c1d4f5a6b7c8d9e0f1a2b3c4d5e6.b32.i2p")).html;
  const other = render(icon("f4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9.b32.i2p")).html;
  assert.equal(a, again, "one address always gets the same icon");
  assert.notEqual(shapes(a), shapes(other), "two addresses do not get the same icon");
});

test("jdenticon honours the size attribute", () => {
  const {html} = render(icon("test", 256));
  assert.match(html, /viewBox="0 0 256 256"/);
});

test("jdenticon applies the padding and saturation the susidns pages set", () => {
  const page = icon("8pkgIP-YKExBOoyB", 256);
  const plain = render(page).html;
  const tuned = render(page, {padding: 0, saturation: {color: 1, grayscale: 0}}).html;
  assert.match(tuned, /<path d="[^"]+"/, "the tuned page still paints");
  assert.notEqual(shapes(plain), shapes(tuned), "padding 0 widens the icon");
  assert.notEqual(fills(plain), fills(tuned), "saturation 1 changes the palette");
});

test("jdenticon leaves an element with neither value nor hash alone", () => {
  const {html, els} = render('<svg class=identicon width=24 height=24 xmlns=http://www.w3.org/2000/svg></svg>');
  assert.ok(!/<path/.test(html), "no shape is added");
  assert.equal(els[0].jdenticonRendered, undefined, "and nothing is flagged as painted");
});

test("jdenticon paints an element that carries a hash instead of a value", () => {
  const {html} = render('<svg width=32 height=32 data-jdenticon-hash="0123456789abcdef0123456789abcdef0123"></svg>');
  assert.match(html, /<path d="[^"]+"/);
});

test("jdenticon does not repaint an element it already painted", () => {
  const {html, els, repaint} = render(icon("same"));
  assert.equal(els[0].jdenticonRendered, true, "painted by the first pass");
  // a second startup pass over the same page is a no-op: the painted flag holds
  assert.equal(repaint(), html);
});
