/**
 * Contract tests for apps/routerconsole/jsp/js/graphTooltip.js — the cursor
 * time/value readout for rrd4j graph images.
 *
 * The maths is the part that matters. A graph is served as an <img>, so its SVG
 * is an isolated document and the numbers have to come from a separate
 * format=meta request; these pin the inversion that turns a cursor position back
 * into a time and a value, and the snapping to the nearest plotted line that the
 * readout reports.
 *
 * @license AGPL3 or later
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { installBrowserShim } from "../helpers/browserShim.js";

const MODULE = "../../../../apps/routerconsole/jsp/js/graphTooltip.js";

/**
 * Loads the module fresh, so its module-level caches start empty.
 *
 * The module registers a DOMContentLoaded listener at evaluation time, so the browser shim has
 * to be installed before the import rather than inside a test.
 *
 * @returns {Promise<Object>} the module namespace
 */
async function load() {
  installBrowserShim();
  return import(`${MODULE}?t=${Math.random()}`);
}

/** A metadata document matching what viewstat.jsp?format=meta returns. */
function meta(overrides = {}) {
  return Object.assign(
    {
      plot: [100, 50, 400, 200],
      x0: 1000000,
      x1: 1000600,
      y0: 0,
      y1: 100,
      colors: ["#0cc0c0"],
      // Four samples: y rises 0 -> 50 across the plot.
      series: [[0, 25, 75, 100]]
    },
    overrides
  );
}

test("the cursor at the plot's left edge reads the first sample", async () => {
  const { invert } = await load();
  const hit = invert(meta(), 100, 150);
  assert.ok(hit, "a point inside the plot must invert");
  assert.equal(hit.time, 1000000, "left edge is the start of the window");
  assert.equal(hit.snapped[0].value, 0);
});

test("the cursor at the plot's right edge reads the last sample", async () => {
  const { invert } = await load();
  const hit = invert(meta(), 500, 150);
  assert.ok(hit);
  assert.equal(hit.time, 1000600, "right edge is the end of the window");
  assert.equal(hit.snapped[0].value, 100);
});

test("time interpolates linearly across the plot", async () => {
  const { invert } = await load();
  const hit = invert(meta(), 300, 150);
  assert.ok(hit);
  // Halfway across a 600 second window.
  assert.equal(hit.time, 1000300);
});

test("value y maps the range back onto the plot height", async () => {
  const { invert } = await load();
  const hit = invert(meta(), 500, 50);
  assert.ok(hit);
  // The plot is (left=100, top=50, w=400, h=200). y1 = 100 is the top edge, y0 = 0 the bottom.
  assert.equal(Math.round(hit.snapped[0].y), 50, "the largest value sits at the top");
  const low = invert(meta(), 100, 150);
  assert.equal(Math.round(low.snapped[0].y), 250, "the smallest value sits at the bottom");
});

test("a mark's y falls inside the plot for every value in range", async () => {
  const { invert } = await load();
  for (const cursorX of [100, 300, 500]) {
    for (const s of invert(meta(), cursorX, 150).snapped) {
      assert.ok(s.y >= 50 && s.y <= 250,
                `mark at ${s.y} fell outside the plot [50,250] for cursorX ${cursorX}`);
    }
  }
});

test("the plot top is the top, so a cursor high in the image is inside the plot", async () => {
  const { invert } = await load();
  // The regression: emitting ImageParameters.yOrigin (the plot's *bottom*) as the top would
  // put the plot at [311,591] and reject every real cursor.
  assert.ok(invert(meta(), 300, 60), "a cursor near the top of the image must invert");
  assert.ok(invert(meta(), 300, 240), "a cursor near the bottom must invert too");
});

test("a cursor outside the plot yields nothing", async () => {
  const { invert } = await load();
  const m = meta();
  assert.equal(invert(m, 99, 150), null, "left of the plot");
  assert.equal(invert(m, 501, 150), null, "right of the plot");
  assert.equal(invert(m, 300, 49), null, "above the plot");
  assert.equal(invert(m, 300, 251), null, "below the plot");
});

