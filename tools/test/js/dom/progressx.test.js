/**
 * Tier-2 contract tests for tools/javadoc/assets/js/progressx.js — the load bar
 * the generated javadoc paints while the page is still arriving.
 *
 * The bar is the only thing a reader sees during a slow, uncached javadoc load,
 * so these pin what that load looks like and, just as much, what it must not do:
 *
 *   - the bar goes into the root element, not the body: the body is hidden
 *     behind the load interstitial, so a bar in the body is invisible;
 *   - while loading it creeps toward 85% and never reaches full, so a slow load
 *     never reads as finished;
 *   - a bar that is holding still still reads as working: a highlight sweeps
 *     across it, and that purely decorative sweep is what prefers-reduced-motion
 *     drops;
 *   - hide() reveals the page exactly once, on the first frame of the bar's
 *     final sweep, then sweeps the bar to full, fades it out, and stops its own
 *     animation loop rather than leaving a rAF running forever;
 *   - with no requestAnimationFrame at all the module stays inert but hide()'s
 *     callback still fires, because the page has to be revealed either way.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { parseHTML } from "linkedom";

/**
 * The javadoc tooling tree sits under the blanket `tools/*` gitignore rule, so a
 * fresh clone does not carry this script. Read it up front and skip the suite
 * with a reason when it is absent rather than throwing at import time and taking
 * the whole harness down with it.
 */
const SOURCE = new URL("../../../../tools/javadoc/assets/js/progressx.js", import.meta.url);
let SCRIPT = null;
try {
  SCRIPT = readFileSync(SOURCE, "utf8");
} catch {
  SCRIPT = null;
}
const SKIP = SCRIPT === null ? "progressx.js is not carried by a fresh clone" : false;

/** Width the fake window reports, so a filled fraction is readable as pixels. */
const VIEWPORT = 1000;

/**
 * A fake 2d context that records what was painted. The gradient object is
 * recorded too, because which colours it was built from is the only visible
 * difference between the two themes.
 *
 * @returns {{ctx: Object, ops: Array<Array>, gradients: Array<Array<string>>}}
 *   the context, every drawing call in order, and each gradient's colour stops
 */
function fakeContext() {
  const ops = [];
  const gradients = [];
  const ctx = {
    createLinearGradient: (x0, y0, x1, y1) => {
      ops.push(["createLinearGradient", x0, y0, x1, y1]);
      const stops = [];
      gradients.push(stops);
      return { addColorStop: (at, color) => stops.push(color) };
    },
    clearRect: (...a) => ops.push(["clearRect", ...a]),
    beginPath: () => ops.push(["beginPath"]),
    moveTo: (...a) => ops.push(["moveTo", ...a]),
    lineTo: (...a) => ops.push(["lineTo", ...a]),
    stroke: () => ops.push(["stroke"]),
    save: () => ops.push(["save"]),
    restore: () => ops.push(["restore"]),
    rect: (...a) => ops.push(["rect", ...a]),
    clip: () => ops.push(["clip"]),
    fillRect: (...a) => ops.push(["fillRect", ...a])
  };
  return { ctx, ops, gradients };
}

/**
 * Loads the module the way a script tag runs it: the file is an IIFE invoked as
 * (window, document) and its bare requestAnimationFrame calls resolve to globals,
 * so the script runs in a fresh vm context whose global object is the fake window
 * here, and that rAF queue is the only clock the bar can use.
 *
 * @param {Object} [opts] load options
 * @param {boolean} [opts.noAnimation] leave window.requestAnimationFrame absent,
 *        as an engine without it does
 * @param {boolean} [opts.reducedMotion] report prefers-reduced-motion: reduce
 * @returns {{bar: Object, document: Document, canvas: function(): Object,
 *          tick: function(number): boolean, frames: number, ops: Array<Array>,
 *          gradients: Array<Array<string>>, resize: function(): void,
 *          resizeTo: function(number): void}}
 *   the module's API, the page, the bar's canvas once it exists, a clock to
 *   advance, how many frames are queued, what was painted, the gradients that
 *   were built, and ways to fire a resize at the bar
 */
