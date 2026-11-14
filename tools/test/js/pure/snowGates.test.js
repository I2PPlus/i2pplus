/**
 * Tier-2 contract tests for apps/routerconsole/jsp/js/snowflakes/snowflakes.js —
 * the page side of the console's festive snow.
 *
 * The worker does the animation; what this file owns is the decision to run at
 * all, and the shape of the handshake that starts it. Those are the parts with
 * dates, precedence rules and thresholds in them, which is exactly where an
 * off-by-one lives:
 *
 *   - the season is 1 December through 6 January inclusive, and it spans the new
 *     year without a gap;
 *   - an explicit choice beats reduced motion, which beats the calendar, and a
 *     URL flag beats what the browser remembers;
 *   - the adaptive controller needs two slow windows before it cuts flakes and
 *     three fast ones before it gives them back, so a single hiccup cannot make
 *     the snow pulse;
 *   - the pixel ratio is capped, and capped harder on a machine that reports two
 *     cores or two gigabytes;
 *   - startup asks the worker for exactly one init, carrying the viewport, the
 *     pixel ratio and the theme colour, and asks it before anything is fetched;
 *   - the atlas is built from the shapes the worker generates, and the six
 *     hand-drawn files are fetched only if the worker sent none of its own.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const SRC = readFileSync(
  new URL("../../../../apps/routerconsole/jsp/js/snowflakes/snowflakes.js", import.meta.url), "utf8");

const REAL_DATE = Date;

/**
 * A Date subclass pinned to a moment the caller chooses, so the season gate can
 * be tested in November.
 *
 * @param {string} iso the moment to pin the clock to
 * @returns {Function} a Date the page-side script cannot tell the difference from
 */
function pinnedDate(iso) {
  const at = new REAL_DATE(iso).getTime();
  class Pinned extends REAL_DATE {
    constructor(...args) {
      super(...(args.length ? args : [at]));
    }
    static now() {return at;}
  }
  return Pinned;
}

/**
 * Loads the page script in a context that imitates a browser: a window with a
 * location and a viewport, a document with a body, storage, matchMedia, a fetch
 * that records what it was asked for, and a Worker stub that records what the
 * page asks it to do and can answer with a ready message.
 *
 * @param {Object} [options] the environment to imitate
 * @param {string} [options.search=""] the page's query string
 * @param {string|null} [options.stored=null] what localStorage holds for snowMode
 * @param {boolean} [options.reduced=false] whether prefers-reduced-motion matches
 * @param {string} [options.date="2026-06-15T12:00:00"] what the clock reads
 * @param {number} [options.width=1280] window.innerWidth
 * @param {number} [options.height=720] window.innerHeight
 * @param {number} [options.dpr=1] window.devicePixelRatio
 * @param {number} [options.cores=8] navigator.hardwareConcurrency
 * @param {number} [options.memory=8] navigator.deviceMemory
 * @param {string} [options.theme="light"] the window.theme the JSP injects
 * @param {boolean} [options.storageThrows=false] whether localStorage throws
 * @returns {{gates: Object, workers: Object[], canvases: Object[],
 *   listeners: Object, fetches: string[], store: Map}} the exported gates plus
 *   what the page touched
 */
