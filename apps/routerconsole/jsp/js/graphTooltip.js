/**
 * @module graphTooltip
 * @file graphTooltip.js - Cursor time/value readout for rrd4j graph images.
 * @description Graphs are served as <img>, so their SVG is an isolated document: a script cannot
 * run inside it and the page cannot read its DOM. The numbers a readout needs therefore arrive
 * separately, from the same endpoint with format=meta, and are fetched only when a graph is
 * actually hovered. Nothing here touches a page until the first hover.
 *
 * As the cursor moves, a crosshair follows it in x, the readout snaps in y to the nearest plotted
 * line at that x, and a mark is drawn on every series there. Three marks on a two-series graph is
 * three more elements than the text alone, but without them the numbers have nothing pointing at
 * them: a combined bandwidth graph reports two values and either could be mistaken for the other.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

/** Graph images this module takes over. */
const GRAPH_SELECTOR = "img.statimage";

/** Metadata already fetched, keyed by the image URL. */
const metaCache = new Map();

/** Requests still outstanding, keyed by URL, so a re-hover joins one in flight. */
const inFlight = new Map();

/** Callers waiting on the shared worker, keyed by URL. */
const workerWaiters = new Map();

/** The shared worker's port, false once it is known to be unavailable, null until first needed. */
let workerPort = null;

/**
 * The budget for one metadata round trip. The worker's own abort is 10s, which is the right
 * figure for a page refresh but far too long for a cursor readout: a hover that does nothing
 * for ten seconds reads as a broken page. This is a backstop for a dropped reply on the shared
 * port, not the expected latency, which is one fetch.
 */
const META_TIMEOUT_MS = 4000;

/**
 * Placement measurements, keyed by image: the image rect, the container's origin inside its own
 * border, the container width, and the cursor scale factors.
 *
 * getBoundingClientRect() forces layout, and doing that once per animation frame is the one way
 * this module could cost more than it saves, so every layout read lives in measure() and nowhere
 * else. Invalidated wholesale on scroll and resize; the readout is hidden on both, so a stale
 * entry is never visible.
 */
const measurements = new WeakMap();

/** The most recent pointer position, flushed once per animation frame. */
let pending = null;
let frameQueued = false;

/**
 * Overlay parts per container, created on first hover of that graph and reused after.
 *
 * The parts live inside the graph's own .graphContainer rather than at the end of body: that
 * container is already position:relative in every theme, so absolute children land correctly,
 * and a theme can scope its rules to .graphContainer .graphTooltip.
 */
const overlays = new WeakMap();

/** The overlay currently on screen, so a leave hides only that one. */
let activeOverlay = null;

/** Bumped on scroll and resize to invalidate every cached rect at once. */
let rectCacheEpoch = 0;

/**
 * The metadata URL for a graph image: its own source with format=meta added.
 *
 * @param {HTMLImageElement} img - the graph image
 * @returns {string} the metadata URL
 */
function metaUrl(img) {
  const src = img.getAttribute("src") || "";
  return src + (src.indexOf("?") === -1 ? "?" : "&") + "format=meta";
}

/**
 * The element an overlay part should be positioned against: the graph's container when it has
 * one, otherwise the body.
 *
 * @param {HTMLImageElement} img - the graph image
 * @returns {Element} the positioning container
 */
export function containerFor(img) {
  const container = img && img.closest && img.closest(".graphContainer");
  return container || document.body;
}

/**
 * Builds the overlay parts for a container, once.
 *
 * Only the functional styles are set inline, so the module works before any stylesheet lands.
 * Appearance, including how far the readout sits off the cursor, belongs to the class names.
 *
 * @param {Element} container - the graph container the parts belong to
 * @returns {Object} the overlay parts
 */
