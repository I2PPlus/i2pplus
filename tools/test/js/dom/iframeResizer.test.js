/**
 * Tier-2 integration tests for the iframe sizing pair the console pages load:
 * apps/routerconsole/jsp/js/iframeResizer/iframeResizer.js in the host page and
 * iframeResizer.contentWindow.js inside the frame.
 *
 * The two files only work by talking to each other, so the contract worth
 * pinning is the handshake, not either half alone — and specifically the
 * handshake the console asks for: initResizer.js calls iFrameResize with the
 * taggedElement height method and no polling interval.
 *
 *   - iFrameResize() adopts the frame and posts it the settings it must measure
 *     with, tagged with the fork's protocol so a stale half warns;
 *   - the frame answers with its own content height and the host writes that
 *     height onto the iframe element, clamped to the limits the host set;
 *   - every message names its frame, so two frames on one page keep their own
 *     sizes;
 *   - the host accepts the frame's own origin and refuses any other, and it
 *     ignores traffic from the console's other postMessage users;
 *   - the frame watches its own body (the MutationObserver, not a polling
 *     timer) and reports its new height when it changes, when a resource
 *     finishes loading, and when it stops being hidden;
 *   - the in-page-link path works in both directions, because the Tunnel
 *     Manager and I2PSnark scroll their host pages through it.
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
import { parseHTML, Event } from "linkedom";

const DIR = new URL("../../../../apps/routerconsole/jsp/js/iframeResizer/", import.meta.url);
const PARENT_SRC = readFileSync(new URL("iframeResizer.js", DIR), "utf8");
const CHILD_SRC = readFileSync(new URL("iframeResizer.contentWindow.js", DIR), "utf8");
const INIT_SRC = readFileSync(new URL("initResizer.js", DIR), "utf8");

/** The origin both halves believe the other lives at. */
const ORIGIN = "http://127.0.0.1:7657";

/** The fork's protocol tag, which the init message carries. */
const PROTOCOL = "i2p1";

/** The init message the host sends for the console's settings. */
const INIT_MSG = `[iFrameSizer]graph:${PROTOCOL}:false:true:2:true:taggedElement`;

/** One frame as a console page declares it. */
const FRAME = `<iframe id="graph" src="${ORIGIN}/graph.html"></iframe>`;

/** The host settings the console's initResizer.js asks for, copied from it. */
const CONSOLE_OPTIONS = {
  heightCalculationMethod: "taggedElement",
  inPageLinks: true,
  minHeight: 1,
  tolerance: 2
};

/**
 * A window the test can drive: listener registrations are recorded so a message
 * can be delivered exactly where a browser would put it, and postMessage is an
 * inbox, because a message posted to a window arrives there as a message event.
 *
 * @param {Document} document the page the window shows
 * @param {string} href the location that window reports
 * @returns {Object} the window plus the state the test inspects: its listeners
 *   by type, everything posted to it, its queued timers, its scroll calls, the
 *   observers it armed and how often it read a computed style
 */
