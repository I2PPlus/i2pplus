/**
 * Contract tests for apps/routerconsole/jsp/js/graphPanZoom.js — dragging and
 * wheel-rolling the console performance graphs through time.
 *
 * A graph is served as an <img> holding an SVG, so the window it shows is fixed
 * by its query string: periodCount is the span and end is how far back from now,
 * both in counts of that rate's own period. The maths these pin is what turns a
 * gesture into those two numbers — a drag into an offset through the graph's own
 * time-per-pixel scale, a wheel roll into a rescaled span anchored on the time
 * under the cursor, and a wall-clock window split back into counts per graph, so
 * that one page of graphs with different rate periods still shows one timeframe.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { installBrowserShim } from "../helpers/browserShim.js";

/** The module under test. */
const MODULE = "../../../../apps/routerconsole/jsp/js/graphPanZoom.js";

/**
 * Loads the module fresh, so its caches start empty.
 *
 * The module registers a DOMContentLoaded listener at evaluation time, so the
 * browser shim has to be installed before the import rather than inside a test.
 *
 * @returns {Promise<Object>} the module namespace
 */
async function load() {
  installBrowserShim();
  return import(`${MODULE}?t=${Math.random()}`);
}

test("a wheel notch is one notch whatever the delta mode", async () => {
  const m = await load();
  assert.equal(m.wheelNotches(100, 0), 1);
  assert.equal(m.wheelNotches(-100, 0), -1);
  // deltaMode 1 reports lines, already one unit per notch.
  assert.equal(m.wheelNotches(3, 1), 3);
  // deltaMode 2 reports pages.
  assert.equal(m.wheelNotches(1, 2), 10);
  assert.equal(m.wheelNotches(0, 0), 0);
});

test("a trackpad fraction still moves, but never by more than one notch", async () => {
  const m = await load();
  assert.equal(m.wheelNotches(4, 0), 1);
  assert.equal(m.wheelNotches(-4, 0), -1);
  assert.equal(m.wheelNotches(150, 0), 1.5);
});

test("rolling down lengthens the span and rolling up shortens it", async () => {
  const m = await load();
  const hour = 3600000;
  assert.equal(m.zoomSpan(hour, 0, m.MAX_PERIODS * 60000), hour);
  assert.ok(m.zoomSpan(hour, 1, m.MAX_PERIODS * 60000) > hour, "wheel down zooms out");
  assert.ok(m.zoomSpan(hour, -1, m.MAX_PERIODS * 60000) < hour, "wheel up zooms in");
  // Four notches of the 1.25 step is about double or half, not four separate jumps.
  assert.equal(Math.round(m.zoomSpan(hour, 4, m.MAX_PERIODS * 60000) / hour), 2);
});

test("the span clamps at the archive edge and at the sample floor", async () => {
  const m = await load();
  assert.equal(m.zoomSpan(9 * 60000, 5, 600000), 600000, "the archive edge stops the zoom out");
  assert.equal(m.zoomSpan(m.MIN_SPAN_MS, -5, m.MAX_PERIODS * 60000), m.MIN_SPAN_MS, "the floor stops the zoom in");
  // A ceiling below the floor wins: pinning such a graph to the floor would ask it
  // to show a span its archive has no rows for.
  assert.equal(m.zoomSpan(60000, -5, 30000), 30000);
});

test("dragging right looks further back, and the scale is the graph's own", async () => {
  const m = await load();
  const span = 8 * 3600000;
  // 296 plot pixels across 8 hours: one pixel is 97 seconds, give or take rounding.
  const onePx = m.panOffsetMs(0, span, 296, 1);
  assert.ok(Math.abs(onePx - (span / 296)) < 1);
  assert.equal(m.panOffsetMs(0, span, 296, 148), span / 2);
  assert.equal(m.panOffsetMs(0, span, 296, -148), 0, "never pans past now");
  assert.equal(m.panOffsetMs(3600000, span, 296, 0), 3600000);
  // A plot of no width has no scale, and inventing one would move the window by
  // the drag distance in milliseconds.
  assert.equal(m.panOffsetMs(3600000, span, 0, 100), 3600000);
});