export function ensureOverlay(container) {
  const cached = overlays.get(container);
  if (cached) { return cached; }
  const crosshair = document.createElement("div");
  crosshair.className = "graphTooltipCrosshair";
  const readout = document.createElement("div");
  readout.className = "graphTooltip";
  // Two rows, each its own element so a theme can style the values and the time separately.
  const valuesRow = document.createElement("div");
  valuesRow.className = "graphTooltipValues";
  const timeRow = document.createElement("div");
  timeRow.className = "graphTooltipTime";
  readout.appendChild(valuesRow);
  readout.appendChild(timeRow);
  const parts = {container, crosshair, readout, valuesRow, timeRow, items: [],
                 marks: [], levels: [], host: container};
  // `hidden` is the single show/hide mechanism: it is semantic, and a theme can back it with a
  // [hidden] rule. Inline styles carry only what layout needs.
  for (const el of [crosshair, readout]) { attach(el, container); }
  overlays.set(container, parts);
  return parts;
}

/**
 * Puts an overlay part inside its container, ready to be positioned.
 *
 * The parts are absolute children of a position:relative container, and the container's border
 * is excluded from that coordinate space, so the transform supplies the real offset from a
 * known origin rather than adding to wherever the element would have flowed to.
 *
 * @param {HTMLElement} el - the element to attach
 * @param {Element} container - the graph container
 * @returns {void}
 */
function attach(el, container) {
  el.hidden = true;
  el.style.position = "absolute";
  el.style.top = "0";
  el.style.left = "0";
  el.style.pointerEvents = "none";
  container.appendChild(el);
}

/**
 * Shows or hides one overlay's parts.
 *
 * @param {Object} parts - the overlay parts
 * @param {boolean} visible - true to show
 * @returns {void}
 */
function setVisible(parts, visible) {
  if (!parts) { return; }
  parts.crosshair.hidden = !visible;
  parts.readout.hidden = !visible;
  for (const m of parts.marks) { m.hidden = !visible; }
  for (const l of parts.levels) { l.hidden = !visible; }
}

/**
 * Hides an overlay, by default the one on screen.
 *
 * Taking the parts explicitly matters for scroll and resize, where every overlay has to be
 * hidden rather than only the one the pointer happened to be over.
 *
 * @param {Object} parts - the overlay parts to hide, or omitted for the active one
 * @returns {void}
 */
export function hideOverlay(parts) {
  const target = parts || activeOverlay;
  setVisible(target, false);
  if (target === activeOverlay) { activeOverlay = null; }
}

/**
 * Inverts a cursor position against the metadata.
 *
 * The plot rectangle arrives as a conventional (left, top, width, height) in the image's own
 * pixels, so the cursor must be in those pixels too: an image laid out smaller than it was
 * rendered scales the cursor, and the plot would otherwise be read at the wrong scale.
 *
 * Returns the time at the cursor and, for each series, the value of the sample nearest the
 * cursor in x together with the y pixel it sits at. Null where the cursor is outside the plot.
 *
 * @param {Object} meta - the parsed metadata document
 * @param {number} cursorX - cursor x in image pixels
 * @param {number} cursorY - cursor y in image pixels
 * @returns {?{x: number, time: number, snapped: Array, nearest: Object}} the readout, or null
 */
export function invert(meta, cursorX, cursorY) {
  const plot = meta && meta.plot;
  if (!plot || plot.length < 4) { return null; }
  const [left, top, width, height] = plot;
  if (cursorX < left || cursorX > left + width || cursorY < top || cursorY > top + height) {
    return null;
  }
  if (!(meta.x1 > meta.x0) || !(meta.y1 > meta.y0)) { return null; }

  const time = meta.x0 + ((cursorX - left) / width) * (meta.x1 - meta.x0);
  const snapped = [];
  for (let s = 0; s < meta.series.length; s++) {
    const values = meta.series[s];
    if (!values || !values.length) { continue; }
    // Nearest sample in x. Uniformly spaced, so the index is a division rather than a search.
    let idx = Math.round(((cursorX - left) / width) * (values.length - 1));
    if (idx < 0) { idx = 0; }
    if (idx > values.length - 1) { idx = values.length - 1; }
    let value = values[idx];
    // Step outward to a real sample: a gap in the series is null, and the line is not drawn there.
    for (let step = 0; step < values.length && value === null; step++) {
      const back = idx - step - 1;
      const fwd = idx + step + 1;
      if (back >= 0 && values[back] !== null) { idx = back; value = values[back]; break; }
      if (fwd < values.length && values[fwd] !== null) { idx = fwd; value = values[fwd]; break; }
    }
    if (value === null) { continue; }
    snapped.push({
      // The source series index, not the position in this array: a series that is entirely gaps
      // is skipped, so position and index drift apart and colours would follow the wrong series.
      index: s,
      value,
      // Value rises upward, so the largest value sits at the top of the plot.
      y: top + height - ((value - meta.y0) / (meta.y1 - meta.y0)) * height
    });
  }
  if (!snapped.length) { return null; }

  // Snap in y to the nearest line, so the readout tracks a line rather than the raw cursor.
  let nearest = snapped[0];
  for (let i = 1; i < snapped.length; i++) {
    if (Math.abs(snapped[i].y - cursorY) < Math.abs(nearest.y - cursorY)) { nearest = snapped[i]; }
  }
  return {x: cursorX, time, snapped, nearest};
}