function load(opts = {}) {
  const {document} = parseHTML("<!DOCTYPE HTML><html><head></head><body></body></html>");
  const { ctx, ops, gradients } = fakeContext();

  // linkedom's canvas.getContext() returns null, so the test supplies the
  // recording context a real browser would.
  const create = document.createElement.bind(document);
  let canvas = null;
  document.createElement = tag => {
    const el = create(tag);
    if (tag === "canvas") {
      canvas = el;
      el.getContext = () => ctx;
    }
    return el;
  };

  let queue = [];
  let nextId = 0;
  let now = 0;
  const listeners = {};
  const win = {
    document,
    innerWidth: VIEWPORT,
    addEventListener: (type, fn) => { (listeners[type] = listeners[type] || []).push(fn); },
    removeEventListener: () => {}
  };
  // the vm context object is the window: the script's `window` global, and the
  // object it writes progressx onto, are this one
  win.window = win;
  if (!opts.noAnimation) {
    win.requestAnimationFrame = cb => { queue.push(cb); return ++nextId; };
    win.cancelAnimationFrame = () => {queue = [];};
  }
  if (opts.reducedMotion) {
    win.matchMedia = () => ({ matches: true });
  }

  // the file is an IIFE invoked as (window, document), so the context object is
  // the window it writes progressx onto and the source of every global it reads
  vm.runInNewContext(SCRIPT, win);

  return {
    bar: win.progressx,
    document,
    canvas: () => canvas,
    ops,
    gradients,
    get frames() {return queue.length;},
    /**
     * Runs the queued animation frames, one per call, the way the engine does:
     * a frame that queues another one is run again on the next tick.
     *
     * @param {number} n frames to run
     * @returns {boolean} false as soon as the queue is empty, i.e. the bar's own
     *          animation loop has stopped
     */
    tick(n) {
      for (let i = 0; i < n; i++) {
        const frame = queue.shift();
        if (!frame) {return false;}
        now += 16;
        // the real rAF callback gets the frame timestamp; the bar uses it only
        // to place the sweeping highlight
        frame(now);
      }
      return true;
    },
    /** Fires every registered resize listener, as a viewport change does. */
    resize() {
      for (const fn of listeners.resize || []) {fn();}
    },
    /**
     * Reports a new viewport width and fires the resize listeners, which is what
     * a window resize does.
     *
     * @param {number} width the new window.innerWidth
     */
    resizeTo(width) {
      win.innerWidth = width;
      this.resize();
    }
  };
}

test(
  "progressx: with no requestAnimationFrame the bar is inert but the page is still revealed",
  { skip: SKIP },
  () => {
  const h = load({ noAnimation: true });
  assert.equal(typeof h.bar.show, "function");
  assert.equal(typeof h.bar.hide, "function");
  h.bar.show();
  assert.equal(h.canvas(), null, "nothing is created to paint into");
  let revealed = false;
  h.bar.hide(() => {revealed = true;});
  assert.ok(revealed, "hide() has to reveal the page even with no bar to animate");
});

test(
  "progressx: the bar goes into the root element, not the body",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  const canvas = h.canvas();
  assert.ok(canvas, "show() creates the canvas");
  assert.equal(canvas.id, "pageloader");
  assert.equal(h.document.documentElement.firstChild, canvas,
    "the body is hidden behind the interstitial, so the bar cannot live in it");
  assert.equal(h.document.body.childNodes.length, 0);
  assert.match(canvas.style.cssText, /position:fixed/);
  assert.equal(canvas.style.display, "block", "show() reveals it");
});

test(
  "progressx: a fresh bar paints the empty track, not a fill",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  const cleared = h.ops.filter(op => op[0] === "clearRect");
  assert.equal(cleared.length, 1);
  assert.deepEqual(cleared[0], ["clearRect", 0, 0, VIEWPORT, 3]);
  assert.equal(h.ops.filter(op => op[0] === "stroke").length, 0,
    "progress starts at zero, so there is nothing to stroke yet");
});

test(
  "progressx: while loading the bar creeps and never reads as done",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  const width = () => h.ops.filter(op => op[0] === "lineTo").at(-1)[1];
  const seen = [];
  for (let i = 0; i < 2000; i++) {
    h.tick(1);
    seen.push(width());
  }
  assert.ok(seen.every((w, i) => i === 0 || w >= seen[i - 1]), "the bar only moves forward");
  // 600 frames is 10s at 60fps: the tuning says a slow load is still climbing
  // then, not plateaued, so the bar has to be short of its ceiling
  assert.ok(seen[599] < 850, `still climbing at 10s, got ${seen[599]}px`);
  assert.ok(seen[599] > 700, `and well up the track by then, got ${seen[599]}px`);
  assert.equal(seen.at(-1), 850, "it settles at the 85% ceiling and holds there");
  assert.ok(!seen.includes(VIEWPORT),
    "a load that has not finished never paints a full bar");
});