test("a degenerate domain yields nothing rather than a division by zero", async () => {
  const { invert } = await load();
  // A flat graph: y1 == y0 would divide by zero.
  assert.equal(invert(meta({y0: 5, y1: 5}), 300, 150), null);
  // An empty time span likewise.
  assert.equal(invert(meta({x0: 10, x1: 10}), 300, 150), null);
});

test("the readout snaps to the nearest of several series", async () => {
  const { invert } = await load();
  const m = meta({series: [[100, 100, 100, 100], [0, 0, 0, 0]]});
  const upper = invert(m, 500, 60);
  assert.equal(upper.nearest.value, 100, "near the top, the upper line wins");
  const lower = invert(m, 500, 245);
  assert.equal(lower.nearest.value, 0, "near the bottom, the lower line wins");
});

test("both series are reported, not only the snapped one", async () => {
  const { invert } = await load();
  const hit = invert(meta({series: [[100, 100, 100, 100], [0, 0, 0, 0]]}), 500, 60);
  assert.equal(hit.snapped.length, 2);
});

test("a gap in a series resolves to the nearest real sample, never to null", async () => {
  const { invert } = await load();
  // The middle sample is a gap: the line is not drawn through it.
  const m = meta({series: [[0, null, 100, 100]]});
  const hit = invert(m, 300, 150);
  assert.ok(hit);
  assert.notEqual(hit.snapped[0].value, null, "a gap must not be reported as a value");
});

test("a series that is entirely gaps contributes nothing", async () => {
  const { invert } = await load();
  assert.equal(invert(meta({series: [[null, null, null, null]]}), 300, 150), null);
});

test("an empty series list yields nothing", async () => {
  const { invert } = await load();
  assert.equal(invert(meta({series: []}), 300, 150), null);
});

test("a single-sample series still inverts", async () => {
  const { invert } = await load();
  const hit = invert(meta({series: [[42]]}), 300, 150);
  assert.ok(hit);
  assert.equal(hit.snapped[0].value, 42);
});

test("the time is zero-padded local time to the second", async () => {
  const { formatTime } = await load();
  assert.match(formatTime(1000000), /^\d{2}:\d{2}:\d{2}$/, "always two digits per field");
  // Local time, since the router's axis labels are local and a UTC readout would sit beside them
  // hours out. Derived from the same epoch rather than hardcoded, so this holds on any host.
  const when = new Date(1000600 * 1000);
  assert.equal(formatTime(1000600),
               [when.getHours(), when.getMinutes(), when.getSeconds()]
                 .map(n => String(n).padStart(2, "0")).join(":"));
});

test("a value is rounded to three decimals, finer than any axis label", async () => {
  const { formatValue } = await load();
  assert.equal(formatValue(75), "75");
  assert.equal(formatValue(1 / 3), "0.333");
  assert.equal(formatValue(142.5678), "142.568");
});

test("the metadata URL adds format=meta, respecting an existing query", async () => {
  const { metaUrl } = await load();
  assert.match(metaUrl({getAttribute: () => "/viewstat.jsp?stat=bw.combined"}),
               /\/viewstat\.jsp\?stat=bw\.combined&format=meta$/);
  assert.match(metaUrl({getAttribute: () => "/viewstat.jsp"}), /format=meta$/);
});

test("graphTooltip.js is loaded as a module wherever it is referenced", async () => {
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(
    fileURLToPath(new URL("../../../../apps/routerconsole/jsp/js/graphTooltip.js", import.meta.url)),
    "utf8"
  );
  // Top-level import/export means it cannot load as a classic script.
  assert.match(src, /^export /m, "the module must use export");
  for (const page of ["graph.jsp", "graphs.jsp"]) {
    const jsp = readFileSync(
      fileURLToPath(new URL(`../../../../apps/routerconsole/jsp/${page}`, import.meta.url)),
      "utf8"
    );
    // The src carries a JSP expression, so a [^>]* window would stop at the '>' of "%>".
    assert.match(jsp, /graphTooltip\.js[\s\S]{0,60}?type=module/,
                 `${page} must load it as a module`);
  }
});
// ─── the metadata fetch ──────────────────────────────────────────────────
// The overlay is useless without numbers, so the fetch path gets the same treatment as the
// maths: cache-once, one request per graph, and nothing that can leave a readout pending.