function fakeWindow(document, href = `${ORIGIN}/index.html`) {
  const handlers = {};
  const inbox = [];
  const timers = new Map();
  const scrolls = [];
  let timerId = 0;
  const observed = [];

  const add = (type, fn) => {(handlers[type] = handlers[type] || []).push(fn);};
  const remove = (type, fn) => {
    const list = handlers[type] || [];
    const at = list.indexOf(fn);
    if (at >= 0) {list.splice(at, 1);}
  };

  const win = {
    document,
    location: {href, origin: ORIGIN, hostname: "127.0.0.1", hash: ""},
    scrollX: 0,
    scrollY: 0,
    addEventListener: add,
    removeEventListener: remove,
    postMessage: (msg, targetOrigin) => inbox.push({msg, targetOrigin}),
    scrollTo: (...args) => scrolls.push(args),
    requestAnimationFrame: cb => {cb(0); return 1;},
    cancelAnimationFrame: () => {},
    // recorded, never fired: the tests deliver events themselves and flush the
    // timers they care about. clearTimeout() really does drop one, so a test can
    // tell a cancelled measurement from one that was merely still queued.
    setTimeout: fn => {timerId += 1; timers.set(timerId, fn); return timerId;},
    clearTimeout: id => {timers.delete(id);},
    setInterval: () => 1,
    clearInterval: () => {},
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
    // the box around the frame, which the host reads once and caches
    box: {
      marginTop: "0px", marginBottom: "0px", marginLeft: "0px", marginRight: "0px",
      paddingTop: "0px", paddingBottom: "0px", paddingLeft: "0px", paddingRight: "0px",
      borderTopWidth: "0px", borderBottomWidth: "0px", borderLeftWidth: "0px",
      borderRightWidth: "0px", boxSizing: "border-box", whiteSpace: "pre"
    },
    getComputedStyle() {
      win.styleReads++;
      return win.box;
    },
    styleReads: 0,
    console
  };
  win.window = win;
  win.self = win;
  win.parent = win;
  // a browser guarantees document.defaultView is the window showing the page;
  // linkedom hands out its own, so point it at the one this test drives
  Object.defineProperty(document, "defaultView", {value: win, configurable: true});
  // the frame half watches for load and error on the document, in capture
  // phase, so those registrations have to land in the same record
  document.addEventListener = add;
  document.removeEventListener = remove;
  // linkedom has no getElementsByName(), which both halves use for the second
  // way an anchor can name its target
  document.getElementsByName = name => [...document.querySelectorAll(`[name="${name}"]`)];
  return {window: win, handlers, inbox, timers, scrolls, observed};
}

/**
 * Loads the host half of the pair into a page holding the given iframes.
 *
 * @param {string} frames the iframe markup
 * @returns {Object} the page, its window and listeners, everything posted to
 *   that window, and every message the host posted into each frame
 */
function loadHost(frames) {
  const html = `<!DOCTYPE HTML><html><head></head><body>${frames}</body></html>`;
  const {document} = parseHTML(html);
  const {window, handlers, inbox, timers, scrolls, observed} = fakeWindow(document);
  // each frame gets a contentWindow that records what the host posts into it
  const posted = new Map();
  for (const frame of document.querySelectorAll("iframe")) {
    posted.set(frame, []);
    frame.contentWindow = {
      postMessage: (msg, targetOrigin) => posted.get(frame).push({msg, targetOrigin}),
      addEventListener: () => {},
      removeEventListener: () => {},
      location: {origin: ORIGIN}
    };
  }
  vm.runInNewContext(PARENT_SRC, window);
  return {document, window, handlers, inbox, posted, timers, scrolls, observed};
}

/**
 * Loads a console page the way the console pages load it: the host half of the
 * pair plus initResizer.js, which supplies the console's settings and the
 * handling of the messages the framed webapps send on their own.
 *
 * @param {string} frames the iframe markup
 * @returns {Object} the page, its window and listeners, and every message the
 *   host posted into each frame
 */
function loadConsole(frames) {
  const {document} = parseHTML(`<!DOCTYPE HTML><html><head></head><body>${frames}</body></html>`);
  const {window, handlers, inbox, timers, scrolls, observed} = fakeWindow(document);
  const posted = new Map();
  for (const frame of document.querySelectorAll("iframe")) {
    posted.set(frame, []);
    frame.contentWindow = {
      postMessage: (msg, targetOrigin) => posted.get(frame).push({msg, targetOrigin}),
      addEventListener: () => {},
      removeEventListener: () => {},
      scrollTo: () => {},
      location: {origin: ORIGIN},
      // the framed page's own document, which initResizer reaches into when the
      // framed page asks to be scrolled to one of its elements
      document: null
    };
  }
  vm.runInNewContext(`${PARENT_SRC}\n${INIT_SRC}`, window);
  return {document, window, handlers, inbox, posted, timers, scrolls, observed};
}

/**
 * Loads the frame half of the pair, showing a page whose tagged element is the
 * given height.
 *
 * @param {number} height the height the frame's tagged element reports
 * @param {string} [markup] the rest of the frame's body
 * @returns {Object} the frame's window and listeners, everything it posted at
 *   its parent, the observers it armed, its queued timers, and ways to change
 *   its content height
 */