test("a zoom keeps the time under the cursor where it was", async () => {
  const m = await load();
  const span = 8 * 3600000;
  const offset = 2 * 3600000;
  for (const frac of [0, 0.25, 0.5, 1]) {
    const next = m.zoomWindow(span, offset, span / 2, frac);
    // The cursor's time is now - (offset + (1 - frac) * span); the anchor holds it.
    const before = offset + (1 - frac) * span;
    const after = next.offsetMs + (1 - frac) * next.spanMs;
    assert.equal(Math.round(after), Math.round(before), `fraction ${frac}`);
  }
  // At the right edge the newest sample stays put; at the left edge the whole
  // window slides by what the span lost.
  assert.equal(m.zoomWindow(span, offset, span / 2, 1).offsetMs, offset);
  assert.equal(m.zoomWindow(span, offset, span / 2, 0).offsetMs, offset + span / 2);
});

test("a zoom never leaves the window in the future", async () => {
  const m = await load();
  const next = m.zoomWindow(60000, 0, 8 * 3600000, 0.9);
  assert.equal(next.offsetMs, 0);
  assert.ok(next.spanMs > 0);
});

test("a wall-clock window becomes counts of each graph's own period", async () => {
  const m = await load();
  const span = 8 * 3600000;
  const offset = 90 * 60000;
  // The 1-minute graphs the page mostly holds.
  assert.deepEqual(m.windowParams(span, offset, 60000), {periodCount: 480, end: 90});
  // A 5-minute rate needs a fifth of both counts for the same timeframe.
  assert.deepEqual(m.windowParams(span, offset, 300000), {periodCount: 96, end: 18});
  // end 0 means "up to now", and the parameter is left off the URL entirely.
  assert.deepEqual(m.windowParams(span, 0, 60000), {periodCount: 480, end: 0});
  assert.deepEqual(m.windowParams(90000, 0, 60000), {periodCount: 2, end: 0});
  // A span shorter than one period still plots one sample rather than zero.
  assert.deepEqual(m.windowParams(30000, 0, 60000), {periodCount: 1, end: 0});
});

test("the plot area is read in displayed pixels, not rendered ones", async () => {
  const m = await load();
  const rect = {left: 100, top: 50, width: 189.5, height: 89};
  // The SVG is rendered 379 wide with the plot at [63, 28, 296, 60]; laid out at
  // half that, the plot is half as wide and half as far in.
  const area = m.plotArea(rect, 379, [63, 28, 296, 60]);
  assert.ok(Math.abs(area.width - 148) < 1, String(area.width));
  assert.ok(Math.abs(area.left - (100 + 31.5)) < 1, String(area.left));
  // An image that has not loaded reports no natural width: read it unscaled rather
  // than treating every pixel as infinitely wide.
  const unscaled = m.plotArea({left: 0, top: 0, width: 379, height: 178}, 0, [63, 28, 296, 60]);
  assert.equal(unscaled.width, 296);
  assert.equal(unscaled.left, 63);
});

test("the cursor fraction spans the plot and nothing outside it", async () => {
  const m = await load();
  const area = {left: 63, width: 296};
  assert.equal(m.cursorFraction(63, area), 0);
  assert.equal(m.cursorFraction(359, area), 1);
  assert.equal(Math.round(m.cursorFraction(211, area) * 100) / 100, 0.5);
  assert.equal(m.cursorFraction(0, area), 0);
  assert.equal(m.cursorFraction(9999, area), 1);
  assert.equal(m.cursorFraction(10, {left: 0, width: 0}), 1);
});

test("the metadata reader hands back a plot array the gestures can scale by", async () => {
  const m = await load();
  const meta = {plot: [63, 28, 296, 60], x0: 1795654537, x1: 1795683337, series: [[1, 2]]};
  globalThis.fetch = () => Promise.resolve({ok: true, json: () => Promise.resolve(meta)});
  const img = {src: "http://127.0.0.1:7657/viewstat.jsp?stat=router.activePeers&period=60000&periodCount=480",
               getAttribute: () => null};
  const read = await m.metaFor(img);
  assert.deepEqual(read.plot, [63, 28, 296, 60]);
  // The whole point of the shape: the plot reaches plotArea through one level, not none.
  assert.equal(m.plotArea({left: 0, top: 0, width: 379, height: 178}, 379, read.plot).width, 296);
});