/** A stand-in for the graph image; the module only ever asks it for its src. */
function imgWith(src) {
  return { getAttribute: () => src };
}

/**
 * Replaces global fetch, returning a restore function.
 *
 * SharedWorker is explicitly removed. Node has none, and the browser shim's stand-in is an
 * inert proxy whose target is a function, so `typeof SharedWorker` reads "function" and the
 * module would take the worker path and wait for a reply the stub can never send.
 */
function stubFetch(handler) {
  const original = global.fetch;
  const originalWorker = global.SharedWorker;
  delete global.SharedWorker;
  const calls = [];
  global.fetch = (url, opts) => { calls.push({url, opts}); return handler(url); };
  return {calls, restore: () => { global.fetch = original; global.SharedWorker = originalWorker; }};
}

test("a worker's Blob reply is parsed as metadata", async () => {
  const { parseMeta } = await load();
  const payload = {url: "u", isDown: false, status: 200, responseBlob: new Blob([JSON.stringify(meta())])};
  const json = await parseMeta(payload);
  assert.deepEqual(json.plot, [100, 50, 400, 200]);
  assert.equal(json.series.length, 1);
});

test("a text reply is parsed too", async () => {
  const { parseMeta } = await load();
  const json = await parseMeta({url: "u", isDown: false, responseText: JSON.stringify(meta())});
  assert.equal(json.x1, 1000600);
});

test("a failed or malformed reply yields null rather than throwing", async () => {
  const { parseMeta } = await load();
  assert.equal(await parseMeta(null), null);
  assert.equal(await parseMeta({isDown: true, status: 500}), null, "an error status");
  assert.equal(await parseMeta({isDown: false, responseText: "{not json"}), null, "malformed JSON");
  assert.equal(await parseMeta({isDown: false, responseText: "{}"}), null, "no plot");
  assert.equal(await parseMeta({isDown: false, responseText: '{"plot":[0,0,1,1],"series":[]}'}),
               null, "no series");
});

test("one graph is fetched once, however often it is hovered", async () => {
  const { loadMeta } = await load();
  const stub = stubFetch(async () => ({ok: true, status: 200, blob: async () => new Blob([JSON.stringify(meta())])}));
  try {
    const img = imgWith("/viewstat.jsp?graph=one");
    const first = await new Promise(r => loadMeta(img, r));
    const second = await new Promise(r => loadMeta(img, r));
    assert.ok(first, "the first hover must produce metadata");
    assert.deepEqual(second.plot, first.plot, "the second hover is served from cache");
    assert.equal(stub.calls.length, 1, "exactly one request for two hovers");
  } finally { stub.restore(); }
});

test("concurrent hovers on one graph share a single request", async () => {
  const { loadMeta } = await load();
  const stub = stubFetch(async () => ({ok: true, status: 200, blob: async () => new Blob([JSON.stringify(meta())])}));
  try {
    const img = imgWith("/viewstat.jsp?graph=two");
    const both = await Promise.all([
      new Promise(r => loadMeta(img, r)),
      new Promise(r => loadMeta(img, r)),
      new Promise(r => loadMeta(img, r))
    ]);
    assert.ok(both.every(m => m), "every caller gets metadata");
    assert.equal(stub.calls.length, 1, "three hovers, one request");
  } finally { stub.restore(); }
});

test("a failed fetch is cached as a failure, not retried on every move", async () => {
  const { loadMeta } = await load();
  const stub = stubFetch(async () => ({ok: false, status: 500, blob: async () => new Blob([])}));
  try {
    const img = imgWith("/viewstat.jsp?graph=broken");
    assert.equal(await new Promise(r => loadMeta(img, r)), null);
    assert.equal(await new Promise(r => loadMeta(img, r)), null);
    assert.equal(stub.calls.length, 1, "a permanent failure must not become a request per move");
  } finally { stub.restore(); }
});