function loadFrame(height, markup = '<span id="endOfPage" data-iframe-height></span>') {
  const {document} = parseHTML(`<!DOCTYPE HTML><html><head></head><body>${markup}</body></html>`);
  const tagged = document.querySelector("[data-iframe-height]");
  // linkedom lays nothing out, so the tagged element reports the height the
  // test is asking about
  if (tagged !== null) {
    tagged.getBoundingClientRect = () => ({bottom: height, right: 400, top: 0, left: 0});
  }
  const {window, handlers, timers, observed} = fakeWindow(document, `${ORIGIN}/graph.html`);
  const toParent = [];
  window.parent = {
    postMessage: (msg, targetOrigin) => toParent.push({msg, targetOrigin}),
    location: {origin: ORIGIN}
  };
  vm.runInNewContext(CHILD_SRC, window);
  return {
    document,
    window,
    handlers,
    toParent,
    observed,
    timers,
    /** Makes the frame's content taller, which is what a mutation does. */
    grow(delta) {height += delta;},
    /** Makes the frame's content a fixed height. */
    setHeight(value) {height = value;}
  };
}

/**
 * Runs the timer callbacks a window has queued, the way the event loop does.
 * A callback that queues another one runs on the same pass, and a callback the
 * window cleared never runs at all.
 *
 * @param {Map<number, Function>} timers the window's queued callbacks by id
 * @returns {void}
 */
function runTimers(timers) {
  while (timers.size > 0) {
    const [id, fn] = timers.entries().next().value;
    timers.delete(id);
    fn();
  }
}

/**
 * Delivers a message to a window's message listeners the way the browser does.
 *
 * @param {Object} handlers the window's listeners by type
 * @param {string} data the message body
 * @param {Object} from the window the message claims to come from, which is the
 *        window the receiving half will post its answer back to
 * @param {string} [origin] the origin the message claims
 * @returns {void}
 */
function deliver(handlers, data, from, origin = ORIGIN) {
  for (const fn of handlers.message || []) {
    fn({data, origin, source: from, type: "message"});
  }
}

/**
 * Splits a message the frame sent the host into its fields.
 *
 * @param {string} msg the message body
 * @returns {?Object} the id, size, type and payload, or null if the message is
 *   not part of the pair's protocol
 */
function parseChild(msg) {
  if (!msg.startsWith("[iFrameSizer]")) {return null;}
  const [id, height, width, type, ...rest] = msg.slice("[iFrameSizer]".length).split(":");
  return {id, height: Number(height), width: Number(width), type, payload: rest.join(":")};
}

/**
 * Finds the last message of a type in a box of recorded postMessage calls.
 *
 * @param {Array<{msg: string}>} box the recorded messages
 * @param {string} type the message type to look for
 * @returns {?string} the message body, or null if there was none
 */
function lastOf(box, type) {
  const found = box.filter(entry => {
    const data = parseChild(entry.msg);
    return data !== null && data.type === type;
  }).pop();
  return found === undefined ? null : found.msg;
}

/**
 * Runs the host's side of the handshake on one frame and returns the frame half,
 * already answered: the init the host posted is delivered to the frame, and the
 * frame's reply is picked back out of the host's inbox.
 *
 * @param {Object} host the host side
 * @param {Element} frame the iframe element
 * @param {number} height the height the frame reports
 * @returns {{frameSide: Object, answer: string}} the frame half and the size
 *   message it sent back
 */
function handshake(host, frame, height) {
  const init = host.posted.get(frame)[0].msg;
  const frameSide = loadFrame(height);
  deliver(frameSide.handlers, init, host.window);
  const answer = lastOf(host.inbox, "init");
  return {frameSide, answer};
}

/**
 * Hands the host a message from its frame and returns the height it wrote.
 *
 * @param {Object} host the host side
 * @param {string} msg the message from the frame
 * @param {Object} [from] the window the message comes from
 * @returns {void}
 */