function openPage(options = {}) {
  const workers = [];
  const canvases = [];
  const fetches = [];
  const listeners = {document: {}, window: {}};

  /**
   * Decodes a shape the way an <img> does: asynchronously, and always
   * successfully, so what is under test is the page's ordering rather than the
   * shim's.
   */
  class FakeImage {
    set src(value) {
      this._src = value;
      Promise.resolve().then(() => {if (this.onload) {this.onload();}});
    }
  }

  const canvas = () => {
    const made = {
      width: 0,
      height: 0,
      style: {},
      parentNode: null,
      getContext: () => ({
        setTransform() {},
        clearRect() {},
        drawImage() {},
        globalAlpha: 1
      }),
      remove: () => {}
    };
    made.parentNode = {removeChild: () => {made.parentNode = null;}};
    canvases.push(made);
    return made;
  };

  const store = new Map();
  if (typeof options.stored === "string") {store.set("snowMode", options.stored);}
  const storage = options.storageThrows
    ? {getItem: () => {throw new Error("denied");}, setItem: () => {throw new Error("denied");}}
    : {getItem: k => (store.has(k) ? store.get(k) : null), setItem: (k, v) => {store.set(k, String(v));}};

  const ctx = {
    window: {
      location: {search: options.search || ""},
      innerWidth: options.width === undefined ? 1280 : options.width,
      innerHeight: options.height === undefined ? 720 : options.height,
      devicePixelRatio: options.dpr === undefined ? 1 : options.dpr,
      theme: options.theme === undefined ? "light" : options.theme,
      addEventListener: (type, fn) => {listeners.window[type] = fn;}
    },
    document: {
      body: {appendChild: () => {}},
      visibilityState: "visible",
      createElement: canvas,
      getElementById: () => null,
      addEventListener: (type, fn) => {listeners.document[type] = fn;}
    },
    localStorage: storage,
    matchMedia: () => ({matches: !!options.reduced}),
    navigator: {hardwareConcurrency: options.cores === undefined ? 8 : options.cores,
                deviceMemory: options.memory === undefined ? 8 : options.memory},
    // The atlas path rasterizes shapes through a blob <img>, so the shim needs
    // those globals or the handshake cannot be driven at all.
    Blob: class {constructor() {}},
    URL: {createObjectURL: () => "blob:shape", revokeObjectURL: () => {}},
    Image: FakeImage,
    fetch: url => {
      fetches.push(url);
      return Promise.resolve({ok: true, text: () => Promise.resolve("<svg></svg>")});
    },
    Worker: class {
      constructor(url) {
        this.url = url;
        this.sent = [];
        this.terminated = false;
        this.handlers = {};
        workers.push(this);
      }
      postMessage(message) {this.sent.push(message);}
      addEventListener(type, fn) {this.handlers[type] = fn;}
      terminate() {this.terminated = true;}
    },
    Date: pinnedDate(options.date || "2026-06-15T12:00:00"),
    // URLSearchParams is a host API, not an ECMAScript built-in, so a fresh vm
    // context has none; the page's own guard treats its absence as "the URL says
    // nothing", which would silently disable the override under test.
    URLSearchParams: URLSearchParams,
    setTimeout: () => 0,
    clearTimeout: () => {},
    requestAnimationFrame: () => 1,
    cancelAnimationFrame: () => {}
  };
  ctx.self = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);
  return {gates: ctx.window.snowGates, workers, canvases, listeners, fetches, store};
}

/**
 * Answers the page's init with a ready message, the first half of the atlas
 * handshake, and lets the page finish rasterizing.
 *
 * @param {Object} page an openPage() result
 * @param {Object} over fields to override in the stock ready message
 * @returns {Promise<void>} once the page has answered with the atlas
 */
async function sendReady(page, over = {}) {
  const worker = page.workers[0];
  const msg = Object.assign({
    type: "ready",
    offscreen: false,
    count: 3,
    cell: 64,
    stride: 8,
    layout: {cols: 2, rows: 2, width: 128, height: 128},
    cells: [{type: 1, color: "rgb(1,2,3)"}, {type: 2, color: "rgb(4,5,6)"},
            {type: 3, color: "rgb(7,8,9)"}],
    shapes: ["<svg>a</svg>", "<svg>b</svg>", "<svg>c</svg>"]
  }, over);
  worker.handlers.message({data: msg});
  for (let i = 0; i < 8; i++) {
    await new Promise(resolve => setImmediate(resolve));
  }
}

test("the season runs 1 December to 6 January, across the new year", () => {
  const {gates} = openPage();
  const yes = ["2026-12-01T00:00:01", "2026-12-25T12:00:00", "2026-12-31T23:59:59",
               "2027-01-01T00:00:01", "2027-01-06T23:59:59"];
  const no = ["2026-11-30T23:59:59", "2027-01-07T00:00:01", "2027-02-01T12:00:00",
              "2027-06-15T12:00:00", "2027-11-01T12:00:00"];
  for (const iso of yes) {assert.equal(gates.inSeason(new REAL_DATE(iso)), true, `${iso} is snow`);}
  for (const iso of no) {assert.equal(gates.inSeason(new REAL_DATE(iso)), false, `${iso} is not`);}
});