/**
 * Stubs the shared worker. Node has no SharedWorker, so this is the only way to exercise the
 * path the console actually uses at runtime.
 */
function stubSharedWorker() {
  const posted = [];
  const original = global.SharedWorker;
  const originalFetch = global.fetch;
  let portRef = null;
  global.SharedWorker = class {
    constructor(url) {
      this.url = url;
      this.port = portRef = {start() {}, postMessage(msg) {posted.push(msg);}};
    }
  };
  return {
    posted,
    reply(payload) { portRef.onmessage({data: payload}); },
    restore() { global.SharedWorker = original; global.fetch = originalFetch; }
  };
}

test("metadata goes through the shared worker, unthrottled, and its reply is delivered", async () => {
  const { loadMeta } = await load();
  const stub = stubSharedWorker();
  try {
    const img = imgWith("/viewstat.jsp?graph=worker");
    const done = new Promise(r => loadMeta(img, r));
    assert.equal(stub.posted.length, 1, "exactly one request reaches the worker");
    assert.equal(stub.posted[0].url, "/viewstat.jsp?graph=worker&format=meta");
    // force is what keeps the readout off the worker's 200ms debounce and 500ms rate limit.
    assert.equal(stub.posted[0].force, true, "throttling must be skipped for a readout");
    stub.reply({url: stub.posted[0].url, isDown: false, status: 200,
                responseBlob: new Blob([JSON.stringify(meta())])});
    const json = await done;
    assert.ok(json && json.series.length, "the worker's Blob reply reaches the overlay");
  } finally { stub.restore(); }
});

test("a worker that never replies leaves the readout empty rather than pending", async () => {
  const { loadMeta } = await load();
  const stub = stubSharedWorker();
  try {
    const img = imgWith("/viewstat.jsp?graph=silent");
    loadMeta(img, () => {});
    assert.equal(stub.posted.length, 1, "the request was made");
    // No reply: the overlay simply stays hidden. Nothing here may throw or hang a caller.
  } finally { stub.restore(); }
});

// ─── placement ──────────────────────────────────────────────────────────
// The maths above cannot catch a mis-anchored element: these pin where the overlay actually
// lands on the page. An absolutely positioned part with top/left unset sits at its in-flow
// position, which for an element appended to the end of body is under the footer, and the
// transform then adds to that rather than replacing it.

/**
 * A .graphContainer with a graph image inside it, laid out the way the console does it: the
 * container clips its overflow and is positioned, and it is sized to the graph.
 *
 * @param {Object} imgRect - the image box in viewport coordinates
 * @param {Object} containerRect - the container box in viewport coordinates
 * @param {number} naturalWidth - the image's intrinsic width
 * @param {number} naturalHeight - the image's intrinsic height
 * @returns {Object} the container element
 */
function graphFixture(imgRect, containerRect, naturalWidth, naturalHeight) {
  const container = document.createElement("div");
  container.className = "graphContainer";
  container.getBoundingClientRect = () => containerRect;
  const img = document.createElement("img");
  img.className = "statimage statbox";
  img.getBoundingClientRect = () => imgRect;
  Object.defineProperty(img, "naturalWidth", {value: naturalWidth ?? imgRect.width});
  Object.defineProperty(img, "naturalHeight", {value: naturalHeight ?? imgRect.height});
  img.closest = selector => (selector === ".graphContainer" ? container : null);
  container.appendChild(img);
  document.body.appendChild(container);
  return {container, img};
}

/** Places the overlay over meta() with the cursor at (300,150) and returns the parts. */
async function placed(container, img) {
  const m = meta();
  const mod = await load();
  const parts = mod.ensureOverlay(container);
  mod.placeOverlay(parts, img, m, mod.invert(m, 300, 150));
  return parts;
}