function hostApplies(host, msg, from = host.window) {
  deliver(host.handlers, msg, from);
}

test("iFrameResize() adopts the frame and starts the handshake", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const toFrame = host.posted.get(frame);

  assert.ok(toFrame.length > 0, "the host posts to the frame before it will accept a size");
  // names the frame, carries the fork's protocol tag, then logging, autoResize,
  // tolerance, inPageLinks and the height method
  assert.equal(toFrame[0].msg, INIT_MSG,
    "the init message is the settings the frame has to measure with");
  assert.equal(toFrame[0].targetOrigin, ORIGIN,
    "and it is pinned to the frame's own origin, which checkOrigin defaults to guarding");
});

test("the frame's reported height lands on the iframe element", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");

  const {answer} = handshake(host, frame, 640);
  assert.ok(answer, `the frame reports its height to the host: ${JSON.stringify(host.inbox)}`);
  const data = parseChild(answer);
  assert.equal(data.id, "graph", "every message names its frame");
  assert.equal(data.height, 640);
  assert.equal(data.width, 0, "the fork sizes heights only");

  hostApplies(host, answer);
  assert.equal(frame.style.height, "640px", "the host sizes the frame to what it reported");
});

test("two frames on one page keep their own sizes", () => {
  const host = loadHost(`<iframe id="a" src="${ORIGIN}/a.html"></iframe>` +
                        `<iframe id="b" src="${ORIGIN}/b.html"></iframe>`);
  host.window.iFrameResize(CONSOLE_OPTIONS, "iframe");
  const [a, b] = [...host.document.querySelectorAll("iframe")];
  assert.match(host.posted.get(a)[0].msg, new RegExp(`^\\[iFrameSizer\\]a:${PROTOCOL}:`));
  assert.match(host.posted.get(b)[0].msg, new RegExp(`^\\[iFrameSizer\\]b:${PROTOCOL}:`));

  for (const [frame, height] of [[a, 300], [b, 900]]) {
    const {answer} = handshake(host, frame, height);
    assert.ok(answer, `frame ${frame.id} reports ${height}px: ${JSON.stringify(host.inbox)}`);
    hostApplies(host, answer);
  }

  assert.equal(a.style.height, "300px");
  assert.equal(b.style.height, "900px", "a size for one frame does not move the other");
});

test("the host accepts the frame's origin and refuses any other", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize({...CONSOLE_OPTIONS, checkOrigin: true}, "#graph");
  const frame = host.document.querySelector("iframe");
  // the frame's origin is the iframe src's origin, which the host reads at setup
  assert.equal(host.posted.get(frame)[0].targetOrigin, ORIGIN,
    "with checkOrigin the host pins its replies to the frame's origin");

  hostApplies(host, "[iFrameSizer]graph:640:0:size");
  assert.equal(frame.style.height, "640px", "the agreed origin is accepted");

  assert.throws(
    () => deliver(host.handlers, "[iFrameSizer]graph:900:0:size", host.window,
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
  hostApplies(host, {origin: "parent", command: "scrollToElement", id: "x"});
  hostApplies(host, "some other script's message");
  hostApplies(host, "[iFrameSizer]nosuchframe:640:0:size");
  assert.notEqual(frame.style.height, "640px");
});

test("the host refuses a size from a frame half that predates this protocol", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const frameSide = loadFrame(640);

  // an old frame half answers an init it did not understand, and the host says
  // so instead of measuring garbage
  deliver(frameSide.handlers, "[iFrameSizer]graph:4.x:false:2:true:taggedElement", host.window);
  assert.equal(lastOf(host.inbox, "init"), null,
    "a frame that was not initialized with this protocol stays quiet");
  assert.equal(frame.style.height, "", "and the frame is never sized");
});

test("the frame watches its own body rather than polling", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  assert.equal(frameSide.observed.length, 1,
    "the frame arms one observer, so it reports on its own with no timer");
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
  const again = lastOf(host.inbox, "mutationObserver");
  assert.ok(again, `the frame reports its new height on its own: ${JSON.stringify(host.inbox)}`);
  assert.equal(parseChild(again).height, 840);

  hostApplies(host, again);
  assert.equal(frame.style.height, "840px", "and the host follows the frame's growth");
});