test("an explicit choice beats reduced motion, which beats the calendar", () => {
  const {gates} = openPage();
  assert.equal(gates.modeFor({url: "on", stored: "off", reduced: true, season: false}), "on",
               "a URL flag overrides everything");
  assert.equal(gates.modeFor({url: "off", stored: "on", reduced: false, season: true}), "off",
               "a URL flag overrides a stored choice too");
  assert.equal(gates.modeFor({url: null, stored: "on", reduced: true, season: false}), "on",
               "the hotkey choice overrides reduced motion");
  assert.equal(gates.modeFor({url: null, stored: "off", reduced: false, season: true}), "off",
               "and it can stop snow in the season");
  assert.equal(gates.modeFor({url: null, stored: null, reduced: true, season: true}), "off",
               "reduced motion stops the calendar default");
  assert.equal(gates.modeFor({url: null, stored: null, reduced: false, season: true}), "on");
  assert.equal(gates.modeFor({url: null, stored: null, reduced: false, season: false}), "off",
               "out of season, nothing runs");
});

test("the query override reads only the values it writes", () => {
  assert.equal(openPage({search: "?snow=1"}).gates.urlMode(), "on");
  assert.equal(openPage({search: "?snow=on"}).gates.urlMode(), "on");
  assert.equal(openPage({search: "?snow=0"}).gates.urlMode(), "off");
  assert.equal(openPage({search: "?snow=off"}).gates.urlMode(), "off");
  assert.equal(openPage({search: "?page=jobs&snow=1"}).gates.urlMode(), "on");
  assert.equal(openPage({search: "?snow=yes"}).gates.urlMode(), null, "an unknown value says nothing");
  assert.equal(openPage({search: "?snow="}).gates.urlMode(), null);
  assert.equal(openPage({search: ""}).gates.urlMode(), null);
  assert.equal(openPage({search: "?snowman=1"}).gates.urlMode(), null, "not a prefix match");
});

test("a stored choice is read only when it is one this script wrote", () => {
  assert.equal(openPage({stored: "on"}).gates.storedMode(), "on");
  assert.equal(openPage({stored: "off"}).gates.storedMode(), "off");
  assert.equal(openPage({stored: "true"}).gates.storedMode(), null, "another script's value is ignored");
  assert.equal(openPage({stored: null}).gates.storedMode(), null);
  assert.equal(openPage({storageThrows: true}).gates.storedMode(), null,
               "private browsing does not take the page down");
});

/**
 * Lets the page's async startup (the worker, then the atlas handshake) run to
 * completion.
 *
 * @returns {Promise<void>} once the microtask and macrotask queues have drained
 */
async function settle() {
  for (let i = 0; i < 4; i++) {
    await new Promise(resolve => setImmediate(resolve));
  }
}

test("the hotkey and the calendar share one decision at boot", async () => {
  const offSeason = openPage({date: "2026-06-15T12:00:00"});
  await settle();
  assert.equal(offSeason.workers.length, 0, "out of season, no worker is started");
  assert.equal(offSeason.gates.status().running, false);

  const forced = openPage({date: "2026-06-15T12:00:00", search: "?snow=1"});
  await settle();
  assert.equal(forced.workers.length, 1, "?snow=1 starts it anyway");

  const remembered = openPage({date: "2026-06-15T12:00:00", stored: "on"});
  await settle();
  assert.equal(remembered.workers.length, 1, "the hotkey choice survives a page load");

  const season = openPage({date: "2026-12-20T12:00:00"});
  await settle();
  assert.equal(season.workers.length, 1, "in season it runs by itself");

  const reduced = openPage({date: "2026-12-20T12:00:00", reduced: true});
  await settle();
  assert.equal(reduced.workers.length, 0, "reduced motion wins in season");

  const hidden = openPage({date: "2026-12-20T12:00:00", stored: "off"});
  await settle();
  assert.equal(hidden.workers.length, 0, "a stored off wins in season");
});