/**
 * Formats the time row. Plain 24-hour clock to the second, matching the graph's own x-axis
 * labels; the client may be in any timezone, but the router's axis is in the router's, so
 * guessing would put the readout a day out for anyone not on the same offset.
 *
 * @param {number} epochSeconds - the time at the cursor
 * @returns {string} HH:MM:SS
 */
export function formatTime(epochSeconds) {
  const when = new Date(epochSeconds * 1000);
  const pad = n => String(n).padStart(2, "0");
  return pad(when.getHours()) + ":" + pad(when.getMinutes()) + ":" + pad(when.getSeconds());
}

/**
 * Formats one value. Three decimals is finer than any axis label, so the readout agrees with the
 * graph to the last digit it can show.
 *
 * @param {number} value - the value in axis display units
 * @returns {string} the value
 */
export function formatValue(value) {
  return String(Math.round(value * 1000) / 1000);
}

/**
 * Grows or shrinks the readout's value rows to match the series being shown.
 *
 * Rows are reused rather than rebuilt: the text and the swatch colour are only written when
 * they actually change, so a cursor that stays on one sample costs nothing per frame.
 *
 * @param {Object} parts - the overlay parts
 * @param {number} count - how many series to show
 * @param {Object} meta - the graph metadata, holding the per-series colours
 * @param {Object} hit - the result of {@link invert}
 * @returns {void}
 */
function syncValueRows(parts, count, meta, hit) {
  while (parts.items.length < count) {
    const item = document.createElement("div");
    item.className = "graphTooltipValue";
    const swatch = document.createElement("span");
    swatch.className = "graphTooltipSwatch";
    const label = document.createElement("span");
    label.className = "graphTooltipValueText";
    item.appendChild(swatch);
    item.appendChild(label);
    parts.valuesRow.appendChild(item);
    parts.items.push({el: item, swatch, label, text: null, color: null});
  }
  const colors = meta.colors || [];
  for (let i = 0; i < parts.items.length; i++) {
    const entry = parts.items[i];
    if (i >= count) { entry.el.hidden = true; continue; }
    entry.el.hidden = false;
    const text = formatValue(hit.snapped[i].value);
    if (entry.text !== text) {
      entry.label.textContent = text;
      entry.text = text;
    }
    // The colour comes from the series that produced this value, not from its row, because a
    // skipped all-gap series shifts the rows. Data-driven, so it is set inline; a theme still
    // owns the swatch's shape.
    const color = colors[hit.snapped[i].index] || "";
    if (entry.color !== color) {
      entry.swatch.style.background = color;
      entry.color = color;
    }
  }
}

/**
 * One border width of a container, tolerating a computed style that reports nothing.
 *
 * @param {Element} container - the container
 * @param {string} property - the CSS property to read
 * @returns {number} the width in pixels, or 0
 */
function borderWidth(container, property) {
  let value = 0;
  try {
    value = parseFloat(getComputedStyle(container).getPropertyValue(property));
  } catch (error) {
    value = 0;
  }
  return Number.isFinite(value) ? value : 0;
}

/**
 * Everything placement needs about where an image is, measured once and reused.
 *
 * Absolute children of the container are positioned from its padding box, which starts inside
 * its border, so the border has to come off the container's own rect.
 *
 * @param {HTMLImageElement} img - the graph image
 * @returns {Object} the measurement: image rect, container origin and scale factors
 */