test("metadata without a usable plot is null, so a gesture does not start on nothing", async () => {
  const m = await load();
  globalThis.fetch = () => Promise.resolve({ok: true, json: () => Promise.resolve({x0: 1, x1: 1})});
  const img = {src: "http://127.0.0.1:7657/viewstat.jsp?stat=router.activePeers", getAttribute: () => null};
  assert.equal(await m.metaFor(img), null);
});

test("a fan-out is live only once the box has proved it is quick enough", async () => {
  const m = await load();
  // Nothing measured yet: the safe behaviour is to move one graph and commit at the end.
  assert.equal(m.fanOutLive(-1, 400), false);
  assert.equal(m.fanOutLive(0, 400), true);
  assert.equal(m.fanOutLive(78, 400), true, "26 graphs here redraw in 25-78ms");
  assert.equal(m.fanOutLive(401, 400), false, "a slow box keeps the preview-then-commit path");
  assert.equal(m.fanOutLive(NaN, 400), false);
});

test("a settled span becomes the minutes the graph configuration holds", async () => {
  const m = await load();
  // The configuration is one number for the whole page, in minutes: each graph's own
  // period count is that span divided by its rate's period.
  assert.equal(m.configMinutes(8 * 3600000), 480);
  assert.equal(m.configMinutes(90 * 60000), 90);
  assert.equal(m.configMinutes(30000), 5, "the form's floor");
  assert.equal(m.configMinutes(0), 5);
  assert.equal(m.configMinutes(4 * 365 * 86400000), 131040, "three months of minutes");
  // A 5-minute rate showing 96 periods is the same 8 hours, so the same 480 goes in.
  assert.equal(m.configMinutes(96 * 300000), 480);
});

test("a form's fields become one url-encoded body, one value per name", async () => {
  const m = await load();
  // A form holds a repeated name for each checkbox whether or not it is checked; the
  // body must carry one value per name, the checked one, as a real submit does.
  const fields = [["action", "Save"], ["nonce", "CN123"], ["periodCount", "480"],
                  ["hideRestarts", "false"], ["hideRestarts", "true"]];
  assert.equal(m.formBody(fields), "action=Save&nonce=CN123&periodCount=480&hideRestarts=true");
  assert.equal(m.formBody(fields, {periodCount: 90}),
               "action=Save&nonce=CN123&periodCount=90&hideRestarts=true");
  assert.equal(m.formBody([["q", "a b&c"]]), "q=a%20b%26c");
  assert.equal(m.formBody(null), "");
});

test("the module reports what it has measured and what it has saved", async () => {
  const m = await load();
  const st = m.graphPanZoomState();
  // Nothing has been rendered or written on a fresh page: the safe choices.
  assert.equal(st.renderMs, -1);
  assert.equal(st.savedMinutes, -1);
  assert.equal(st.live, false);
  // Whether the shim's document resolves an element by id is its own business; what
  // matters is that the caller can branch on this without guessing at the DOM.
  assert.equal(typeof st.form, "boolean");
  assert.ok(st.nonceAgeMs >= 0);
});