test("the frame stays quiet while its content is unchanged", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  host.inbox.length = 0;
  // a mutation that does not change the height must not cost a round trip
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  runTimers(frameSide.timers);
  assert.equal(lastOf(host.inbox, "mutationObserver"), null);
});

test("the frame reports again when a resource finishes loading", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  // an image's height arrives long after the mutation that inserted the tag,
  // so the frame listens for load on its own document
  assert.ok(frameSide.handlers.load, "the frame watches load on the document");
  assert.ok(frameSide.handlers.error, "and error, for an image that never arrives");
  frameSide.grow(200);
  host.inbox.length = 0;
  for (const fn of frameSide.handlers.load) {fn({target: {tagName: "IMG"}});}
  runTimers(frameSide.timers);

  const again = lastOf(host.inbox, "resourceLoad");
  assert.ok(again, `a loaded image re-measures the page: ${JSON.stringify(host.inbox)}`);
  assert.equal(parseChild(again).height, 840);
});

test("the host clamps the frame's height to the limits it set", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize({...CONSOLE_OPTIONS, minHeight: 400, maxHeight: 700}, "#graph");
  const frame = host.document.querySelector("iframe");

  hostApplies(host, "[iFrameSizer]graph:100:0:size");
  assert.equal(frame.style.height, "400px", "a short page is held at minHeight");
  hostApplies(host, "[iFrameSizer]graph:1000:0:size");
  assert.equal(frame.style.height, "700px", "a tall page is held at maxHeight");
});

test("a height change inside the tolerance is not reported", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize({...CONSOLE_OPTIONS, tolerance: 2}, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  // the tolerance is applied where the measurement is taken, so a change too
  // small to see never costs a round trip or a style write
  host.inbox.length = 0;
  frameSide.setHeight(641);
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  runTimers(frameSide.timers);
  assert.equal(lastOf(host.inbox, "mutationObserver"), null,
    "a pixel of scrollbar jitter is not a resize");

  frameSide.setHeight(660);
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  runTimers(frameSide.timers);
  const again = lastOf(host.inbox, "mutationObserver");
  assert.ok(again, `a real change is still reported: ${JSON.stringify(host.inbox)}`);
  assert.equal(parseChild(again).height, 660);
});

test("the host reads the frame's box once and reuses it", () => {
  const host = loadHost(FRAME);
  const frame = host.document.querySelector("iframe");
  host.window.box.paddingTop = "10px";
  host.window.box.paddingBottom = "10px";
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");

  hostApplies(host, "[iFrameSizer]graph:640:0:size");
  assert.equal(frame.style.height, "660px", "the box around the frame is part of its height");
  hostApplies(host, "[iFrameSizer]graph:700:0:size");
  assert.equal(frame.style.height, "720px");
  assert.equal(host.window.styleReads, 1,
    "getComputedStyle() runs once per frame, not once per report");
});

test("a frame measured while hidden is re-measured once it becomes visible", () => {
  const host = loadHost(FRAME);
  const frame = host.document.querySelector("iframe");
  host.window.iFrameResize({...CONSOLE_OPTIONS, minHeight: 0}, "#graph");

  // every console frame starts inside display:none, so the first measurement
  // is often nothing at all
  frame.offsetParent = null;
  hostApplies(host, "[iFrameSizer]graph:0:0:init");
  assert.equal(frame.style.height, "0px");

  const watching = host.observed.filter(entry => entry.config.attributes === true);
  assert.equal(watching.length, 1, "the host watches the page for the frame becoming visible");

  frame.offsetParent = frame.parentNode;
  host.posted.get(frame).length = 0;
  watching[0].cb([]);
  runTimers(host.timers);
  assert.deepEqual(host.posted.get(frame).map(entry => entry.msg), ["[iFrameSizer]resize"],
    "the host asks the frame for its height again");
  assert.equal(watching[0].disconnected, true, "and the observer is not left running");

  // the frame answers with a real height and the host sizes the frame to it
  hostApplies(host, "[iFrameSizer]graph:500:0:resizeParent");
  assert.equal(frame.style.height, "500px");
});

