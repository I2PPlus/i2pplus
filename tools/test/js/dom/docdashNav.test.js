/**
 * Tier-2 contract tests for the docdash page scripts the generated JavaScript
 * API docs load: nav.js, search.js, linenumber.js and collapse.js.
 *
 * Those scripts run on every generated page (tools/jsdoc/jsdoc.json sets
 * search and collapse on), and they act on the nav that
 * tools/jsdoc/docdash-template/publish.js builds — a flat
 * <ul><li class="section-heading|folder"><a href="folder/script.html"> list,
 * with no nested lists and no h3.collapsed_header. So the contract these tests
 * pin is the one the real pages have:
 *
 *   - nav.js scrolls the entry for the current page into view. The nav href is
 *     folder-prefixed while the page's own URL is not, so the comparison has to
 *     be on the file name, not the whole href — that is what these pin;
 *   - search.js hides the nav entries whose text the term does not match and
 *     marks the page as searching, and clearing the box restores every entry;
 *   - linenumber.js gives every source line an anchor id and selects the one the
 *     URL hash names;
 *   - collapse.js runs against this nav without hiding anything: its
 *     collapse_top and nested-list selectors match nothing we generate.
 *
 * The scripts are plain non-module <script> files with top-level side effects,
 * so each test evaluates the file the way the page does, against a linkedom
 * document and the location that page would have.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const SCRIPT_DIR = new URL("../../../../tools/jsdoc/docdash-template/static/scripts/",
                     import.meta.url);

/**
 * The nav publish.js builds for two sections of scripts, as a page renders it:
 * a heading entry per section and one folder-prefixed entry per source file.
 *
 * @returns {string} the nav markup
 */
function navMarkup() {
  return '<h2><a href="index.html">I2P+ JSDoc Index</a></h2>' +
    '<ul>' +
    '<li class="section-heading router"><h4><a href="router/index.html">Router</a></h4></li>' +
    '<li class="router"><a href="router/PeerStats.js.html">PeerStats</a></li>' +
    '<li class="router"><a href="router/PeerProfile.js.html">PeerProfile</a></li>' +
    '<li class="section-heading data"><h4><a href="data/index.html">Data</a></h4></li>' +
    '<li class="data"><a href="data/DataHelper.js.html">DataHelper</a></li>' +
    '</ul>';
}

/**
 * Loads one docdash page script against a fresh page and lets its top-level
 * pass run, the way the deferred <script> tag does.
 *
 * @param {string} name file name under the template's static/scripts
 * @param {string} body page body markup
 * @param {Object} [page] the location the page has: pathname, href, hash
 * @returns {{document: Document, entries: Element[], scrolled: string[],
 *   keyup: function(Element): void}} the live page, its nav entries, the hrefs
 *   of links scrolled into view, and a way to raise a keyup on an element
 */
function loadPage(name, body, page = {}) {
  installBrowserShim();
  const html = `<!DOCTYPE HTML><html><head></head><body>${body}</body></html>`;
  const {window, document} = parseHTML(html);
  const location = {
    pathname: page.pathname ?? "/jsdoc/index.html",
    href: page.href ?? "http://localhost/jsdoc/index.html",
    hash: page.hash ?? ""
  };
  // linkedom's getElementsByClassName() always returns an empty list, and
  // linenumber.js finds the source block with it; map it onto the selector that
  // the same class list means, so the script sees what a browser gives it.
  document.getElementsByClassName = names =>
    document.querySelectorAll(`.${names.trim().split(/\s+/).join(".")}`);
  // linkedom models no scroll API, and the scroll is the only observable effect
  // nav.js has: record which link asked for it instead of pretending it happened
  const scrolled = [];
  for (const link of document.querySelectorAll("a")) {
    link.scrollIntoView = () => scrolled.push(link.getAttribute("href"));
  }
  const src = readFileSync(new URL(name, SCRIPT_DIR), "utf8");
  const sandbox = {document, location, console};
  sandbox.window = Object.assign(Object.create(window), {location});
  sandbox.self = sandbox.window;
  sandbox.document.location = location;
  // the script files are plain non-module scripts with top-level side effects, so
  // they run in a fresh vm context whose globals are the page's own: no Function
  // constructor, and the bare document/window/location references resolve the way
  // they do in the browser
  vm.runInNewContext(src, sandbox);
  return {
    document,
    entries: [...document.querySelectorAll("nav > ul > li")],
    scrolled,
    keyup: el => el.dispatchEvent(new window.Event("keyup"))
  };
}

/**
 * Which nav entries the page would show.
 *
 * @param {Element[]} entries nav entries
 * @returns {string[]} the entry texts that are not hidden
 */