test("a roll that can move the window cancels the page scroll before it awaits anything", async () => {
  const m = await load();
  globalThis.fetch = () => Promise.resolve({ok: false});
  const img = {
    tagName: "IMG",
    classList: {contains: (c) => c === "statimage"},
    matches: (sel) => sel === "img.statimage",
    closest: (sel) => (sel === "img.statimage" ? img : null),
    getAttribute: () => null,
    getBoundingClientRect: () => ({left: 0, top: 0, width: 379, height: 178}),
    src: "http://127.0.0.1:7657/viewstat.jsp?stat=router.activePeers&period=60000&periodCount=480",
    naturalWidth: 379,
  };
  let cancelled = 0;
  const event = {target: img, deltaY: -120, deltaMode: 0, clientX: 200, clientY: 50,
                 preventDefault: () => {cancelled++;}};
  m.onWheel(event);
  // Not after the returned promise settles: the browser commits the scroll while the
  // listeners are still running, so a cancellation that arrives after an await is a
  // cancellation the page never hears about. Whether this notch can move the window is
  // decided from the image's own src, which needs no request to read.
  assert.equal(cancelled, 1, "cancelled synchronously, before the metadata fetch");
});

test("a roll at the end of the zoom goes back to being a page scroll", async () => {
  const m = await load();
  globalThis.fetch = () => Promise.resolve({ok: false});
  // Already at the shortest window the module allows: rolling up further has nowhere to go.
  const atFloor = {
    closest: (sel) => (sel === "img.statimage" ? atFloor : null),
    getAttribute: () => null,
    getBoundingClientRect: () => ({left: 0, top: 0, width: 379, height: 178}),
    src: "http://127.0.0.1:7657/viewstat.jsp?stat=router.activePeers&period=60000&periodCount=30",
    naturalWidth: 379,
  };
  let cancelled = 0;
  m.onWheel({target: atFloor, deltaY: -120, deltaMode: 0, clientX: 200, clientY: 50,
             preventDefault: () => {cancelled++;}});
  assert.equal(cancelled, 0, "the wheel is the page's again");
});

test("a roll anywhere else keeps the page's own scroll", async () => {
  const m = await load();
  const p = {tagName: "P", closest: () => null, matches: () => false};
  let cancelled = 0;
  m.onWheel({target: p, deltaY: -120, deltaMode: 0, clientX: 5, clientY: 5,
             preventDefault: () => {cancelled++;}});
  assert.equal(cancelled, 0);
});

test("a graph takes its drag gesture back from the page's own scrolling", async () => {
  const m = await load();
  m.initGraphPanZoom();
  const rules = Array.from(document.querySelectorAll("style")).map((s) => s.textContent).join("");
  // A touch or pen drag is a scroll gesture by default and the browser claims it, so
  // the pan never sees a move; touch-action has to be set before the surface is touched.
  assert.match(rules, /img\.statimage\s*\{\s*touch-action:\s*none/);
});

test("a drag keeps winding the timeframe past the edge of the graph", async () => {
  const m = await load();
  const span = 8 * 3600000;
  const plot = 296;
  // The gesture is accumulated a step at a time, so travel beyond the image's own width
  // keeps moving the window at the same rate rather than running out of graph.
  let offset = 0;
  for (let step = 0; step < 6; step++) {
    offset = m.panOffsetMs(offset, span, plot, 100);
  }
  assert.equal(Math.round(offset), Math.round(600 * (span / plot)));
  assert.ok(offset > span, "600px of travel is more than the 296px the graph is wide");
  // And back the other way, down to now, where it stops rather than going forward.
  for (let step = 0; step < 12; step++) {
    offset = m.panOffsetMs(offset, span, plot, -100);
  }
  assert.equal(offset, 0);
});

test("a plot read from the wrong level of the metadata is a zero area, not NaN", async () => {
  const m = await load();
  const meta = {plot: [63, 28, 296, 60], x0: 1795654537, x1: 1795683337};
  const rect = {left: 0, top: 0, width: 379, height: 178};
  // The metadata's plot is one level down; handing plotArea the whole document is the
  // mistake that reads as a working gesture - the width is NaN, so a drag shifts the
  // window by nothing and a zoom anchors at the right edge whatever the cursor does.
  const wrong = m.plotArea(rect, 379, meta);
  assert.equal(wrong.width, 0);
  assert.equal(m.panOffsetMs(0, 28800000, wrong.width, 120), 0);
  const right = m.plotArea(rect, 379, meta.plot);
  assert.equal(right.width, 296);
  assert.ok(m.panOffsetMs(0, 28800000, right.width, 120) > 3 * 3600000);
});