test("the host asks the frame to re-measure on a window resize", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  handshake(host, frame, 640);

  host.posted.get(frame).length = 0;
  frame.iFrameResizer.resize();
  assert.deepEqual(host.posted.get(frame).map(entry => entry.msg), ["[iFrameSizer]resize"],
    "the host public API asks the frame for a fresh measurement");

  // the Tunnel Manager pauses reporting while it shows its own overlay
  hostApplies(host, "[iFrameSizer]graph:0:0:autoResize:false");
  host.posted.get(frame).length = 0;
  frame.iFrameResizer.resize();
  assert.equal(host.posted.get(frame).length, 0,
    "and it does not ask a frame that has paused auto-resizing");
});

test("the frame stops measuring when the page asks it to pause", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  frameSide.grow(300);
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  assert.ok(lastOf(host.inbox, "mutationObserver"), "the frame reports its growth at first");

  frameSide.grow(100);
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  // inside the throttle window this measurement is only scheduled, and the
  // Tunnel Manager pauses before it runs
  assert.equal(frameSide.window.parentIFrame.autoResize(false), false);
  assert.equal(frameSide.observed[0].disconnected, true, "the body observer is disconnected");
  assert.equal(frameSide.handlers.load.length, 0, "the resource listeners are removed");

  host.inbox.length = 0;
  runTimers(frameSide.timers);
  assert.equal(lastOf(host.inbox, "mutationObserver"), null,
    "a measurement scheduled before the pause does not land");

  assert.equal(frameSide.window.parentIFrame.autoResize(true), true, "and it can be resumed");
});

test("the frame adds its own end-of-page marker when the page has none", () => {
  const frameSide = loadFrame(300, "<p>content</p>");
  deliver(frameSide.handlers, INIT_MSG, {postMessage: () => {}, location: {origin: ORIGIN}});

  const markers = frameSide.document.querySelectorAll("[data-iframe-height]");
  assert.equal(markers.length, 1, "the frame can report a height without the page marking it");
});

test("the frame does not duplicate a marker the page already has", () => {
  const frameSide = loadFrame(300, '<span id="endOfPage" data-iframe-height></span>');
  deliver(frameSide.handlers, INIT_MSG, {postMessage: () => {}, location: {origin: ORIGIN}});

  assert.equal(frameSide.document.querySelectorAll("[data-iframe-height]").length, 1,
    "the page's own marker is the one that gets measured");
});

test("a repeated init does not re-arm the frame", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  const init = host.posted.get(frame)[0].msg;
  const listeners = Object.keys(frameSide.handlers).map(type => frameSide.handlers[type].length);
  deliver(frameSide.handlers, init, host.window);
  deliver(frameSide.handlers, init, host.window);

  assert.equal(frameSide.observed.length, 1, "the body observer is armed once");
  const after = Object.keys(frameSide.handlers).map(type => frameSide.handlers[type].length);
  assert.deepEqual(after, listeners, "and no listener is registered twice");
});

test("the frame pins its replies to the host's origin", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  frameSide.grow(200);
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  runTimers(frameSide.timers);

  assert.ok(host.inbox.length > 1, "the frame keeps reporting to the host it met");
  for (const entry of host.inbox) {
    assert.equal(entry.targetOrigin, ORIGIN,
      "the frame only ever posts to the origin the host message came from");
  }
});

test("the host re-inits a frame half that loaded after it looked", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  host.posted.get(frame).length = 0;

  deliver(host.handlers, "[iFrameResizerChild]Ready", frame.contentWindow);
  assert.match(host.posted.get(frame)[0].msg, new RegExp(`^\\[iFrameSizer\\]graph:${PROTOCOL}:`),
    "the frame asking for init gets one");

  // and adopting the same frame twice does not stack up host listeners
  const before = (host.handlers.message || []).length;
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  assert.equal((host.handlers.message || []).length, before,
    "a frame already adopted is not adopted again");
});