function visible(entries) {
  return entries.filter(el => el.style.display !== "none").map(el => el.textContent.trim());
}

/** The page URL and location a folder-prefixed nav entry points at. */
const SOURCE_PAGE = {
  href: "http://localhost/jsdoc/router/PeerStats.js.html",
  pathname: "/jsdoc/router/PeerStats.js.html"
};

test("nav.js scrolls the nav entry for the current page into view", () => {
  const {scrolled} = loadPage("nav.js", `<nav class=wrap>${navMarkup()}</nav>`, SOURCE_PAGE);
  assert.deepEqual(scrolled, ["router/PeerStats.js.html"],
    "the nav href is folder-prefixed while the page URL is not, so match on the name");
});

test("nav.js scrolls nothing when no nav entry matches the page", () => {
  const {scrolled} = loadPage("nav.js", `<nav class=wrap>${navMarkup()}</nav>`, {
    href: "http://localhost/jsdoc/util/Clock.js.html"
  });
  assert.deepEqual(scrolled, []);
});

test("nav.js does not scroll an entry that merely shares a prefix", () => {
  // PeerStats.js.html vs a PeerStats.html class page: the nav lists source files,
  // so a member page has no entry to scroll to
  const {scrolled} = loadPage("nav.js", `<nav class=wrap>${navMarkup()}</nav>`, {
    href: "http://localhost/jsdoc/router/PeerStats.html"
  });
  assert.deepEqual(scrolled, []);
});

test("search.js hides the entries the term does not match", () => {
  const page = loadPage("search.js",
    `<nav class=wrap><input type=text id=nav-search>${navMarkup()}</nav>`);
  const box = page.document.getElementById("nav-search");
  box.value = "Peer";
  page.keyup(box);
  assert.deepEqual(visible(page.entries), ["PeerStats", "PeerProfile"],
    "the two matching entries stay; the Router heading matches nothing");
  assert.equal(page.document.documentElement.getAttribute("data-search-mode"), "",
    "the page is marked as searching");
});

test("search.js keeps a section heading whose own text matches", () => {
  const page = loadPage("search.js",
    `<nav class=wrap><input type=text id=nav-search>${navMarkup()}</nav>`);
  const box = page.document.getElementById("nav-search");
  box.value = "Data";
  page.keyup(box);
  assert.deepEqual(visible(page.entries), ["Data", "DataHelper"]);
});

test("search.js restores every nav entry when the term is cleared", () => {
  const page = loadPage("search.js",
    `<nav class=wrap><input type=text id=nav-search>${navMarkup()}</nav>`);
  const box = page.document.getElementById("nav-search");
  box.value = "DataHelper";
  page.keyup(box);
  assert.deepEqual(visible(page.entries), ["DataHelper"],
    "a heading only stays if its own text contains the whole term");
  box.value = "";
  page.keyup(box);
  assert.equal(visible(page.entries).length, 5, "every entry is back");
  assert.equal(page.document.documentElement.hasAttribute("data-search-mode"), false,
    "and the searching mark is removed");
});

test("linenumber.js anchors every source line and selects the hashed one", () => {
  const source = '<pre class="prettyprint source linenums">' +
    "<li>line one</li><li>line two</li><li>line three</li></pre>";
  const {document} = loadPage("linenumber.js", `<div id=main>${source}</div>`, {hash: "#line2"});
  const lines = [...document.querySelectorAll("pre li")];
  assert.equal(lines.length, 3, "the source block was found");
  assert.deepEqual(lines.map(l => l.id), ["line1", "line2", "line3"], "each line gets an anchor");
  // linkedom normalises className, so ask about the class rather than the string
  assert.ok(lines[1].classList.contains("selected"), "the hashed line is selected");
  assert.ok(!lines[0].classList.contains("selected") && !lines[2].classList.contains("selected"),
    "the others are not");
});

test("linenumber.js anchors the lines without selecting anything when the hash is empty", () => {
  const source = '<pre class="prettyprint source linenums"><li>a</li><li>b</li></pre>';
  const {document} = loadPage("linenumber.js", `<div id=main>${source}</div>`);
  const lines = [...document.querySelectorAll("pre li")];
  assert.deepEqual(lines.map(l => l.id), ["line1", "line2"]);
  assert.deepEqual(lines.map(l => l.className), ["", ""], "nothing is selected");
});

test("collapse.js runs against the generated nav without hiding its entries", () => {
  const page = loadPage("collapse.js", `<nav class=wrap>${navMarkup()}</nav>`, SOURCE_PAGE);
  // this nav has no ul.collapse_top and no nested lists, so the script's
  // hiding passes match nothing and the entries stay as the page shipped them
  assert.equal(visible(page.entries).length, 5, "no entry is hidden");
});
