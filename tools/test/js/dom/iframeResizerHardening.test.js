/**
 * Hardening suite for the iframe sizing pair the console pages load, complementing
 * dom/iframeResizer.test.js: that suite pins the happy-path handshake, this one
 * throws everything else at the two halves and asserts they neither crash nor
 * mis-size, and that the measuring stays cheap.
 *
 *   - a frame half receives junk from its host: a bare marker, a message with no
 *     fields, a trigger before it was initialised, an anchor that is empty or
 *     missing, an init from the upstream protocol it is not paired with, and the
 *     same init over and over;
 *   - a host page receives junk from its frame: missing fields, a height that is
 *     not a number, a negative height, an absurd one, an in-page link with no
 *     target or an encoded one, an autoResize payload that is neither true nor
 *     false, an unknown type, and messages that are not strings at all - the
 *     console's other postMessage users;
 *   - the public entry point is called with nonsense: inverted limits, a non-iframe
 *     element, a number, no target, a frame with no id, an origin allow-list;
 *   - a frame taken out of the DOM stops being sized and its id can be adopted
 *     again;
 *   - the cost of measuring stays bounded: one style read per adopted frame across
 *     any number of reports, and a mutation storm collapses to a handful of
 *     reports rather than one per change.
 *
 * Unlike the handshake suite, this one runs on real timers, because the throttle
 * and the debounce it exercises are only observable when they actually fire.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { clearTimeout as clearTimer, setTimeout as timer } from "node:timers";
import vm from "node:vm";
import { parseHTML } from "linkedom";

const DIR = new URL("../../../../apps/routerconsole/jsp/js/iframeResizer/", import.meta.url);
const PARENT_SRC = readFileSync(new URL("iframeResizer.js", DIR), "utf8");
const CHILD_SRC = readFileSync(new URL("iframeResizer.contentWindow.js", DIR), "utf8");
const INIT_SRC = readFileSync(new URL("initResizer.js", DIR), "utf8");

/** The origin both halves believe the other lives at. */
const ORIGIN = "http://127.0.0.1:7657";

/** An init message this pair agrees on. */
const INIT = `[iFrameSizer]graph:i2p1:false:true:2:true:taggedElement`;

// node:timers, not globalThis: other suites in the same process install a shim
// that records timers without firing them, which would stall every wait here.
const sleep = ms => new Promise(resolve => timer(resolve, ms));

/**
 * A window with real timers, so the throttle and the debounce under test actually
 * run, and with the two counters the cost assertions read.
 *
 * @param {Document} document the page the window shows
 * @param {string} href the location that window reports
 * @returns {Object} the window, its listeners by type, everything posted to it,
 *   the observers it armed, and its style and layout read counts
 */
function realTimedWindow(document, href = `${ORIGIN}/index.html`) {
  const handlers = {};
  const inbox = [];
  const observed = [];
  const scrolls = [];
  const counts = {style: 0, rect: 0};

  const add = (type, fn) => {(handlers[type] = handlers[type] || []).push(fn);};
  const remove = (type, fn) => {
    const list = handlers[type] || [];
    const at = list.indexOf(fn);
    if (at >= 0) {list.splice(at, 1);}
  };

  const win = {
    document,
    location: {href, origin: ORIGIN, hash: ""},
    scrollX: 0,
    scrollY: 0,
    addEventListener: add,
    removeEventListener: remove,
    postMessage: (msg, targetOrigin) => inbox.push({msg, targetOrigin}),
    scrollTo: (...args) => scrolls.push(args),
    requestAnimationFrame: cb => {cb(0); return 1;},
    setTimeout: (fn, ms) => timer(fn, ms),
    clearTimeout: id => clearTimer(id),
    matchMedia: () => ({matches: false}),
    MutationObserver: class {
      constructor(cb) {this.cb = cb;}
      observe(target, config) {
        this.record = {target, config, cb: this.cb, disconnected: false};
        observed.push(this.record);
      }
      disconnect() {if (this.record !== undefined) {this.record.disconnected = true;}}
      takeRecords() {return [];}
    },
    box: {
      marginTop: "0px", marginBottom: "0px", paddingTop: "0px", paddingBottom: "0px",
      borderTopWidth: "0px", borderBottomWidth: "0px", boxSizing: "border-box", whiteSpace: "pre"
    },
    getComputedStyle() {counts.style++; return win.box;},
    // the pair logs on its own terms; keep the run quiet and the failures readable
    console: {log() {}, warn() {}, error() {}}
  };
  win.window = win;
  win.self = win;
  win.parent = win;
  Object.defineProperty(document, "defaultView", {value: win, configurable: true});
  document.addEventListener = add;
  document.removeEventListener = remove;
  document.getElementsByName = name => [...document.querySelectorAll(`[name="${name}"]`)];
  return {win, handlers, inbox, observed, scrolls, counts};
}