test("the frame scrolls the host to an anchor in the frame", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const init = host.posted.get(frame)[0].msg;

  const frameSide = loadFrame(640,
    '<a href="#section">go</a><span id="section"></span><span data-iframe-height></span>');
  const target = frameSide.document.getElementById("section");
  target.getBoundingClientRect = () => ({bottom: 300, right: 200, top: 300, left: 20});
  deliver(frameSide.handlers, init, host.window);

  // the console pages use in-page links, so a click has to move the host page
  host.inbox.length = 0;
  frameSide.document.querySelector("a").dispatchEvent(new Event("click", {cancelable: true}));

  const request = host.inbox.map(entry => ({data: parseChild(entry.msg), msg: entry.msg}))
    .find(entry => entry.data !== null && entry.data.type === "scrollToOffset");
  assert.ok(request, `a #anchor click asks the host to scroll: ${JSON.stringify(host.inbox)}`);
  assert.equal(request.data.height, 300, "the height field carries the y offset");
  assert.equal(request.data.width, 20, "and the width field the x offset");

  hostApplies(host, request.msg);
  assert.deepEqual(host.scrolls, [[20, 300]], "the host scrolls to where the frame asked");
});

test("the host can refuse the frame's scroll request", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize({...CONSOLE_OPTIONS, onScroll: () => false}, "#graph");
  host.document.querySelector("iframe");

  hostApplies(host, "[iFrameSizer]graph:300:20:scrollToOffset");
  assert.equal(host.scrolls.length, 0, "onScroll returning false stops the scroll");
});

test("the host finds an anchor the frame does not hold", () => {
  const host = loadHost(`${FRAME}<span id="elsewhere"></span>`);
  const target = host.document.getElementById("elsewhere");
  target.getBoundingClientRect = () => ({bottom: 250, right: 0, top: 250, left: 0});
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");

  hostApplies(host, "[iFrameSizer]graph:0:0:inPageLink:#elsewhere");
  assert.deepEqual(host.scrolls, [[0, 250]],
    "a link the frame cannot resolve is resolved by the host");
});

test("the host reports an in-page link it cannot find either", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");

  hostApplies(host, "[iFrameSizer]graph:0:0:inPageLink:#missing");
  assert.equal(host.scrolls.length, 0, "a missing anchor does not move the page");
});

test("the host runs onInit when the frame first reports", () => {
  const host = loadHost(FRAME);
  let inits = 0;
  host.window.iFrameResize({...CONSOLE_OPTIONS, onInit: () => {inits++;}}, "#graph");
  const frame = host.document.querySelector("iframe");

  const {frameSide, answer} = handshake(host, frame, 640);
  hostApplies(host, answer);
  assert.equal(inits, 1, "the console scrolls a framed page to the top when it loads");

  frameSide.grow(200);
  frameSide.observed[0].cb([{type: "childList", target: frameSide.window.document.body}]);
  runTimers(frameSide.timers);
  const again = lastOf(host.inbox, "mutationObserver");
  hostApplies(host, again);
  assert.equal(inits, 1, "and not again for a later size report");
});

test("the frame's public size() reports the height the page chose", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const {frameSide} = handshake(host, frame, 640);

  host.inbox.length = 0;
  frameSide.window.parentIFrame.size(500);
  const msg = lastOf(host.inbox, "size");
  assert.ok(msg, `a framed page can set its own height: ${JSON.stringify(host.inbox)}`);
  assert.equal(parseChild(msg).height, 500);
});

