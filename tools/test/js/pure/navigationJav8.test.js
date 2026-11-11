/**
 * Tier-1 unit tests for tools/javadoc/jdk8/navigation-jav8.js - the script the
 * javadoc-java8 build puts in place of the Java 8 doclet's own frameset script.
 *
 * It is a classic script, not a module: the frameset's onLoad attribute calls
 * loadFrames() off the window, and each generated page hands show() and
 * updateTabs() its methods, tabs and colour names as globals. So the file is read
 * and evaluated in a stub sandbox the way a browser would run it, and the
 * assertions are made on what that run leaves behind - the class frame's
 * location, the rows and tabs of a member table, and the click handler it wires.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

/** The script under test, read from the tree rather than copied here. */
const SCRIPT = new URL("../../../../tools/javadoc/jdk8/navigation-jav8.js", import.meta.url);

/** An element of the stub DOM, carrying only what the script reads or writes. */
class StubElement {
  /**
   * @param {string} id the id getElementById finds this element by.
   * @param {Object<string, string>} attrs attributes to serve from getAttribute.
   */
  constructor(id, attrs = {}) {
    this.id = id;
    this.attrs = attrs;
    this.style = { display: "initial" };
    this.className = "untouched";
    this.innerHTML = "untouched";
    this.firstChild = null;
    this.parentNode = null;
  }

  /**
   * @param {string} name the attribute to read.
   * @returns {?string} its value, or null when the element does not carry it.
   */
  getAttribute(name) {
    return name in this.attrs ? this.attrs[name] : null;
  }

  /** Stands in for Element.closest for the one selector the script asks for. */
  closest() {
    for (let node = this; node; node = node.parentNode) {
      if (node.getAttribute && node.getAttribute("data-method-filter") !== null) {
        return node;
      }
    }
    return null;
  }
}

/**
 * Runs one copy of the script against a fresh stub page.
 * @param {string} search the query string of the frameset index page.
 * @param {Object} page the globals a generated class page defines: methods, tabs
 *   and the four colour and class names.
 * @returns {{context: Object, byId: Map<string, StubElement>, clicks:
 *   Array<Function>, top: Object}} the sandbox and the handles to drive it
 */
function loadScript(search, page = {}) {
  const byId = new Map();
  const body = new StubElement("body");
  byId.set("body", body);
  const clicks = [];
  body.addEventListener = (type, fn) => {
    if (type !== "click") {
      throw new Error("unexpected body listener: " + type);
    }
    clicks.push(fn);
  };
  const document = {
    body,
    getElementById: (id) => (byId.has(id) ? byId.get(id) : null),
    addEventListener: (type, fn) => {
      if (type !== "DOMContentLoaded") {
        throw new Error("unexpected document listener: " + type);
      }
      fn();
    },
  };
  const top = { classFrame: { location: "help-doc.html" } };
  const context = vm.createContext({ window: { location: { search } }, document, top });
  Object.assign(context, page);
  vm.runInContext(readFileSync(SCRIPT, "utf8"), context, { filename: "navigation.js" });
  return { context, byId, clicks, top };
}

/**
 * The globals of a generated class page: three rows and two tabs to switch
 * between them. Every member row carries the "all" bit beside its own category
 * bit, which is how the doclet writes the mask.
 * @returns {Object} the globals to hand the script
 */
function memberPage() {
  return {
    methods: { m_all: 1, m_instance: 1 | 2, m_static: 1 | 4 },
    tabs: { 1: ["t1", "All Methods"], 2: ["t2", "Instance Methods"] },
    altColor: "altColor",
    rowColor: "rowColor",
    tableTab: "tableTab",
    activeTableTab: "activeTableTab",
  };
}

/**
 * Adds the rows and the tab strip of memberPage() to a stub page's elements.
 * @param {Map<string, StubElement>} byId the map getElementById reads.
 * @returns {void}
 */
function addTableElements(byId) {
  for (const id of ["m_all", "m_instance", "m_static", "t1", "t2"]) {
    byId.set(id, new StubElement(id));
  }
  for (const id of ["t1", "t2"]) {
    byId.get(id).firstChild = new StubElement(id + "-label");
  }
}

test("a deep link opens the class the reader asked for", () => {
  const { context, top } = loadScript("?net/i2p/data/RouterInfo.html");
  context.loadFrames();
  assert.equal(top.classFrame.location, "net/i2p/data/RouterInfo.html");
});

test("a nested class is a deep link too", () => {
  const { context, top } = loadScript("?net/i2p/router/PeerManager$Builder.html");
  context.loadFrames();
  assert.equal(top.classFrame.location, "net/i2p/router/PeerManager$Builder.html");
});

test("a percent-encoded value is checked decoded and loaded as written", () => {
  const { context, top } = loadScript("?net%2Fi2p%2FFoo.html");
  context.loadFrames();
  assert.equal(top.classFrame.location, "net%2Fi2p%2FFoo.html");
});

test("no query string leaves the frameset on the page it names itself", () => {
  const { context, top } = loadScript("");
  context.loadFrames();
  assert.equal(top.classFrame.location, "help-doc.html");
});