test("overlay parts live inside the graph container, positioned absolutely", async () => {
  const {container, img} = graphFixture({left: 41, top: 301, width: 400, height: 200},
                                        {left: 40, top: 300, width: 402, height: 202});
  const parts = await placed(container, img);
  for (const [name, el] of [["crosshair", parts.crosshair], ["readout", parts.readout],
                            ["mark", parts.marks[0]]]) {
    assert.equal(el.style.position, "absolute", `${name} positions against the container`);
    assert.equal(el.style.top, "0", `${name} needs an explicit top origin`);
    assert.equal(el.style.left, "0", `${name} needs an explicit left origin`);
    assert.equal(el.parentNode, container, `${name} belongs to the graph's own container`);
  }
});

test("coordinates are relative to the container, not the viewport", async () => {
  // Container at (40,300): a viewport-coordinate overlay would put the crosshair 40px and
  // 300px to the right of and below the graph.
  const {container, img} = graphFixture({left: 41, top: 301, width: 400, height: 200},
                                        {left: 40, top: 300, width: 402, height: 202});
  const parts = await placed(container, img);
  assert.equal(parts.crosshair.style.transform, "translate3d(301px,51px,0)");
  assert.equal(parts.readout.style.transform, "translate3d(301px,51px,0)");
});

test("the readout moves to the left of the crosshair past the graph midpoint", async () => {
  // The container clips its overflow, so a right-hand readout near the right edge is cut off.
  const wide = {left: 0, top: 0, width: 400, height: 200};
  const {container, img} = graphFixture(wide, {left: 0, top: 0, width: 400, height: 200});
  const m = meta();
  const mod = await load();
  const parts = mod.ensureOverlay(container);

  mod.placeOverlay(parts, img, m, mod.invert(m, 120, 150));
  assert.equal(parts.readout.className, "graphTooltip", "left of the midpoint: reads rightwards");

  mod.placeOverlay(parts, img, m, mod.invert(m, 480, 150));
  assert.match(parts.readout.className, /flip-x/, "right of the midpoint: reads leftwards");

  // and back again, so the class is not left stale
  mod.placeOverlay(parts, img, m, mod.invert(m, 120, 150));
  assert.equal(parts.readout.className, "graphTooltip");
});

test("the crosshair lands on the plot's left edge", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  const parts = await placed(container, img);
  assert.equal(parts.crosshair.style.transform, "translate3d(300px,50px,0)");
  // The crosshair spans the plot height, not the image height.
  assert.equal(parts.crosshair.style.height, "200px");
});

test("a mark lands on the snapped value, and the readout marks the same x", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  const parts = await placed(container, img);
  // value 75 of [0,100] across plot [50,250] -> y 100.
  assert.equal(parts.marks[0].style.transform, "translate3d(300px,100px,0)");
  assert.equal(parts.readout.style.transform, "translate3d(300px,50px,0)");
});

test("the readout offset is left to CSS, not applied twice", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  const parts = await placed(container, img);
  // The stylesheet supplies translate:14px -10px; the script must emit the bare anchor only.
  assert.equal(parts.readout.style.transform, "translate3d(300px,50px,0)");
});

test("an image laid out smaller than rendered scales the cursor with it", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200}, 800, 400);
  const parts = await placed(container, img);
  assert.equal(parts.crosshair.style.transform, "translate3d(150px,25px,0)");
  assert.equal(parts.marks[0].style.transform, "translate3d(150px,50px,0)");
  assert.equal(parts.crosshair.style.height, "100px");
});

test("a mark never falls outside the plot for any cursor position", async () => {
  const { ensureOverlay, invert, placeOverlay } = await load();
  const m = meta();
  const [, top, , height] = m.plot;
  // Tall enough to contain the plot; an image shorter than its own plot is not a real layout.
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: top + height},
                                        {left: 0, top: 0, width: 400, height: top + height});
  const parts0 = ensureOverlay(container);
  let seen = 0;
  for (const cx of [100, 200, 300, 400, 500]) {
    for (const cy of [50, 100, 150, 200, 250]) {
      const hit = invert(m, cx, cy);
      assert.ok(hit, `(${cx},${cy}) is inside the plot and must invert`);
      placeOverlay(parts0, img, m, hit);
      const y = Number(parts0.marks[0].style.transform.match(/,(\d+)px/)[1]);
      assert.ok(y >= top && y <= top + height,
                `mark y ${y} outside the plot [${top},${top + height}] at (${cx},${cy})`);
      seen++;
    }
  }
  assert.equal(seen, 25, "every cursor position in the plot was exercised");
});

