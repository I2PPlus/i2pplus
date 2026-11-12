/**
 * Tier-2 integration tests for the iframe sizing pair the console pages load:
 * apps/routerconsole/jsp/js/iframeResizer/iframeResizer.js in the host page and
 * iframeResizer.contentWindow.js inside the frame.
 *
 * The two files only work by talking to each other, so the contract worth
 * pinning is the handshake, not either half alone — and specifically the
 * handshake the console asks for: initResizer.js calls iFrameResize with
 * interval 0 and the taggedElement height method.
 *
 *   - iFrameResize() adopts the frame and posts it the settings it must use,
 *     rather than sizing it blind;
 *   - the frame answers with its own content height and the host writes that
 *     height onto the iframe element;
 *   - every message names its frame, so two frames on one page keep their own
 *     sizes;
 *   - the host accepts the frame's own origin and refuses any other, and it
 *     ignores traffic from the console's other postMessage users;
 *   - the frame watches its own body (interval 0 selects the MutationObserver,
 *     not the polling timer) and reports its new height when it changes.
 *
 * Both halves are plain scripts with top-level side effects, so each runs in a
 * fresh vm context whose global object is the fake window the test drives. The
 * frame posts to event.source, which is the host window, so the host window's
 * postMessage is its inbox: everything the frame sends lands there, and the test
 * hands it to the host's message listeners the way a browser would. Timers are
 * queued rather than fired, so the test runs the ones a step depends on.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { parseHTML } from "linkedom";
import { installBrowserShim } from "../helpers/browserShim.js";

const DIR = new URL("../../../../apps/routerconsole/jsp/js/iframeResizer/", import.meta.url);
const PARENT_SRC = readFileSync(new URL("iframeResizer.js", DIR), "utf8");
const CHILD_SRC = readFileSync(new URL("iframeResizer.contentWindow.js", DIR), "utf8");

/** The origin both halves believe the other lives at. */
const ORIGIN = "http://127.0.0.1:7657";

/** One frame as a console page declares it. */
const FRAME = `<iframe id="graph" src="${ORIGIN}/graph.html"></iframe>`;

/** The host settings the console's initResizer.js asks for, copied from it. */
const CONSOLE_OPTIONS = {
  interval: 0,
  warningTimeout: 0,
  inPageLinks: true,
  heightCalculationMethod: "taggedElement"
};

/**
 * A window the test can drive: listener registrations are recorded so a message
 * can be delivered exactly where a browser would put it, and postMessage is an
 * inbox, because a message posted to a window arrives there as a message event.
 *
 * @param {Document} document the page the window shows
 * @param {string} href the location that window reports
 * @returns {{window: Object, handlers: Object<string, Function[]>,
 *          inbox: string[], timers: Function[]}} the window, its listeners by
 *   type, everything posted to it, and the timer callbacks it has queued
 */
function fakeWindow(document, href = `${ORIGIN}/index.html`) {
  const handlers = {};
  const inbox = [];
  const timers = [];
  const win = {
    document,
    location: {href, origin: ORIGIN, hostname: "127.0.0.1", hash: ""},
    addEventListener: (type, fn) => {(handlers[type] = handlers[type] || []).push(fn);},
    removeEventListener: () => {},
    postMessage: msg => inbox.push(msg),
    scrollTo: () => {},
    requestAnimationFrame: cb => {cb(0); return 1;},
    cancelAnimationFrame: () => {},
    // recorded, never fired: the tests deliver events themselves and flush the
    // timers they care about
    setTimeout: fn => {timers.push(fn); return timers.length;},
    clearTimeout: () => {},
    setInterval: () => 1,
    clearInterval: () => {},
    matchMedia: () => ({matches: false}),
    // the host watches the page for frames being removed; no test triggers that,
    // so an inert observer is enough to let setup finish
    MutationObserver: class {observe() {} disconnect() {} takeRecords() {return [];}},
    getComputedStyle: () => ({
      marginTop: "0px", marginBottom: "0px", marginLeft: "0px", marginRight: "0px",
      paddingTop: "0px", paddingBottom: "0px", paddingLeft: "0px", paddingRight: "0px",
      borderTopWidth: "0px", borderBottomWidth: "0px", borderLeftWidth: "0px",
      borderRightWidth: "0px", boxSizing: "border-box", whiteSpace: "pre"
    }),
    console
  };
  win.window = win;
  win.self = win;
  win.parent = win;
  // a browser guarantees document.defaultView is the window showing the page;
  // linkedom hands out its own, so point it at the one this test drives
  Object.defineProperty(document, "defaultView", {value: win, configurable: true});
  return {window: win, handlers, inbox, timers};
}