test("a deep link naming another host is not loaded", () => {
  for (const search of ["?https://evil.example/x.html", "?//evil.example/x.html", "?a:b.html"]) {
    const { context, top } = loadScript(search);
    context.loadFrames();
    assert.equal(top.classFrame.location, "help-doc.html", search);
  }
});

test("a deep link that would leave the documentation is not loaded", () => {
  for (const search of ["?..%2Fetc.html", "?/etc/passwd.html", "?x=y.html", "?a%ZZ.html",
      "?Foo.html?x=1", "?Foo.html#frag", "?a+b.html", "?a b.html"]) {
    const { context, top } = loadScript(search);
    context.loadFrames();
    assert.equal(top.classFrame.location, "help-doc.html", search);
  }
});

test("validURL takes the page names the doclet generates", () => {
  const { context } = loadScript("");
  for (const page of ["net/i2p/Foo.html", "net/i2p/Foo.Bar.html", "a1-.html", "a_$b.html",
      "$x.html", "_y.html", "a-b/C9.html", ".html", "a/.html", "net/i2p/Ünicode.html",
      "日本語/Class.html", "a%2Fb.html"]) {
    assert.equal(context.validURL(page), true, page);
  }
});

test("validURL refuses anything that is not one page of ours", () => {
  const { context } = loadScript("");
  for (const page of ["", ".htm", "Foo.HTML", "9a.html", "-a.html", "a b.html", "a+b.html",
      "/abs.html", "a//b.html", "a./b.html", "a..b.html", "a/b.c/d.html", "Foo.htmlx.html",
      "a.htmlb.html", "Foo.html#frag", "Foo.html?x", "x=y.html", "..%2Fetc.html", "a%ZZ.html"]) {
    assert.equal(context.validURL(page), false, page);
  }
});

test("show hides the rows outside the category and bands the ones inside it", () => {
  const { context, byId } = loadScript("", memberPage());
  addTableElements(byId);
  context.show(2);
  const afterInstance = ["m_all", "m_instance", "m_static"]
    .map((id) => [byId.get(id).style.display, byId.get(id).className]);
  assert.deepEqual(afterInstance, [["none", "untouched"], ["", "altColor"], ["none", "untouched"]]);
  context.show(1);
  const banded = ["m_all", "m_instance", "m_static"].map((id) => byId.get(id).className);
  assert.deepEqual(banded, ["altColor", "rowColor", "altColor"]);
});

test("show ignores keys the page inherits rather than defines", () => {
  const page = memberPage();
  page.methods = Object.assign(Object.create({ m_inherited: 7 }), page.methods);
  const { context, byId } = loadScript("", page);
  addTableElements(byId);
  byId.set("m_inherited", new StubElement("m_inherited"));
  context.show(7);
  assert.equal(byId.get("m_inherited").style.display, "initial");
});

test("show tolerates a row the page never emitted", () => {
  const { context } = loadScript("", memberPage());
  assert.doesNotThrow(() => context.show(1));
});

test("updateTabs selects one tab and links the rest to their own category", () => {
  const { context, byId } = loadScript("", memberPage());
  addTableElements(byId);
  context.show(2);
  assert.equal(byId.get("t1").className, "tableTab");
  assert.equal(byId.get("t1").firstChild.innerHTML,
      '<a href="#" data-method-filter="1">All Methods</a>');
  assert.equal(byId.get("t2").className, "activeTableTab");
  assert.equal(byId.get("t2").firstChild.innerHTML, "Instance Methods");
});

test("updateTabs escapes a label that would otherwise close the anchor", () => {
  const page = memberPage();
  page.tabs = { 1: ["t1", 'Static <a href="x">methods</a>'] };
  const { context, byId } = loadScript("", page);
  byId.set("t1", new StubElement("t1"));
  byId.get("t1").firstChild = new StubElement("t1-label");
  context.show(2);
  assert.equal(byId.get("t1").firstChild.innerHTML,
      '<a href="#" data-method-filter="1">Static &lt;a href=&quot;x&quot;&gt;methods&lt;/a&gt;</a>');
});

test("a click on a tab is taken by the delegated handler and filters the table", () => {
  const { context, byId, clicks } = loadScript("", memberPage());
  addTableElements(byId);
  assert.equal(clicks.length, 1, "one delegated listener, however many tabs");
  const anchor = new StubElement("t1-link", { "data-method-filter": "2" });
  anchor.parentNode = byId.get("t1");
  const text = new StubElement("t1-text");
  text.parentNode = anchor;
  let prevented = false;
  clicks[0]({ target: text, preventDefault: () => { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(byId.get("m_instance").style.display, "");
  assert.equal(byId.get("m_static").style.display, "none");
});

test("a click outside any tab is left alone", () => {
  const { byId, clicks } = loadScript("", memberPage());
  addTableElements(byId);
  let prevented = false;
  clicks[0]({ target: byId.get("m_all"), preventDefault: () => { prevented = true; } });
  assert.equal(prevented, false);
  assert.equal(byId.get("m_all").style.display, "initial");
});