test(
  "progressx: a bar that is holding still keeps sweeping a highlight",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  h.tick(1);
  const first = h.ops.filter(op => op[0] === "fillRect").length;
  h.tick(30);
  const later = h.ops.filter(op => op[0] === "fillRect").length;
  assert.equal(first, 1, "the highlight is clipped to the filled part, one fill per frame");
  assert.ok(later > first, "and it keeps sweeping while the bar holds at the ceiling");
  const clip = h.ops.filter(op => op[0] === "clip").length;
  assert.equal(clip, later, "every sweep is clipped, so it never paints past the bar");
});

test(
  "progressx: reduced motion drops the decorative sweep, not the bar",
  { skip: SKIP },
  () => {
  const h = load({ reducedMotion: true });
  h.bar.show();
  h.tick(20);
  assert.equal(h.ops.filter(op => op[0] === "fillRect").length, 0);
  assert.equal(h.ops.filter(op => op[0] === "clip").length, 0);
  assert.ok(h.ops.some(op => op[0] === "stroke"), "the bar itself still paints");
});

test(
  "progressx: hide() sweeps the bar to full and reveals the page exactly once",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  h.tick(120);
  const before = h.ops.filter(op => op[0] === "lineTo").at(-1)[1];
  let revealed = 0;
  h.bar.hide(() => revealed++);
  h.tick(1);
  assert.equal(revealed, 1, "the page is revealed on the first frame of the final sweep");
  const width = () => h.ops.filter(op => op[0] === "lineTo").at(-1)[1];
  assert.ok(width() > before, "the bar jumps ahead of where the load left it");
  for (let i = 0; i < 10 && width() < VIEWPORT; i++) {h.tick(1);}
  assert.equal(width(), VIEWPORT, "and it is swept to full width before it fades out");
  h.tick(10);
  assert.equal(revealed, 1, "and only once, however many frames follow");
});

test(
  "progressx: hide() fades the bar out and stops its own animation loop",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  h.tick(10);
  h.bar.hide(() => {});
  const shades = [];
  let frames = 0;
  while (frames++ < 60) {
    if (!h.tick(1)) {break;}
    const o = h.canvas().style.opacity;
    if (String(o) !== String(shades.at(-1))) {shades.push(Number(o));}
    if (h.canvas().style.display === "none") {break;}
  }
  assert.equal(h.canvas().style.display, "none", "the bar is hidden, not left on screen");
  assert.ok(shades.length > 3, `it fades over ${shades.length} steps rather than in one`);
  assert.ok(shades.every((o, i) => i === 0 || o <= shades[i - 1]), "and the fade only goes down");
  assert.ok(shades.at(-1) <= 0.2, `it fades nearly to nothing, stopped at ${shades.at(-1)}`);
  assert.equal(h.frames, 0, "the loop stops: no rAF left running on a finished page");
});

test(
  "progressx: hide() without a visible bar reveals the page at once",
  { skip: SKIP },
  () => {
  const h = load();
  let revealed = 0;
  h.bar.hide(() => revealed++);
  assert.equal(revealed, 1);
  assert.equal(h.canvas(), null, "and no bar is created just to hide it");
});

test(
  "progressx: the theme decides the gradient, and an unknown one falls back",
  { skip: SKIP },
  () => {
  const midnight = load();
  midnight.bar.show("midnight");
  midnight.tick(1);
  assert.deepEqual(midnight.gradients[0], ["rgba(72,0,72,.8)", "rgba(160,0,160,.8)"]);

  const unknown = load();
  unknown.bar.show("no-such-theme");
  unknown.tick(1);
  assert.deepEqual(unknown.gradients[0], ["rgba(48,64,192,.7)", "rgba(48,64,255,.7)"],
    "a bad theme name still paints, in the house colours");
});

test(
  "progressx: a viewport change rebuilds the gradient for the new width",
  { skip: SKIP },
  () => {
  const h = load();
  h.bar.show();
  // the sweeping highlight builds its own moving gradient too; the bar's own
  // gradient is the one that starts at the left edge
  const barGradients = () => h.ops.filter(op => op[0] === "createLinearGradient" && op[1] === 0);
  const fill = () => h.ops.filter(op => op[0] === "lineTo").map(op => op[1]).at(-1);
  h.tick(1);
  assert.deepEqual(barGradients().at(-1), ["createLinearGradient", 0, 0, VIEWPORT, 0]);
  h.tick(5);
  assert.equal(barGradients().length, 1, "the gradient is cached, not rebuilt every frame");
  const before = fill();
  h.resizeTo(1400);
  h.tick(1);
  assert.equal(barGradients().length, 2, "a wider viewport needs a gradient that spans it");
  assert.deepEqual(barGradients().at(-1), ["createLinearGradient", 0, 0, 1400, 0]);
  assert.ok(fill() > before * 1.3,
    `the same progress now spans the new width: ${before} -> ${fill()}`);
});