/**
 * Loads the host half of the pair into a page holding the given iframes.
 *
 * @param {string} frames the iframe markup
 * @returns {{document: Document, window: Object, handlers: Object,
 *          inbox: string[], posted: Map<Element, string[]>}} the page, its
 *   window and message listeners, everything posted to that window, and every
 *   message the host posted into each frame
 */
function loadHost(frames) {
  installBrowserShim();
  const html = `<!DOCTYPE HTML><html><head></head><body>${frames}</body></html>`;
  const {document} = parseHTML(html);
  const {window, handlers, inbox, timers} = fakeWindow(document);
  // each frame gets a contentWindow that records what the host posts into it
  const posted = new Map();
  for (const frame of document.querySelectorAll("iframe")) {
    posted.set(frame, []);
    frame.contentWindow = {
      postMessage: msg => posted.get(frame).push(msg),
      addEventListener: () => {},
      location: {origin: ORIGIN}
    };
  }
  vm.runInNewContext(PARENT_SRC, window);
  return {document, window, handlers, inbox, posted, timers};
}

/**
 * Loads the frame half of the pair, showing a page whose tagged element is the
 * given height.
 *
 * @param {number} height the height the frame's tagged element reports
 * @returns {{window: Object, handlers: Object<string, Function[]>,
 *          toParent: string[], observed: Array, timers: Function[],
 *          grow: function(number): void}}
 *   the frame's window and listeners, everything it posted at its parent, the
 *   mutation observers it armed, its queued timers, and a way to make its
 *   content taller
 */
function loadFrame(height) {
  const {document} = parseHTML(
    "<!DOCTYPE HTML><html><head></head><body>" +
    '<span id="endOfPage" data-iframe-height></span></body></html>'
  );
  const tagged = document.querySelector("[data-iframe-height]");
  // linkedom lays nothing out, so the tagged element reports the height the
  // test is asking about
  tagged.getBoundingClientRect = () => ({bottom: height, right: 400, top: 0, left: 0});
  const {window, handlers, timers} = fakeWindow(document, `${ORIGIN}/graph.html`);
  const toParent = [];
  window.parent = {postMessage: msg => toParent.push(msg), location: {origin: ORIGIN}};
  const observed = [];
  window.MutationObserver = class {
    constructor(cb) {this.cb = cb;}
    observe(target, config) {observed.push({target, config, cb: this.cb});}
    disconnect() {observed.disconnected = true;}
  };
  vm.runInNewContext(CHILD_SRC, window);
  return {
    window,
    handlers,
    toParent,
    observed,
    timers,
    /** Makes the frame's content taller, which is what a mutation does. */
    grow(delta) {height += delta;}
  };
}

/**
 * Runs the timer callbacks a window has queued, the way the event loop does.
 * A callback that queues another one runs on the same pass.
 *
 * @param {Function[]} timers the window's queued callbacks
 */
function runTimers(timers) {
  for (let i = 0; i < timers.length; i++) {timers[i]();}
  timers.length = 0;
}

/**
 * Delivers a message to a window's message listeners the way the browser does.
 *
 * @param {Object} handlers the window's listeners by type
 * @param {string} data the message body
 * @param {Object} from the window the message claims to come from, which is the
 *        window the receiving half will post its answer back to
 * @param {string} [origin] the origin the message claims
 */
function deliver(handlers, data, from, origin = ORIGIN) {
  for (const fn of handlers.message || []) {
    fn({data, origin, source: from, type: "message"});
  }
}

/**
 * Runs the host's side of the handshake on one frame and returns the frame half,
 * already answered: the init the host posted is delivered to the frame, and the
 * frame's reply is picked back out of the host's inbox.
 *
 * @param {{window: Object, handlers: Object, inbox: string[],
 *          posted: Map<Element, string[]>}} host the host side
 * @param {Element} frame the iframe element
 * @param {number} height the height the frame reports
 * @returns {{frameSide: Object, answer: string}} the frame half and the size
 *   message it sent back
 */