function measure(img) {
  const cached = measurements.get(img);
  if (cached && cached.epoch === rectCacheEpoch) { return cached; }
  const rect = img.getBoundingClientRect();
  const container = containerFor(img);
  const containerRect = container.getBoundingClientRect();
  // An image laid out smaller than it was rendered scales the cursor with it.
  const entry = {
    rect,
    originX: containerRect.left + borderWidth(container, "border-left-width"),
    originY: containerRect.top + borderWidth(container, "border-top-width"),
    containerWidth: containerRect.width,
    scaleX: rect.width / (img.naturalWidth || rect.width),
    scaleY: rect.height / (img.naturalHeight || rect.height),
    epoch: rectCacheEpoch
  };
  measurements.set(img, entry);
  return entry;
}

/**
 * Places the crosshair, the marks and the readout.
 *
 * Everything moves by transform alone: no layout, and nothing writes to the image subtree, so
 * the graph is never repainted.
 *
 * @param {Object} parts - the overlay parts
 * @param {HTMLImageElement} img - the graph being hovered
 * @param {Object} meta - the graph's metadata
 * @param {Object} hit - the result of {@link invert}
 * @returns {void}
 */
export function placeOverlay(parts, img, meta, hit) {
  const m = measure(img);
  const [left, top, width, height] = meta.plot;
  const containerWidth = m.containerWidth;

  // Image pixels to container pixels. The container's padding box is the origin, so the
  // crosshair and the marks land on the graph rather than a few pixels off.
  const anchorX = m.rect.left - m.originX + hit.x * m.scaleX;
  const plotTop = m.rect.top - m.originY + top * m.scaleY;
  const plotHeight = height * m.scaleY;

  parts.crosshair.hidden = false;
  // translate3d keeps the move on the compositor: no layout, no repaint of the graph.
  parts.crosshair.style.height = Math.round(plotHeight) + "px";
  parts.crosshair.style.transform =
    "translate3d(" + Math.round(anchorX) + "px," + Math.round(plotTop) + "px,0)";

  // One mark and one level line per series, in the same order as the values in the readout, so
  // every number reported has both a point and a level pointing at it.
  while (parts.marks.length < hit.snapped.length) {
    const mark = document.createElement("div");
    mark.className = "graphTooltipMark";
    attach(mark, parts.host);
    parts.marks.push(mark);
  }
  while (parts.levels.length < hit.snapped.length) {
    const level = document.createElement("div");
    level.className = "graphTooltipLevel";
    attach(level, parts.host);
    parts.levels.push(level);
  }
  const plotX = m.rect.left - m.originX + left * m.scaleX;
  const plotWidth = width * m.scaleX;
  for (let i = 0; i < parts.marks.length; i++) {
    const mark = parts.marks[i];
    const level = parts.levels[i];
    if (i >= hit.snapped.length) {
      mark.hidden = true;
      level.hidden = true;
      continue;
    }
    const y = m.rect.top - m.originY + hit.snapped[i].y * m.scaleY;
    mark.hidden = false;
    mark.style.transform =
      "translate3d(" + Math.round(anchorX) + "px," + Math.round(y) + "px,0)";
    // The level is bounded by the axis: it spans the plot and stops at both axis lines, so it
    // never runs over the tick labels to the left. Being pinned in x it also stays put while the
    // cursor sweeps sideways, and only the value moves it.
    level.hidden = false;
    level.style.width = Math.round(plotWidth) + "px";
    level.style.transform =
      "translate3d(" + Math.round(plotX) + "px," + Math.round(y) + "px,0)";
  }

  parts.readout.hidden = false;
  syncValueRows(parts, hit.snapped.length, meta, hit);
  const when = formatTime(hit.time);
  if (parts.timeText !== when) {
    parts.timeRow.textContent = when;
    parts.timeText = when;
  }
  // The container clips its overflow, and the readout is wider than the gap to the graph edge,
  // so past the midpoint it goes to the left of the crosshair instead. Deciding from the
  // cursor's half of the graph needs no measurement of the text, and the offset itself is the
  // theme's, via .graphTooltip and .graphTooltip.flip-x.
  const flip = anchorX > containerWidth / 2;
  if (flip !== parts.flipped) {
    parts.readout.className = flip ? "graphTooltip flip-x" : "graphTooltip";
    parts.flipped = flip;
  }
  parts.readout.style.transform =
    "translate3d(" + Math.round(anchorX) + "px," + Math.round(plotTop) + "px,0)";
}