test("startup asks the worker for exactly one init, with the viewport and theme", async () => {
  const page = openPage({date: "2026-12-20T12:00:00", width: 1440, height: 900, dpr: 2, theme: "dark"});
  await settle();
  assert.equal(page.workers.length, 1);
  assert.equal(page.workers[0].url, "/js/snowflakes/snowman.js");
  const init = page.workers[0].sent.find(message => message.type === "init");
  assert.ok(init, "the handshake starts with init");
  assert.equal(init.width, 1440);
  assert.equal(init.height, 900);
  assert.equal(init.dpr, 1.5, "the pixel ratio is capped, not passed through");
  assert.equal(init.baseColor, "#5a9d68", "the dark theme's flake colour");
  assert.ok(Number.isInteger(init.seed), "a seed so a run can be reproduced");
  assert.equal(page.gates.status().mode, "physics", "no OffscreenCanvas in this context");
});

test("the page rasterizes the shapes the worker generated and fetches nothing", async () => {
  const page = openPage({date: "2026-12-20T12:00:00"});
  await settle();
  assert.equal(page.fetches.length, 0, "generated shapes arrive with the worker, so nothing is fetched");
  await sendReady(page);
  assert.ok(page.workers[0].sent.some(message => message.type === "atlas"),
            "the page hands the rasterized atlas back");
  assert.equal(page.gates.status().count, 3, "and the field is the size the worker asked for");
});

test("a worker that sends no shapes falls back to the hand-drawn files", async () => {
  const page = openPage({date: "2026-12-20T12:00:00"});
  await settle();
  await sendReady(page, {shapes: []});
  assert.equal(page.fetches.length, 6, "the six files are fetched, and only once generation is ruled out");
  for (const url of page.fetches) {
    assert.match(url, /^\/js\/snowflakes\/flake[1-6]\.svg$/, "by their real names");
  }
  assert.ok(page.workers[0].sent.some(message => message.type === "atlas"),
            "and the atlas still gets built from them");
});

test("the pixel ratio is capped, and capped harder on a small machine", () => {
  assert.equal(openPage({dpr: 1}).gates.pickDpr(), 1);
  assert.equal(openPage({dpr: 2}).gates.pickDpr(), 1.5, "a HiDPI screen gets a capped scale");
  assert.equal(openPage({dpr: 3}).gates.pickDpr(), 1.5);
  assert.equal(openPage({dpr: 2, cores: 2}).gates.pickDpr(), 1, "two cores stay at 1:1");
  assert.equal(openPage({dpr: 2, memory: 2}).gates.pickDpr(), 1, "so does two gigabytes");
  assert.equal(openPage({dpr: null}).gates.pickDpr(), 1, "a browser that reports no ratio is not upscaled");
});

test("the paint ceiling and floor are absolute, not panel-relative", () => {
  const {gates} = openPage();
  assert.equal(gates.rateFor(60), 1, "a 60Hz panel needs no divisor to sit at 60");
  assert.equal(gates.rateFor(75), 1, "a rate near the target is left alone");
  assert.equal(gates.rateFor(144), 2, "a 144Hz panel paints every other beat, 72 a second");
  assert.equal(gates.maxRate(60), 2, "60Hz can halve the paint and still draw 30");
  assert.equal(gates.maxRate(144), 4, "144Hz can go to every fourth beat, 36 a second");
  assert.equal(gates.maxRate(30), 1, "a panel already at the floor cannot be thinned at all");
  assert.equal(gates.maxRate(400), 4, "and an absurd measurement is clamped");
});