/**
 * A host page holding the given iframes, with the host half loaded into it.
 *
 * @param {string} frames the iframe markup
 * @returns {Object} the page, its window, listeners, observers and read counts
 */
function loadHost(frames) {
  const {document} = parseHTML(`<!DOCTYPE HTML><html><head></head><body>${frames}</body></html>`);
  const ctx = realTimedWindow(document);
  const posted = new Map();
  for (const frame of document.querySelectorAll("iframe")) {
    posted.set(frame, []);
    frame.contentWindow = {
      postMessage: (msg, targetOrigin) => posted.get(frame).push({msg, targetOrigin}),
      addEventListener: () => {},
      removeEventListener: () => {},
      scrollTo: () => {},
      location: {origin: ORIGIN}
    };
    frame.getBoundingClientRect = () => ({top: 0, bottom: 400, left: 0, right: 800});
  }
  vm.runInNewContext(PARENT_SRC, ctx.win);
  return {document, posted, ...ctx};
}

/**
 * A framed page, with the frame half loaded into it and its tagged element
 * reporting the given height.
 *
 * @param {number} height the height the tagged element reports
 * @param {string} markup the rest of the frame's body
 * @returns {Object} the page, its window, listeners, observers and read counts
 */
function loadFrame(height, markup = '<span id="endOfPage" data-iframe-height></span>') {
  const {document} = parseHTML(`<!DOCTYPE HTML><html><head></head><body>${markup}</body></html>`);
  const ctx = realTimedWindow(document, `${ORIGIN}/graph.html`);
  const tagged = document.querySelector("[data-iframe-height]");
  if (tagged !== null) {
    tagged.getBoundingClientRect = () => {
      ctx.counts.rect++;
      return {top: 0, bottom: height, left: 0, right: 400};
    };
  }
  ctx.win.parent = {postMessage: () => {}, location: {origin: ORIGIN}};
  vm.runInNewContext(CHILD_SRC, ctx.win);
  return {document, tagged, ...ctx};
}

/**
 * Delivers a message to a window's message listeners.
 *
 * @param {Object} ctx the window's harness
 * @param {*} data the message body, of whatever type the caller wants to try
 * @param {Object} from the window it claims to come from
 * @param {string} [origin] the origin it claims
 * @returns {void}
 */
function deliver(ctx, data, from, origin = ORIGIN) {
  for (const fn of ctx.handlers.message || []) {
    fn({data, origin, source: from, type: "message"});
  }
}

/** The body observer the frame half arms, found by its config. */
function bodyObserver(ctx) {
  return ctx.observed.find(entry => entry.config.childList === true && entry.config.attributes === true);
}

test("the frame survives junk from its host", () => {
  const frame = loadFrame(400);
  const host = {postMessage: () => {}, location: {origin: ORIGIN}};
  const junk = ["[iFrameSizer]", "[iFrameSizer]graph", "[iFrameSizer]resize",
                "[iFrameSizer]moveToAnchor:", "[iFrameSizer]moveToAnchor:#nowhere",
                "[iFrameSizer]graph:4.x:false:2:true:taggedElement",
                `${INIT}:extra:fields`, INIT, INIT];

  for (const msg of junk) {deliver(frame, msg, host);}

  assert.equal((frame.handlers.message || []).length, 1, "and never arms a second listener");
  assert.equal(frame.document.querySelectorAll("[data-iframe-height]").length, 1,
    "nor adds a second marker, however many inits it takes");
});

test("the host survives junk from its frame", () => {
  const page = loadHost(`<iframe id="graph" src="${ORIGIN}/g.html"></iframe>`);
  page.win.iFrameResize({heightCalculationMethod: "taggedElement", minHeight: 1, tolerance: 2}, "#graph");
  const frame = page.document.getElementById("graph");
  const junk = ["[iFrameSizer]graph:size", "[iFrameSizer]graph:abc:0:size", "[iFrameSizer]graph:-50:0:size",
                "[iFrameSizer]graph:1e9:0:size", "[iFrameSizer]graph:0:0:inPageLink:",
                "[iFrameSizer]graph:0:0:inPageLink:#%20space", "[iFrameSizer]graph:0:0:autoResize:maybe",
                "[iFrameSizer]graph:0:0:unknownType", "[iFrameSizer]graph:640:0:size:extra:payload"];

  for (const msg of junk) {deliver(page, msg, frame.contentWindow);}
  for (const msg of [null, undefined, 42, {origin: "parent"}, "[iFrameResizerChild]Ready"]) {
    deliver(page, msg, frame.contentWindow);
  }

  deliver(page, "[iFrameSizer]graph:640:0:size", frame.contentWindow);
  assert.equal(frame.style.height, "640px", "a good size still lands after all that");
});