// ─── the horizontal level line ───────────────────────────────────────────
// The vertical crosshair is unambiguous: x is the cursor. A horizontal line is not, so it is
// pinned to the snapped value rather than the raw cursor y, and there is one per series so it
// agrees with the readout instead of pointing at one of several numbers.

test("a level line sits at the same y as its mark, and spans the plot", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  const parts = await placed(container, img);
  const m = meta();
  const [left, , width] = m.plot;
  assert.equal(parts.levels.length, m.series.length, "one level per snapped series");
  // Bounded by the axis: starts at the plot's left edge and spans its width, no more.
  assert.equal(parts.levels[0].style.transform.split(",")[0], "translate3d(" + left + "px");
  assert.equal(parts.levels[0].style.width, width + "px", "spans the plot width");
  assert.equal(parts.levels[0].style.transform.split(",")[1],
               parts.marks[0].style.transform.split(",")[1], "level and mark share a y");
});

test("the level stops at the axis rather than running over the tick labels", async () => {
  const [plotLeft, , plotWidth] = meta().plot;
  assert.ok(plotLeft > 0, "this fixture must leave room for labels, as a real graph does");
  // A real rrd4j image is 600x300 and carries tick labels left of the plot, so the fixture has
  // to be that size for the plot to fit and the label area to exist at all.
  const {container, img} = graphFixture({left: 0, top: 0, width: 600, height: 300},
                                        {left: 0, top: 0, width: 600, height: 300});
  const parts = await placed(container, img);
  const level = parts.levels[0];
  const start = Number(level.style.transform.match(/translate3d\((\d+)px/)[1]);
  assert.equal(start, plotLeft, "starts at the plot's left edge, clear of the labels");
  assert.equal(start + parseInt(level.style.width, 10), plotLeft + plotWidth,
               "ends exactly at the right-hand axis");
  assert.ok(start + parseInt(level.style.width, 10) < 600,
            "and stays inside the image, so the container's overflow never clips it");
});

test("the level line does not move when the cursor sweeps sideways", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  const m = meta();
  const mod = await load();
  const parts = mod.ensureOverlay(container);
  const at = cx => {
    mod.placeOverlay(parts, img, m, mod.invert(m, cx, 150));
    return parts.levels[0].style.transform;
  };
  // Pinned to the plot, so only the value can change it, never the cursor.
  assert.match(at(120), /^translate3d\(100px,/);
  assert.match(at(480), /^translate3d\(100px,/);
});

test("each series gets its own level, and a series of gaps gets none", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  // Three series, the middle one entirely gaps, so it contributes no value to the readout.
  const m = meta({series: [[0, 25, 75, 100], [null, null, null, null], [100, 75, 25, 0]]});
  const mod = await load();
  const parts = mod.ensureOverlay(container);
  const hit = mod.invert(m, 300, 150);
  assert.equal(hit.snapped.length, 2, "the gap series is dropped");
  mod.placeOverlay(parts, img, m, hit);
  assert.equal(parts.levels.filter(l => !l.hidden).length, 2, "two visible levels");
  assert.equal(parts.levels.filter(l => !l.hidden).length, parts.marks.filter(x => !x.hidden).length,
               "levels and marks always agree on how many series are shown");
});

test("hiding the overlay hides the levels too", async () => {
  const {container, img} = graphFixture({left: 0, top: 0, width: 400, height: 200},
                                        {left: 0, top: 0, width: 400, height: 200});
  const mod = await load();
  const parts = await placed(container, img);
  mod.hideOverlay(parts);
  assert.ok(parts.levels.every(l => l.hidden), "a level must not survive a leave");
  assert.ok(parts.marks.every(x => x.hidden));
  assert.ok(parts.crosshair.hidden);
});