test("the adaptive controller thins the paint before it thins the field", () => {
  const {gates} = openPage();
  const base = 16;
  let count = base;
  let rate = 1;
  let state = {low: 0, high: 0};
  const step = (fps) => {
    const next = gates.qualityStep(count, base, 60, fps, rate, state);
    count = next.count;
    rate = next.rate;
    state = next;
  };

  step(30);
  assert.equal(count, base, "one slow window changes nothing");
  assert.equal(rate, 1, "the paint included");
  step(30);
  assert.equal(rate, 2, "two slow windows halve the paint rate first");
  assert.equal(count, base, "and the field is untouched, so the snow looks exactly as it did");

  step(30);
  step(30);
  assert.equal(rate, 2, "the paint stops at the divisor the panel can afford above 30fps");
  assert.equal(count, 11, "and the next cut takes flakes instead");
  step(30);
  step(30);
  assert.equal(count, 8, "two more cut again");
  step(30);
  step(30);
  assert.equal(count, 6, "and stop at the floor");
  step(30);
  step(30);
  assert.equal(count, 6, "the floor holds");

  step(59);
  step(59);
  assert.equal(count, 6, "one fast window gives nothing back");
  step(59);
  assert.equal(count, 8, "three fast windows add two flakes");
  assert.equal(rate, 2, "but the paint divisor is never probed again");
  for (let i = 0; i < 12; i++) {step(59);}
  assert.equal(count, base, "and the field climbs back to what the viewport asked for");
  assert.equal(rate, 2, "at the reduced paint rate, which is where it stays");
});

test("a saturated loop still gets the paint step that would fix it", () => {
  const {gates} = openPage();
  // 30 measured on a 144Hz panel: bounding the divisor by the stuck-at number would
  // skip straight to cutting flakes and pay for a step that was never tried
  let state = {low: 0, high: 0};
  state = gates.qualityStep(12, 12, 144, 30, 1, state);
  state = gates.qualityStep(12, 12, 144, 30, state.rate, state);
  assert.equal(state.rate, 2, "the divisor is bounded by the panel, not by the current rate");
  assert.equal(state.count, 12, "so the field is left whole");
});

test("a window in the middle of the bands resets both streaks", () => {
  const {gates} = openPage();
  let state = {low: 0, high: 0};
  state = gates.qualityStep(10, 16, 60, 30, 1, state);
  state = gates.qualityStep(10, 16, 60, 50, state.rate, state);
  assert.equal(state.low, 0);
  assert.equal(state.high, 0);
});

test("flake colours follow the theme, with a fallback for an unknown one", () => {
  assert.equal(openPage({theme: "light"}).gates.themeColor(), "#78c3f2");
  assert.equal(openPage({theme: "dark"}).gates.themeColor(), "#5a9d68");
  assert.equal(openPage({theme: "midnight"}).gates.themeColor(), "#9662ca");
  assert.equal(openPage({theme: "classic"}).gates.themeColor(), "#b0e0e6");
  assert.equal(openPage({theme: "nonsense"}).gates.themeColor(), "#87ceeb");
  assert.equal(openPage({theme: null}).gates.themeColor(), "#78c3f2",
               "a page that injects no theme gets the light colour");
});

test("the page installs its override keys whatever the calendar says", () => {
  const page = openPage({date: "2026-06-15T12:00:00"});
  assert.equal(typeof page.listeners.document.keydown, "function",
               "the hotkey is the only way to see snow out of season");
  assert.equal(typeof page.listeners.document.visibilitychange, "function");
  assert.equal(typeof page.listeners.window.resize, "function");
});

test("pressing the override twice in a row starts exactly one worker", async () => {
  const page = openPage({date: "2026-06-15T12:00:00"});
  const press = () => page.listeners.document.keydown({
    key: "s", ctrlKey: true, altKey: true, preventDefault() {}, target: {tagName: "BODY"}
  });
  press();
  assert.equal(page.workers.length, 1, "the worker is started synchronously, before any fetch");
  assert.equal(page.gates.status().running, true);
  press();
  await settle();
  assert.equal(page.workers.length, 1, "a second press is a toggle, not a second worker");
  assert.equal(page.canvases.length, 1, "and it does not add a second canvas either");
  assert.equal(page.gates.status().running, false);
});

test("the override stops the worker and takes the canvas with it", async () => {
  const page = openPage({date: "2026-12-20T12:00:00"});
  await settle();
  assert.equal(page.workers.length, 1);
  page.listeners.document.keydown({
    key: "s", ctrlKey: true, altKey: true, preventDefault() {}, target: {tagName: "BODY"}
  });
  assert.equal(page.workers[0].terminated, true, "the worker is terminated, not just ignored");
  assert.equal(page.canvases[0].parentNode, null, "the canvas is taken out of the page");
  assert.equal(page.gates.status().running, false);
  assert.equal(page.store.get("snowMode"), "off", "and the choice is remembered");
});