function handshake(host, frame, height) {
  const init = host.posted.get(frame)[0];
  const frameSide = loadFrame(height);
  deliver(frameSide.handlers, init, host.window);
  const answer = host.inbox.find(msg => msg.startsWith(`[iFrameSizer]${frame.id}:`) &&
                                      msg.includes(`:${height}:`));
  return {frameSide, answer};
}

test("iFrameResize() adopts the frame and starts the handshake", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const toFrame = host.posted.get(frame);
  assert.ok(toFrame.length > 0, "the host posts to the frame before it will accept a size");
  assert.match(toFrame[0], /^\[iFrameSizer\]graph:/,
    "the init message names the frame it is talking to");
  assert.match(toFrame[0], /taggedElement/,
    "the console's heightCalculationMethod reaches the frame through it");
});

test("the frame's reported height lands on the iframe element", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");

  const {answer} = handshake(host, frame, 640);
  assert.ok(answer, `the frame reports its height to the host: ${JSON.stringify(host.inbox)}`);
  deliver(host.handlers, answer, host.window);

  assert.equal(frame.style.height, "640px", "the host sizes the frame to what it reported");
});

test("two frames on one page keep their own sizes", () => {
  const host = loadHost(`<iframe id="a" src="${ORIGIN}/a.html"></iframe>` +
                        `<iframe id="b" src="${ORIGIN}/b.html"></iframe>`);
  host.window.iFrameResize(CONSOLE_OPTIONS, "iframe");
  const [a, b] = [...host.document.querySelectorAll("iframe")];
  assert.match(host.posted.get(a)[0], /^\[iFrameSizer\]a:/);
  assert.match(host.posted.get(b)[0], /^\[iFrameSizer\]b:/);

  for (const [frame, height] of [[a, 300], [b, 900]]) {
    const {answer} = handshake(host, frame, height);
    assert.ok(answer, `frame ${frame.id} reports ${height}px: ${JSON.stringify(host.inbox)}`);
    deliver(host.handlers, answer, host.window);
  }

  assert.equal(a.style.height, "300px");
  assert.equal(b.style.height, "900px", "a size for one frame does not move the other");
});

test("the host accepts the frame's origin and refuses any other", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize({...CONSOLE_OPTIONS, checkOrigin: true}, "#graph");
  const frame = host.document.querySelector("iframe");
  // the frame's origin is the iframe src's origin, which the host reads at setup
  deliver(host.handlers, "[iFrameSizer]graph:640:0:init", host.window, ORIGIN);
  assert.equal(frame.style.height, "640px", "the agreed origin is accepted");

  assert.throws(
    () => deliver(host.handlers, "[iFrameSizer]graph:900:0:init", host.window,
      "https://elsewhere.example"),
    /Unexpected message received from: https:\/\/elsewhere\.example/,
    "a foreign origin is refused loudly, not resized quietly"
  );
  assert.equal(frame.style.height, "640px", "and the frame keeps the size it had");
});

test("the host ignores a message that is not from the pair", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  // the console pages carry other postMessage traffic (initResizer's own
  // scrollToElement commands), which has to pass through untouched
  deliver(host.handlers, {origin: "parent", command: "scrollToElement", id: "x"}, host.window);
  deliver(host.handlers, "some other script's message", host.window);
  assert.notEqual(frame.style.height, "640px");
});

test("the frame watches its own body rather than polling", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  assert.equal(frameSide.observed.length, 1,
    "interval 0 selects the MutationObserver, so the frame reports on its own");
  assert.equal(frameSide.observed[0].target, frameSide.window.document.body);
  assert.equal(frameSide.observed[0].config.childList, true,
    "and it watches for markup that changes the height");
});

test("the frame tells the host again when its own content changes", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  // the frame grows and the observer fires: it re-measures and reports by
  // itself, with no timer polling and no request from the host
  frameSide.grow(200);
  host.inbox.length = 0;
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  runTimers(frameSide.timers);
  const again = host.inbox.find(msg => msg.startsWith("[iFrameSizer]graph:") &&
                                       msg.includes(":840:"));
  assert.ok(again, `the frame reports its new height on its own: ${JSON.stringify(host.inbox)}`);

  deliver(host.handlers, again, frameSide.window);
  assert.equal(frame.style.height, "840px", "and the host follows the frame's growth");
});