/**
 * Attaches to the console's shared fetch worker, on first use.
 *
 * The worker is what every other console module uses for network access. It throttles the
 * periodic refreshes that would otherwise flood the router, and it aborts on its own timeout.
 * Metadata requests ask for force, which skips both the debounce and the rate limit: this is
 * user-initiated, and a readout that arrives late is worth nothing.
 *
 * The port is opened lazily so a page nobody hovers a graph on pays nothing for it.
 *
 * @returns {MessagePort|false} the port, or false when the worker cannot be used
 */
function workerChannel() {
  if (workerPort !== null) { return workerPort; }
  if (typeof SharedWorker !== "function") { workerPort = false; return workerPort; }
  try {
    const port = new SharedWorker("/js/fetchWorker.js").port;
    port.onmessage = event => {onWorkerReply(event.data);};
    port.start();
    workerPort = port;
  } catch (error) {
    // No SharedWorker, or blocked: a plain fetch still beats showing nothing.
    workerPort = false;
  }
  return workerPort;
}

/**
 * Routes a shared-worker reply to whoever asked for that URL.
 *
 * @param {Object} payload - the worker's message
 * @returns {void}
 */
function onWorkerReply(payload) {
  const waiter = payload && workerWaiters.get(payload.url);
  if (!waiter) { return; }
  workerWaiters.delete(payload.url);
  clearTimeout(waiter.timer);
  waiter.resolve(payload);
}

/**
 * Fetches metadata without the shared worker, for a browser that lacks SharedWorker.
 *
 * Still uses AbortController, so it inherits the same guarantee of not hanging: a stalled
 * viewstat.jsp cannot leave the callback pending forever.
 *
 * @param {string} url - the metadata URL
 * @returns {Promise<Object|null>} the worker's payload shape, or null
 */
async function directFetch(url) {
  if (typeof fetch !== "function") { return null; }
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const timer = controller
    ? setTimeout(() => {controller.abort();}, META_TIMEOUT_MS)
    : null;
  try {
    const response = await fetch(url, {
      credentials: "same-origin",
      signal: controller ? controller.signal : undefined
    });
    if (!response.ok) { return {url, isDown: true, status: response.status}; }
    return {url, responseBlob: await response.blob(), isDown: false, status: response.status};
  } catch (error) {
    return {url, isDown: true, status: 0};
  } finally {
    if (timer) { clearTimeout(timer); }
  }
}

/**
 * Asks for one graph's metadata, through the shared worker where possible.
 *
 * A reply that never arrives resolves to null rather than hanging, so the overlay simply stays
 * hidden instead of being stranded half-placed.
 *
 * @param {string} url - the metadata URL
 * @returns {Promise<Object|null>} the worker payload, or null
 */
function requestMeta(url) {
  const port = workerChannel();
  if (!port) { return directFetch(url); }
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      workerWaiters.delete(url);
      resolve(null);
    }, META_TIMEOUT_MS);
    workerWaiters.set(url, {resolve, timer});
    port.postMessage({url, force: true});
  });
}

/**
 * Reads the worker payload as text, then as metadata.
 *
 * The worker hands back text for HTML and a Blob for everything else, and metadata is JSON, so
 * this almost always goes through Blob.text().
 *
 * @param {Object} payload - the worker payload
 * @returns {Promise<Object|null>} the parsed metadata, or null if unusable
 */
async function parseMeta(payload) {
  if (!payload || payload.isDown) { return null; }
  let text = payload.responseText;
  if (text == null && payload.responseBlob && typeof payload.responseBlob.text === "function") {
    text = await payload.responseBlob.text();
  }
  if (typeof text !== "string") { return null; }
  let json;
  try { json = JSON.parse(text); } catch (error) { return null; }
  if (!json || !json.plot || !Array.isArray(json.series) || !json.series.length) { return null; }
  return json;
}