test("the host can ask the frame to scroll to one of its anchors", () => {
  const host = loadHost(FRAME);
  host.window.iFrameResize(CONSOLE_OPTIONS, "#graph");
  const frame = host.document.querySelector("iframe");
  const init = host.posted.get(frame)[0].msg;

  const frameSide = loadFrame(640, '<span id="deep"></span><span data-iframe-height></span>');
  frameSide.document.getElementById("deep").getBoundingClientRect = () =>
    ({bottom: 300, right: 200, top: 300, left: 20});
  deliver(frameSide.handlers, init, host.window);

  // I2PSnark scrolls its host page to a panel it expands
  host.posted.get(frame).length = 0;
  frame.iFrameResizer.moveToAnchor("deep");
  assert.deepEqual(host.posted.get(frame).map(entry => entry.msg), ["[iFrameSizer]moveToAnchor:deep"]);

  host.inbox.length = 0;
  deliver(frameSide.handlers, "[iFrameSizer]moveToAnchor:deep", host.window);
  const request = lastOf(host.inbox, "scrollToOffset");
  assert.ok(request, `the frame answers with the offset: ${JSON.stringify(host.inbox)}`);
  assert.equal(parseChild(request).height, 300);

  hostApplies(host, request);
  assert.deepEqual(host.scrolls, [[20, 300]], "and the host moves to it");
});

test("initResizer() adopts the frame with the console's settings", () => {
  const page = loadConsole(FRAME);
  page.window.initResizer("graph");
  const frame = page.document.querySelector("iframe");

  assert.equal(page.posted.get(frame)[0].msg,
    `[iFrameSizer]graph:${PROTOCOL}:false:true:2:true:taggedElement`,
    "the console's settings are the ones the frame measures with");

  // a framed page that is still display:none reports nothing, and the floor
  // keeps the host from latching a page-wide observer on a 0px frame
  hostApplies(page, "[iFrameSizer]graph:0:0:init");
  assert.equal(frame.style.height, "1px", "minHeight keeps the frame from collapsing");
});

test("initResizer() answers the framed page's own scroll request", () => {
  const page = loadConsole(FRAME);
  page.window.initResizer("graph");
  const frame = page.document.querySelector("iframe");

  // I2PSnark's toggleConfigs.js posts this shape directly, not through the pair
  const inside = parseHTML('<!DOCTYPE HTML><html><body><div id="panel"></div></body></html>').document;
  inside.getElementById("panel").getBoundingClientRect = () => ({top: 250, bottom: 400, left: 0, right: 400});
  frame.contentWindow.document = inside;
  page.window.scrollY = 100;

  deliver(page.handlers, {origin: "parent", command: "scrollToElement", id: "panel"}, frame.contentWindow);
  const [call] = page.scrolls;
  assert.equal(page.scrolls.length, 1, "the host scrolls once");
  assert.equal(call.length, 1, "with a single options argument");
  assert.equal(call[0].top, 350,
    "the frame's position in the host is its viewport position plus the host scroll");
  assert.equal(call[0].behavior, "smooth", "and the scroll is smooth, as it was before");

  // an id the framed page does not have must not throw
  deliver(page.handlers, {origin: "parent", command: "scrollToElement", id: "nope"}, frame.contentWindow);
  assert.equal(page.scrolls.length, 1, "and leaves the page where it was");
});

test("initResizer() answers the Tunnel Manager's resize request", () => {
  const page = loadConsole(FRAME);
  page.window.initResizer("graph");
  const frame = page.document.querySelector("iframe");
  page.posted.get(frame).length = 0;

  deliver(page.handlers, {action: "resize", iframeId: "graph"}, frame.contentWindow);
  assert.deepEqual(page.posted.get(frame).map(entry => entry.msg), ["[iFrameSizer]resize"],
    "the framed page asking for a re-measure gets one");

  page.posted.get(frame).length = 0;
  deliver(page.handlers, {action: "resize", iframeId: "other"}, frame.contentWindow);
  assert.equal(page.posted.get(frame).length, 0, "and only the frame that asked");
});

test("a framed page navigation does not wire the host twice", () => {
  const page = loadConsole(FRAME);
  page.window.initResizer("graph");
  const after = (page.handlers.message || []).length;

  // the framed page fires the host's onInit again on every navigation inside it
  page.window.initResizer("graph");
  page.window.initResizer("graph");
  assert.equal((page.handlers.message || []).length, after,
    "one set of listeners per frame, however often the frame navigates");
});
