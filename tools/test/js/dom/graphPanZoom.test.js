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

test("a part of a graph on the screen is a graph on the screen", async () => {
  const m = await load();
  const box = (top, bottom) => ({top: top, bottom: bottom, left: 0, right: 296});
  assert.equal(m.inViewport(box(10, 70), 800, 600), true, "wholly inside");
  assert.equal(m.inViewport(box(-40, 20), 800, 600), true, "its bottom half is showing");
  assert.equal(m.inViewport(box(580, 640), 800, 600), true, "its top half is showing");
  assert.equal(m.inViewport(box(600, 660), 800, 600), false, "starts at the fold");
  assert.equal(m.inViewport(box(-120, -60), 800, 600), false, "scrolled past");
  // Off to the side is off the screen too: the graphs page wraps at any width.
  assert.equal(m.inViewport({top: 10, bottom: 70, left: 900, right: 1196}, 800, 600), false);
});

test("a drag snaps by how wide a window it is moving", async () => {
  const m = await load();
  const minute = 60000, hour = 60 * minute, day = 24 * hour, week = 7 * day, month = 30 * day, year = 365 * day;
  // The largest rung the window is at least three steps wide.
  const want = [
    [9 * minute, minute, "nine minutes is nine minute steps"],
    [15 * minute, 5 * minute, "a quarter of an hour steps by five minutes"],
    [20 * minute, 5 * minute, "twenty minutes is four five-minute steps"],
    [30 * minute, 10 * minute, "the module's floor is half an hour"],
    [60 * minute, 10 * minute, "an hour is six ten-minute steps, not two thirty-minute"],
    [90 * minute, 30 * minute, "an hour and a half steps by the half hour"],
    [2 * hour + 59 * minute, 30 * minute, "a hair under three hour steps falls to the half hour"],
    [3 * hour, hour, "three hours is three hour steps"],
    [8 * hour, hour, "the default window on this box"],
    [3 * day, day, "three days steps by the day"],
    [30 * day, week, "a month steps by the week, not the month"],
    [91 * day, month, "three months steps by the month"],
    [3 * year, year, "three years steps by the year"],
  ];
  for (const [span, quantum, why] of want) {
    assert.equal(m.snapQuantumMs(span), quantum, why + " (" + (span / minute) + "m window)");
  }
  // A rung the window is only one or two steps wide is skipped: the drag would spend
  // itself crossing a single step, and the graph would look stuck rather than quick.
  assert.equal(m.snapQuantumMs(2 * minute), 0, "two minutes is not three minute steps");
  assert.equal(m.snapQuantumMs(2 * day), hour, "two days is not three day steps");
  assert.equal(m.snapQuantumMs(89 * day), week, "just under three months is not three month steps");
  // Nearest, not down: a window dragged 25 minutes past an hour stays on that hour, and
  // one dragged 35 minutes past it moves to the next.
  assert.equal(m.snapOffsetMs(61 * minute, hour), hour);
  assert.equal(m.snapOffsetMs(95 * minute, hour), 2 * hour);
  assert.equal(m.snapOffsetMs(0, hour), 0, "now stays now");
  // A quantum of 0 leaves the pointer's own position alone.
  assert.equal(m.snapOffsetMs(123456, 0), 123456);
  assert.equal(m.snapOffsetMs(-5000, hour), 0, "never forward of now");
  // Most frames of a drag land on the quantum the frame before already used, and that is
  // the whole saving: the same picture is not asked for twice. Thirty steps of 10px across
  // an 8h window drawn 296px wide is 16 minutes of travel a step, so the window moves once
  // every four steps - 8 renders for the drag, not 30. The travel accumulates unsnapped,
  // because snapping it would drop every step under half a quantum and a slow drag would
  // never move the window at all.
  const stepMs = 10 * (8 * hour / 296);
  let raw = 0;
  let published = 0;
  let changes = 0;
  for (let step = 0; step < 30; step++) {
    raw += stepMs;
    const before = published;
    published = m.snapOffsetMs(raw, hour);
    if (published !== before) {changes++;}
  }
  assert.equal(changes, 8, "8 hours of travel is 8 renders, not 30");
  assert.ok(published > 7.9 * hour && published < 8.3 * hour, "and it ends where the pointer left it");
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

test("a ctrl-drag sizes a graph one px of graph per px of pointer", async () => {
  const m = await load();
  // The pointer is holding the graph's own edge, so the rate has to be 1:1 or the edge
  // drifts away from the finger mid-drag.
  assert.equal(m.sizeFromDrag(296, 50, 160, 7680), 346);
  assert.equal(m.sizeFromDrag(296, -50, 160, 7680), 246);
  // Travel is added a step at a time, and the clamp holds at each step, so a long drag
  // past the bound stays at the bound instead of running away and coming back.
  let width = 296;
  for (let step = 0; step < 100; step++) {width = m.sizeFromDrag(width, 100, 160, 7680);}
  assert.equal(width, 7680, "the largest graph the console draws");
  for (let step = 0; step < 200; step++) {width = m.sizeFromDrag(width, -100, 160, 7680);}
  assert.equal(width, 160, "the smallest graph the console draws");
  // A graph with no size in its src starts at the floor rather than at NaN, which would
  // make every later size NaN and the graph never draw at all.
  assert.equal(m.sizeFromDrag(NaN, 40, 160, 7680), 200);
  // The bounds a gesture hands in are the picture's, not the plot's: the pointer is on the
  // picture, and the caller widens the console's plot bounds by that graph's own chrome, so
  // the plot inside never leaves what the console will draw.
  assert.equal(m.sizeFromDrag(896, 50, 160 + 83, 7680 + 83), 946);
});

test("a graph is asked for its box less the chrome it draws around the plot", async () => {
  const m = await load();
  // Measured on this router at the graphs page's own parameters: asked for the same
  // 813x140 plot, a plain stat's picture comes back 896x270 and the combined bandwidth
  // tile's 896x308. Same plot, 38px more legend, and the page pins every graph box to one
  // size - so one plot for the whole page is either too big for one tile or too small for
  // the other.
  const plain = {width: 83, height: 130};
  const tile = {width: 83, height: 168};
  const box = {width: 896, height: 308};
  assert.deepEqual(m.plotFor(plain, box), {width: 813, height: 178});
  assert.deepEqual(m.plotFor(tile, box), {width: 813, height: 140});
  // Each picture comes out at the box, which is what the single pin needs.
  assert.equal(m.plotFor(plain, box).width + plain.width, m.plotFor(tile, box).width + tile.width);
  assert.equal(m.plotFor(plain, box).height + plain.height, m.plotFor(tile, box).height + tile.height);
  // The clamp is on the plot, because that is what the console refuses to draw outside.
  assert.deepEqual(m.plotFor(plain, {width: 10, height: 10}), {width: 160, height: 40});
  assert.deepEqual(m.plotFor(plain, {width: 99999, height: 99999}), {width: 7680, height: 4320});
});

test("a graph keeps the size of the axis a gesture never moved", async () => {
  const m = await load();
  // A plain stat and the combined bandwidth tile, both drawn 579px wide and 283px tall at
  // this router's own parameters - the tile spends 38px more on two legends and is asked for
  // a 38px shorter plot to make the pictures match.
  const src = (w, h) => "http://127.0.0.1:7657/viewstat.jsp?stat=x&width=" + w + "&height=" + h;
  const plain = {src: src(496, 153), naturalWidth: 579, naturalHeight: 283};
  const tile = {src: src(496, 115), naturalWidth: 579, naturalHeight: 283};
  const chrome = {width: 83, height: 130};
  // A ctrl-drag 121px wider says nothing about height, so each graph keeps the height it was
  // drawn at - and each is asked for its own plot, so both pictures still come out the same
  // size. Redrawing every graph at the anchor's height instead would change proportions.
  assert.deepEqual(m.sizeFor(plain, {width: 700}, chrome), {width: 617, height: 153});
  assert.deepEqual(m.sizeFor(tile, {width: 700}, chrome), {width: 617, height: 115});
  // A ctrl-roll says nothing about width, and the width each graph is drawn at is kept.
  assert.deepEqual(m.sizeFor(plain, {height: 400}, chrome), {width: 496, height: 270});
  assert.deepEqual(m.sizeFor(tile, {height: 400}, chrome), {width: 496, height: 232});
  // Both axes.
  assert.deepEqual(m.sizeFor(plain, {width: 700, height: 400}, chrome), {width: 617, height: 270});
  // A graph whose picture has not come back is sized with the chrome its neighbours were
  // drawn at, not with nothing, or it would be asked for a plot 130px taller than them.
  const loading = {src: src(496, 153), naturalWidth: 0, naturalHeight: 0};
  assert.deepEqual(m.sizeFor(loading, {width: 700}, chrome), {width: 617, height: 153});
  // The clamp is on the plot, because that is what the console refuses to draw outside.
  assert.deepEqual(m.sizeFor(plain, {width: 10}, chrome), {width: 160, height: 153});
});

test("a ctrl-roll sizes a graph by the same step a zoom moves time", async () => {
  const m = await load();
  assert.equal(m.sizeFromRoll(280, 1, 40, 4320), 350);
  assert.equal(m.sizeFromRoll(280, -1, 40, 4320), 224);
  assert.equal(m.sizeFromRoll(280, 0, 40, 4320), 280, "a notchless roll changes nothing");
  // One notch undoes the one before it, whatever the size started at.
  assert.equal(m.sizeFromRoll(m.sizeFromRoll(1000, 3, 40, 4320), -3, 40, 4320), 1000);
  let height = 280;
  for (let step = 0; step < 40; step++) {height = m.sizeFromRoll(height, 1, 40, 4320);}
  assert.equal(height, 4320, "the tallest graph the console draws");
  for (let step = 0; step < 400; step++) {height = m.sizeFromRoll(height, -1, 40, 4320);}
  assert.equal(height, 40, "the shortest graph the console draws");
});

test("a size a gesture settles on lands on the size grid", async () => {
  const m = await load();
  // A size has no natural unit the way a timeframe does, so the grid is what makes a saved
  // one askable again: the width and height fields of the form are how you ask, and nobody
  // types 1934 or 643.
  assert.equal(m.snapSize(1934, 243, 7763), 1930);
  assert.equal(m.snapSize(1936, 243, 7763), 1940);
  assert.equal(m.snapSize(643, 96, 4450), 640);
  // A size already on the grid is left exactly where it is, so a roll that lands on it does
  // not move the graph a pixel either way, and repeated gestures converge instead of drifting.
  assert.equal(m.snapSize(1930, 243, 7763), 1930);
  // Half of a grid moves up, and the same size always goes the same way: a size between two
  // lines is a size the gesture will ask for again, and one that alternates between them
  // never settles.
  assert.equal(m.snapSize(1935, 243, 7763), 1940);
  assert.equal(m.snapSize(m.snapSize(1935, 243, 7763), 243, 7763), 1940, "and stays there");
  // A gesture pushed past either end of the range stops on the last grid line inside it, not
  // on the bound itself: the console's bounds are on the plot inside the graph, and this
  // graph's chrome puts the picture's bounds (243 and 7763 here) between the lines.
  assert.equal(m.snapSize(244, 243, 7763), 250, "the smallest size on the grid this graph allows");
  assert.equal(m.snapSize(7762, 243, 7763), 7760, "the largest");
  assert.equal(m.snapSize(10, 243, 7763), 250, "a drag that ran past the floor");
  assert.equal(m.snapSize(99999, 243, 7763), 7760, "a drag that ran past the ceiling");
  // Only what is published is snapped, never what the gesture accumulates: the pointer's
  // travel is all kept, so a slow drag reaches every line of the grid rather than stalling
  // below one, and the graph moves as soon as the travel passes the halfway point to it.
  let raw = 1930;
  const published = [];
  for (let step = 0; step < 10; step++) {
    raw += 1;
    published.push(m.snapSize(raw, 243, 7763));
  }
  assert.deepEqual(published, [1930, 1930, 1930, 1930, 1940, 1940, 1940, 1940, 1940, 1940]);
  assert.equal(raw, 1940, "and the pointer's travel is all kept, none of it thrown away");
  assert.equal(m.snapSize(NaN, 243, 7763), 243, "a graph with no size of its own starts at the floor");
});

test("the size a gesture writes is named for the page that owns it", async () => {
  const m = await load();
  const win = {spanMs: 8 * 3600000, offsetMs: 0, width: 1500, height: 420};
  // The graphs page's width and height are the size of its two dozen small images; the
  // single-graph page's singleW and singleH are its own. One key cannot serve both: a
  // 1500px single graph saved into the grid's keys is a wall of 1500px images.
  assert.deepEqual(m.configFields(win, false), {periodCount: 480, width: 1500, height: 420});
  assert.deepEqual(m.configFields(win, true), {periodCount: 480, singleW: 1500, singleH: 420});
  // A gesture that only moved through time says nothing about size, so the form's own
  // width and height fields, which hold what is saved, go through untouched.
  assert.deepEqual(m.configFields({spanMs: 8 * 3600000, offsetMs: 3600000}, false), {periodCount: 480});
  assert.deepEqual(m.configFields({spanMs: 8 * 3600000, offsetMs: 0}, true), {periodCount: 480});
  // A page opened at a size by one of the single page's own Larger/Taller links carries a
  // c= of its own - three times the saved count for a tile that plots send and receive
  // together - and that is a look at one stat's history, not a preference. Only the size
  // is saved.
  assert.deepEqual(m.configFields({width: 1500, height: 420}, true), {singleW: 1500, singleH: 420});
  assert.deepEqual(m.configFields({width: 1500, height: 420}, false), {width: 1500, height: 420});
  // A save that names nothing leaves every setting the router holds alone.
  assert.deepEqual(m.configFields({}, true), {});
});

test("a form's fields become one url-encoded body, the FIRST value per name", async () => {
  const m = await load();
  // The console's slider boxes are a checked input followed by a hidden one carrying the
  // opposite value, so a form holds the name twice. A servlet's getParameter() answers
  // with the first, so the first is what the body must carry: keeping the last instead
  // posts the hidden value for every option the page has switched on, and one save
  // turns glow, smoothing, the legend and the rest off.
  const fields = [["action", "Save"], ["nonce", "CN123"], ["periodCount", "480"],
                  ["graphGlow", "true"], ["graphGlow", "false"],
                  ["hideRestarts", "true"], ["hideRestarts", "false"]];
  assert.equal(m.formBody(fields),
               "action=Save&nonce=CN123&periodCount=480&graphGlow=true&hideRestarts=true");
  // An override still wins over both halves of the pair.
  assert.equal(m.formBody(fields, {periodCount: 90, nonce: "CN999"}),
               "action=Save&nonce=CN999&periodCount=90&graphGlow=true&hideRestarts=true");
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
  assert.ok(Number.isInteger(st.graphs) && st.graphs >= 0, "the count on the page");
  assert.ok(Number.isInteger(st.visible) && st.visible <= st.graphs, "the count on the screen");
  // The biggest picture on the page and the plot that picture becomes in the configuration.
  // Whether the shim has anything drawn is its own business - other suites leave images in
  // the shared document - so what is pinned here is that the two travel together and the
  // plot never outruns the box it was reduced from.
  assert.equal(st.plot === null, st.box === null, "a plot without a box, or the reverse, is a bug");
  if (st.box) {
    assert.ok(st.plot.width <= st.box.width && st.plot.height <= st.box.height, "the plot sits inside its box");
  }
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

test("a roll over a graph is the page's scroll nowhere, even at the end of the zoom", async () => {
  const m = await load();
  globalThis.fetch = () => Promise.resolve({ok: false});
  // Already at the shortest window the module allows: rolling up further has nowhere to
  // go. The wheel is still cancelled, because a page that starts scrolling under a
  // wheel that is zooming a graph moves the graph off the screen mid-gesture.
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
  assert.equal(cancelled, 1, "the wheel is claimed even where the zoom cannot move");
});

test("a roll over the box holding a graph belongs to that graph", async () => {
  const m = await load();
  globalThis.fetch = () => Promise.resolve({ok: false});
  const img = {
    closest: (sel) => (sel === "img.statimage" ? img : null),
    getAttribute: () => null,
    src: "http://127.0.0.1:7657/viewstat.jsp?stat=router.activePeers&period=60000&periodCount=480",
    naturalWidth: 379,
    getBoundingClientRect: () => ({left: 0, top: 0, width: 379, height: 178}),
  };
  // The console wraps every graph in .graphContainer; a roll on the box's border has to
  // reach the graph inside it, not fall through to the page.
  const box = {closest: (sel) => (sel === ".graphContainer" ? box : null),
               querySelector: (sel) => (sel === "img.statimage" ? img : null)};
  let cancelled = 0;
  m.onWheel({target: box, deltaY: -120, deltaMode: 0, clientX: 200, clientY: 50,
             preventDefault: () => {cancelled++;}});
  assert.equal(cancelled, 1);
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
  // The graph is held for the whole gesture, so the cursor that says so covers the box
  // holding the graph and not just the picture, and the page's text cannot be dragged out
  // from under a pointer that runs off the graph mid-drag.
  assert.match(rules, /\.graphPanZoomGesture \.graphContainer[^\}]*cursor:\s*grabbing/);
  assert.match(rules, /\.graphPanZoomGesture \.graphContainer[^\}]*user-select:\s*none/);
});

test("ctrl sizes the graph on the single-graph page and nowhere else", async () => {
  const m = await load();
  const img = {tagName: "IMG", classList: {contains: () => false},
               closest: (sel) => (sel === "img.statimage" ? img : null),
               getAttribute: () => null, addEventListener: () => {},
               naturalWidth: 579, naturalHeight: 283,
               src: "http://127.0.0.1:7657/viewstat.jsp?stat=router.activePeers" +
                    "&period=60000&periodCount=480&width=496&height=153"};
  // A pan needs the graph's plot geometry before it can turn pointer px into time, and a
  // size gesture does not - so the fetch count says which gesture a press started.
  let fetches = 0;
  globalThis.fetch = () => {fetches++; return Promise.resolve({ok: false});};
  // Which page this is, stated rather than assumed: the suites share one document, so what
  // an absent id answers with is whichever suite ran last.
  const byId = document.getElementById.bind(document);
  const marker = document.createElement("span");
  const single = () => {document.getElementById = (id) => (id === "graphSingle" ? marker : byId(id));};
  const grid = () => {document.getElementById = (id) => (id === "graphSingle" ? null : byId(id));};

  single();
  await m.onPointerDown({button: 0, ctrlKey: true, target: img, clientX: 200, clientY: 50});
  assert.equal(fetches, 0, "ctrl-drag sizes the one graph, and starts without a metadata read");
  m.onWheel({target: img, deltaY: -120, deltaMode: 0, ctrlKey: true, clientX: 200, clientY: 50,
             preventDefault: () => {}});
  assert.equal(fetches, 0, "ctrl-roll does the same");

  // On the graphs page the two dozen tiles are one size and the page's own form sets it, so
  // a ctrl press there is the plain gesture with a modifier on it.
  grid();
  await m.onPointerDown({button: 0, ctrlKey: true, target: img, clientX: 200, clientY: 50});
  assert.equal(fetches, 1, "ctrl-drag on the grid pans");
  m.onWheel({target: img, deltaY: -120, deltaMode: 0, ctrlKey: true, clientX: 200, clientY: 50,
             preventDefault: () => {}});
  assert.equal(fetches, 2, "ctrl-roll on the grid zooms the timeframe");
  document.getElementById = byId;
});

test("the native drag of a graph or of the link wrapped around it is cancelled", async () => {
  const m = await load();
  const img = {closest: (sel) => (sel === "img.statimage" ? img : null)};
  let cancelled = 0;
  m.onDragStart({target: img, preventDefault: () => {cancelled++;}});
  assert.equal(cancelled, 1, "the image itself");
  // Every console graph sits inside a link - to the single-graph page from the list,
  // back to the list from the single graph - and a drag that starts on the link is
  // dragged by the browser as a link unless it is cancelled there too.
  const box = {closest: (sel) => (sel === ".graphContainer" ? box : null),
               querySelector: (sel) => (sel === "img.statimage" ? img : null)};
  m.onDragStart({target: box, preventDefault: () => {cancelled++;}});
  assert.equal(cancelled, 2, "the container holding it");
  // Somewhere else on the page, a drag is the page's own business.
  m.onDragStart({target: {closest: () => null}, preventDefault: () => {cancelled++;}});
  assert.equal(cancelled, 2, "not a graph");
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