test("a paused frame refuses a resize and a resumed one accepts it", () => {
  const page = loadHost(`<iframe id="graph" src="${ORIGIN}/g.html"></iframe>`);
  page.win.iFrameResize({}, "#graph");
  const frame = page.document.getElementById("graph");

  deliver(page, "[iFrameSizer]graph:0:0:autoResize:false", frame.contentWindow);
  page.posted.get(frame).length = 0;
  frame.iFrameResizer.resize();
  assert.equal(page.posted.get(frame).length, 0, "nothing is asked of a paused frame");

  deliver(page, "[iFrameSizer]graph:0:0:autoResize:true", frame.contentWindow);
  page.posted.get(frame).length = 0;
  frame.iFrameResizer.resize();
  assert.equal(page.posted.get(frame).length, 1, "and one thing of a resumed one");
});

test("iFrameResize() refuses nonsense instead of sizing on it", () => {
  const page = loadHost(`<iframe id="a" src="${ORIGIN}/a.html"></iframe>`);

  assert.throws(() => page.win.iFrameResize({minHeight: 500, maxHeight: 100}, "#a"),
    /minHeight can not be greater than maxHeight/);
  assert.throws(() => page.win.iFrameResize({}, page.document.body), /Expected <IFRAME> tag/);
  assert.throws(() => page.win.iFrameResize({}, 42), /Unexpected data type/);

  const adopted = page.win.iFrameResize({}, undefined);
  assert.equal(adopted.length, 1, "no target adopts every frame on the page");
  assert.equal(adopted[0].id, "a");
});

test("a frame that arrives without an id gets one", () => {
  const page = loadHost(`<iframe src="${ORIGIN}/noid.html"></iframe>`);
  page.win.iFrameResize({}, undefined);
  assert.match(page.document.querySelector("iframe").id, /^iFrameResizer\d+$/);
});

test("checkOrigin as a list accepts the listed and refuses the rest", () => {
  const page = loadHost(`<iframe id="x" src="${ORIGIN}/x.html"></iframe>`);
  page.win.iFrameResize({checkOrigin: ["https://a.example", "https://b.example"]}, "#x");
  const frame = page.document.getElementById("x");

  assert.doesNotThrow(() => deliver(page, "[iFrameSizer]x:100:0:size", frame.contentWindow,
    "https://a.example"));
  assert.equal(frame.style.height, "100px", "the listed origin is sized");
  assert.throws(() => deliver(page, "[iFrameSizer]x:200:0:size", frame.contentWindow, "https://c.example"),
    /Unexpected message received from: https:\/\/c\.example/);
  assert.equal(frame.style.height, "100px", "and the unlisted one leaves the size alone");
});

test("a frame taken out of the page stops being sized", () => {
  const page = loadHost(`<div id="wrap"><iframe id="graph" src="${ORIGIN}/g.html"></iframe></div>`);
  page.win.iFrameResize({}, "#graph");
  const frame = page.document.getElementById("graph");

  page.document.getElementById("wrap").removeChild(frame);
  // linkedom never runs the observer for us, so run the recorded callback the way
  // the browser would when the frame is removed
  for (const entry of page.observed) {entry.cb([{type: "childList", removedNodes: [frame], addedNodes: []}]);}

  assert.doesNotThrow(() => deliver(page, "[iFrameSizer]graph:300:0:size", frame.contentWindow));
  assert.equal(frame.style.height, "", "the removed frame is no longer sized");

  page.win.iFrameResize({}, "#graph");
  assert.ok(page.document.getElementById("graph") === null, "and re-adopting a gone frame is harmless");
});

test("the host reads a frame's box once, however many reports arrive", () => {
  const page = loadHost(`<iframe id="graph" src="${ORIGIN}/g.html"></iframe>`);
  page.win.iFrameResize({tolerance: 0}, "#graph");
  const frame = page.document.getElementById("graph");
  const atSetup = page.counts.style;

  for (let i = 1; i <= 50; i++) {deliver(page, `[iFrameSizer]graph:${400 + i}:0:size`, frame.contentWindow);}

  assert.equal(page.counts.style, atSetup,
    "50 size reports cost no extra getComputedStyle() call");
  assert.equal(frame.style.height, "450px", "and every one of them is applied");
});

test("the frame measures a bounded number of times per burst", async () => {
  const frame = loadFrame(400);
  deliver(frame, INIT, {postMessage: () => {}, location: {origin: ORIGIN}});
  const observer = bodyObserver(frame);
  frame.counts.style = 0;
  frame.counts.rect = 0;

  for (let i = 0; i < 10; i++) {observer.cb([{type: "childList", target: frame.document.body}]);}
  await sleep(60);

  assert.ok(frame.counts.rect <= 2, `10 mutations cost ${frame.counts.rect} measurements`);
  assert.ok(frame.counts.style <= 2, `10 mutations cost ${frame.counts.style} style reads`);
});