/**
 * Fetches the metadata for a graph once, then caches it for the page's lifetime.
 *
 * A graph's axis bounds move with its data, so a cache hit is only the answer for the window
 * that was fetched. Acceptable for a readout, and the alternative is a request per mouse move.
 * Concurrent hovers on one graph join the same request rather than issuing a second.
 *
 * @param {HTMLImageElement} img - the graph image
 * @param {Function} done - called with the parsed metadata, or null on failure
 * @returns {void}
 */
function loadMeta(img, done) {
  const url = metaUrl(img);
  const cached = metaCache.get(url);
  if (cached !== undefined) { done(cached); return; }
  const pendingRequest = inFlight.get(url);
  if (pendingRequest) { pendingRequest.then(done); return; }

  const request = requestMeta(url).then(parseMeta).then(json => {
    metaCache.set(url, json);
    inFlight.delete(url);
    return json;
  });
  inFlight.set(url, request);
  request.then(done);
}

/**
 * Handles one pointer position: invert, snap, place.
 *
 * @param {Object} position - the buffered position
 * @returns {void}
 */
function handleMove(position) {
  const img = position.target;
  loadMeta(img, meta => {
    if (!meta) { hideOverlay(); return; }
    const hit = invert(meta, position.offsetX, position.offsetY);
    if (!hit) { hideOverlay(); return; }
    const parts = ensureOverlay(containerFor(img));
    if (activeOverlay && activeOverlay !== parts) { setVisible(activeOverlay, false); }
    activeOverlay = parts;
    placeOverlay(parts, img, meta, hit);
  });
}

/**
 * Flushes the buffered position, once per frame.
 *
 * pointermove fires at the mouse's polling rate, which can exceed the display refresh by an
 * order of magnitude. Buffering caps the work at one pass per frame regardless.
 *
 * @returns {void}
 */
function flush() {
  frameQueued = false;
  const position = pending;
  pending = null;
  if (position) { handleMove(position); }
}

/**
 * Measures a graph's rect as the pointer arrives, so the first move does not pay for it.
 *
 * @param {PointerEvent} event - the pointerover
 * @returns {void}
 */
function onPointerOver(event) {
  const img = event.target;
  if (!img || img.tagName !== "IMG" || !img.classList.contains("statimage")) { return; }
  measure(img);
}

/**
 * The single delegated listener. One listener for the page, not one per graph: a page can carry
 * two dozen images and every one of them would otherwise run on every mouse move.
 *
 * @param {PointerEvent} event - the pointermove
 * @returns {void}
 */
function onPointerMove(event) {
  const img = event.target;
  if (!img || img.tagName !== "IMG" || !img.classList.contains("statimage")) { return; }
  // offsetX/offsetY are in the image's own box, so no layout read is needed for the input.
  pending = {target: img, offsetX: event.offsetX, offsetY: event.offsetY};
  if (!frameQueued) {
    frameQueued = true;
    requestAnimationFrame(flush);
  }
}

/**
 * Drops every cached rect and hides the overlay.
 *
 * A WeakMap cannot be cleared, so the epoch is bumped instead and any entry stamped with an
 * older one is treated as a miss. The rect is only wrong while the page is scrolled or resized,
 * and both of those hide the overlay.
 */
function invalidate() {
  rectCacheEpoch++;
  hideOverlay();
}

/**
 * Starts the readout. Idempotent.
 *
 * @returns {void}
 */
export function initGraphTooltip() {
  if (document.documentElement.classList.contains("graphTooltipListener")) { return; }
  document.documentElement.classList.add("graphTooltipListener");
  document.addEventListener("pointermove", onPointerMove, {passive: true});
  document.addEventListener("pointerover", onPointerOver, {passive: true});
  document.addEventListener("pointerleave", hideOverlay, true);
  // A WeakMap cannot be cleared, so a scroll bumps the epoch instead and every cached rect
  // older than it is ignored.
  document.addEventListener("scroll", invalidate, {passive: true, capture: true});
  window.addEventListener("resize", invalidate, {passive: true});
}

document.addEventListener("DOMContentLoaded", initGraphTooltip);

/**
 * The module's fetch seam, exported for tests and for anything that wants to warm the cache.
 *
 * @returns {Promise<Object|null>} the metadata the overlay would use, or null
 */
export {GRAPH_SELECTOR, metaUrl, loadMeta, parseMeta};