test("a mutation storm costs a handful of reports, not one per change", async () => {
  const frame = loadFrame(400);
  const target = {posts: 0, postMessage() {this.posts++;}, location: {origin: ORIGIN}};
  deliver(frame, INIT, target);
  const observer = bodyObserver(frame);

  // the page gets taller with every mutation, so any measurement the throttle
  // lets through is a change the tolerance cannot swallow
  let mutations = 0;
  frame.tagged.getBoundingClientRect = () => ({top: 0, bottom: 400 + mutations, left: 0, right: 400});

  target.posts = 0;
  const started = Date.now();
  for (let i = 0; i < 300; i++) {
    mutations += 1;
    observer.cb([{type: "childList", target: frame.document.body}]);
  }
  await sleep(120);

  assert.ok(target.posts >= 1, `300 mutations in ${Date.now() - started}ms sent ${target.posts} reports`);
  assert.ok(target.posts <= 12, `300 mutations in ${Date.now() - started}ms sent ${target.posts} reports`);
});

test("the frame arms its content triggers, and nothing else", () => {
  const frame = loadFrame(400);
  deliver(frame, INIT, {postMessage: () => {}, location: {origin: ORIGIN}});
  const armed = Object.keys(frame.handlers).filter(type => type !== "message").sort();

  // content and resources, plus the end of an animation or transition, which is
  // the first moment the new geometry is real
  for (const type of ["load", "error", "hashchange", "input", "readystatechange",
                      "animationend", "transitionend"]) {
    assert.ok(armed.includes(type), `${type} is armed`);
  }
  // interaction, mid-flight geometry, and orientation, which the host answers by
  // asking every frame it holds to re-measure
  for (const type of ["mousemove", "mousedown", "touchstart", "touchmove", "scroll", "print", "click",
                      "animationstart", "animationiteration", "transitionstart", "orientationchange"]) {
    assert.ok(!armed.includes(type), `${type} is not armed`);
  }
});

test("a load event on the document itself does not upset the frame", () => {
  const frame = loadFrame(400);
  deliver(frame, INIT, {postMessage: () => {}, location: {origin: ORIGIN}});

  assert.ok((frame.handlers.load || []).length > 0, "the frame watches load on its document");
  assert.doesNotThrow(() => {
    for (const fn of frame.handlers.load) {fn({target: frame.document});}
  }, "including one whose target is the document, which has no tagName");
});

test("initResizer() follows the frame element when the page replaces it", () => {
  const page = loadHost(`<iframe id="graph" src="${ORIGIN}/g.html"></iframe>`);
  vm.runInNewContext(INIT_SRC, page.win);
  page.win.initResizer("graph");

  const first = page.document.getElementById("graph");
  const oldInside = parseHTML('<!DOCTYPE HTML><html><body><div id="panel"></div></body></html>').document;
  oldInside.getElementById("panel").getBoundingClientRect = () => ({top: 111, bottom: 200, left: 0, right: 0});
  first.contentWindow.document = oldInside;

  // the page navigates in a way that swaps the element out and keeps its id
  first.remove();
  const second = parseHTML('<!DOCTYPE HTML><html><body><iframe id="graph" src="g.html"></iframe>' +
    "</body></html>").document.getElementById("graph");
  const toNewFrame = [];
  second.contentWindow = {postMessage: msg => toNewFrame.push(msg), addEventListener: () => {},
                          removeEventListener: () => {}, scrollTo: () => {}, location: {origin: ORIGIN},
                          document: null};
  const newInside = parseHTML('<!DOCTYPE HTML><html><body><div id="panel"></div></body></html>').document;
  newInside.getElementById("panel").getBoundingClientRect = () => ({top: 777, bottom: 900, left: 0, right: 0});
  second.contentWindow.document = newInside;
  page.document.body.appendChild(second);
  page.win.initResizer("graph");

  const wired = (page.handlers.message || []).length;
  page.win.initResizer("graph");
  assert.equal((page.handlers.message || []).length, wired,
    "and re-initialising the same id never wires it twice");

  deliver(page, {origin: "parent", command: "scrollToElement", id: "panel"}, second.contentWindow);
  assert.equal(page.scrolls.length, 1, "the scroll request is answered");
  assert.equal(page.scrolls[0][0].top, 777,
    "and it is answered against the frame that is on the page now, not the one first adopted");

  page.win.initResizer("graph");
  toNewFrame.length = 0;
  deliver(page, {action: "resize", iframeId: "graph"}, second.contentWindow);
  assert.deepEqual(toNewFrame.filter(msg => msg === "[iFrameSizer]resize"), ["[iFrameSizer]resize"],
    "and a re-measure request reaches the current element too");
});
