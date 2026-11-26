/**
 * @module graphPanZoom
 * @description Drags and rolls the console performance graphs through time. A drag
 * slides the displayed window by a fraction of its own span, mapped through the
 * graph's own pixel-to-time scale; the mouse wheel shortens or lengthens the span,
 * anchored on the time under the cursor. On /graphs the gesture is previewed on the
 * graph it started on and committed to every graph when it ends, so one page shows
 * one timeframe; on /graph it applies to the single graph only.
 *
 * <p>The window is carried as wall-clock - a span and an offset from now, in ms -
 * not as the URL's period counts. Each graph's period differs (a 1-minute rate and a
 * 5-minute rate are the same wall-clock window expressed in different counts), so a
 * shared period count would give every graph a different timeframe. The counts are
 * derived per graph from its own period when the src is written.
 *
 * <p>Nothing here redraws on the server beyond the render each image already costs:
 * a single graph re-renders in 1-3ms, so the preview of the graph under the pointer
 * is a real render, not a slide of the old one. The x-axis labels are baked into the
 * SVG, so sliding the image would show a smooth pan over wrong numbers.
 *
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/**
 * How long one graph may take to come back for the page to keep re-pointing every
 * graph while a gesture is still moving. Measured here at 1-20ms a graph, so 26 of
 * them land in 25-78ms; a box that cannot answer a single graph inside this budget
 * gets the preview-one-then-commit behaviour instead, which is what the module does
 * until it has a measurement.
 */
const FAN_OUT_BUDGET_MS = 400;

/** Where the measured render cost is kept, so the choice survives a page reload. */
const FAN_OUT_KEY = "i2p.graphRenderMs";

/** ms of gesture silence after which the settled span is written to the config. */
const CONFIG_SETTLE_MS = 1500;

/** ms a nonce read from the page stays worth posting before it is re-read. */
const NONCE_MAX_MS = 45000;

/** Graph images a gesture may start on. */
const GRAPH_SELECTOR = "img.statimage";

/**
 * Shortest span a gesture may set, in ms. Below this the samples are wider than the
 * pixels that draw them and the graph is a flat line.
 */
export const MIN_SPAN_MS = 30 * 60 * 1000;

/**
 * Longest span and furthest offset a gesture may set, in periods of the graph's own
 * rate. Mirrors GraphListener.MAX_ROWS - the most rows a persistent archive keeps,
 * three months at one-minute resolution - so a gesture stops at the archive edge
 * instead of panning into an empty window.
 */
export const MAX_PERIODS = 131040;

/** Span multiplier per wheel notch: four notches is about double or half. */
export const ZOOM_STEP = 1.25;

/** Wheel notches that count as one step, so a fast flick moves several. */
const WHEEL_PIXELS_PER_NOTCH = 100;

/** Drag distance before a press becomes a pan, so a click stays a click. */
const DRAG_SLOP_PX = 4;

/** ms of wheel silence after which the pending window is committed. */
const WHEEL_COMMIT_MS = 150;

/**
 * Converts wheel movement into zoom notches, positive meaning zoom out.
 * @function wheelNotches
 * @param {number} deltaY the wheel delta, positive scrolling down
 * @param {number} [deltaMode=0] 0 pixels, 1 lines, 2 pages
 * @returns {number} notches, positive to lengthen the span, 0 for no movement
 */
export function wheelNotches(deltaY, deltaMode = 0) {
  if (!deltaY) {return 0;}
  let notches;
  if (deltaMode === 1) {notches = deltaY;}
  else if (deltaMode === 2) {notches = deltaY * 10;}
  else {notches = deltaY / WHEEL_PIXELS_PER_NOTCH;}
  // A fraction of a notch still moves, but never by less than one notch's worth, so
  // a trackpad's small deltas do not turn into a no-op or a jump.
  if (notches === 0) {return 0;}
  return Math.abs(notches) < 1 ? (notches > 0 ? 1 : -1) : notches;
}

/**
 * Multiplies a span by the zoom step for the notches rolled, clamped to the archive.
 * @function zoomSpan
 * @param {number} spanMs the current span in ms
 * @param {number} notches from wheelNotches, positive to lengthen the span
 * @param {number} maxMs the longest span this graph can show
 * @returns {number} the new span in ms, never below MIN_SPAN_MS nor above maxMs
 */
export function zoomSpan(spanMs, notches, maxMs) {
  if (!notches) {return spanMs;}
  let next = spanMs * Math.pow(ZOOM_STEP, notches);
  if (next < MIN_SPAN_MS) {next = MIN_SPAN_MS;}
  if (next > maxMs) {next = maxMs;}
  return next;
}

/**
 * Offsets a window by a drag, in the graph's own time-per-pixel scale.
 * Dragging the content right (positive dx) looks further back, so the offset grows.
 * @function panOffsetMs
 * @param {number} offsetMs the current offset from now, in ms
 * @param {number} spanMs the current span in ms
 * @param {number} plotWidth the plot area's width in displayed pixels
 * @param {number} dragPx the pointer's horizontal travel in pixels
 * @returns {number} the new offset in ms, never negative
 */
export function panOffsetMs(offsetMs, spanMs, plotWidth, dragPx) {
  if (!(plotWidth > 0) || !(spanMs > 0) || !dragPx) {return Math.max(0, offsetMs);}
  return Math.max(0, offsetMs + dragPx * (spanMs / plotWidth));
}

/**
 * Rescales a window around the time under the cursor, so the point being rolled over
 * stays put instead of sliding away.
 * @function zoomWindow
 * @param {number} spanMs the current span in ms
 * @param {number} offsetMs the current offset from now, in ms
 * @param {number} newSpanMs the span to set
 * @param {number} cursorFraction 0 at the plot's left edge, 1 at its right edge
 * @returns {{spanMs: number, offsetMs: number}} the anchored window
 */
export function zoomWindow(spanMs, offsetMs, newSpanMs, cursorFraction) {
  const frac = Math.min(1, Math.max(0, cursorFraction || 0));
  const offset = offsetMs + spanMs - frac * spanMs - (1 - frac) * newSpanMs;
  return {spanMs: newSpanMs, offsetMs: Math.max(0, Math.round(offset))};
}

/**
 * Turns a wall-clock window into the two query parameters one graph understands.
 * @function windowParams
 * @param {number} spanMs the span to show, in ms
 * @param {number} offsetMs how far back from now, in ms
 * @param {number} periodMs the graph's rate period in ms
 * @returns {{periodCount: number, end: number}} counts of periods, end never negative
 */
export function windowParams(spanMs, offsetMs, periodMs) {
  if (!(periodMs > 0)) {return {periodCount: 0, end: 0};}
  const periodCount = Math.min(MAX_PERIODS, Math.max(1, Math.round(spanMs / periodMs)));
  const end = Math.min(MAX_PERIODS, Math.max(0, Math.round(offsetMs / periodMs)));
  return {periodCount: periodCount, end: end};
}

/**
 * The plot area of a rendered graph, in displayed (not rendered) pixels.
 *
 * <p>A missing or malformed plot yields a zero-width area rather than NaN: every
 * gesture scales its movement by that width, and a NaN width turns a drag into a
 * shift of nothing at all while still looking like the gesture ran.
 *
 * @function plotArea
 * @param {DOMRect} rect the image's bounding client rect
 * @param {number} naturalWidth the image's rendered width in px
 * @param {Array<number>} plot the metadata plot as [left, top, width, height]
 * @returns {{left: number, top: number, width: number, height: number}} in client px
 */
export function plotArea(rect, naturalWidth, plot) {
  if (!Array.isArray(plot) || plot.length !== 4) {return {left: 0, top: 0, width: 0, height: 0};}
  const scale = naturalWidth > 0 ? rect.width / naturalWidth : 1;
  return {left: rect.left + plot[0] * scale, top: rect.top + plot[1] * scale,
          width: plot[2] * scale, height: plot[3] * scale};
}

/**
 * Where the cursor sits across the plot, 0 at the left edge and 1 at the right.
 * @function cursorFraction
 * @param {number} cursorX the pointer's client x
 * @param {{left: number, width: number}} area the plot area
 * @returns {number} the fraction, clamped to 0-1
 */
export function cursorFraction(cursorX, area) {
  if (!(area.width > 0)) {return 1;}
  return Math.min(1, Math.max(0, (cursorX - area.left) / area.width));
}

/** The graph's own rate period, from its src; the combined and grouped graphs use a minute. */
function periodMsOf(params) {
  const period = parseInt(params.get("period") || "", 10);
  return period > 0 ? period : 60000;
}

/** The window a graph's src currently asks for, in wall-clock ms. */
function windowOf(params, periodMs) {
  const periodCount = parseInt(params.get("periodCount") || "", 10);
  const end = parseInt(params.get("end") || "0", 10);
  const count = periodCount > 0 ? periodCount : 480;
  return {spanMs: count * periodMs, offsetMs: (isNaN(end) ? 0 : end) * periodMs};
}

/**
 * Decides whether a gesture may re-point every graph on the page as it moves, or only
 * the graph it started on.
 *
 * <p>A fan-out is measured, not assumed: it is the wall time from re-pointing every
 * graph to the last image coming back, and it is dominated by the router's renders, so
 * a fast box does it in tens of milliseconds and a loaded one with more graphs takes
 * seconds. The measurement carries across page loads because the box does not change
 * between gestures.
 *
 * @function fanOutLive
 * @param {number} lastMs how long the most recent fan-out took, or -1 if none has run
 * @param {number} budgetMs what the page will absorb per step
 * @returns {boolean} true to re-point every graph during the gesture
 */
export function fanOutLive(lastMs, budgetMs) {
  if (!(lastMs >= 0)) {return false;}
  return lastMs <= budgetMs;
}

/**
 * The span a gesture settled on, as the graph configuration page counts it.
 *
 * <p>The configuration holds one number for the whole page and it is a number of
 * minutes, not of samples: each graph's own period count is that span divided by its
 * rate's period, which is why a wall-clock span, not a period count, is what belongs
 * in it.
 *
 * @function configMinutes
 * @param {number} spanMs the span the page now shows
 * @param {number} [minMinutes=5] the shortest span the form accepts
 * @param {number} [maxMinutes=131040] the longest, three months of minutes
 * @returns {number} whole minutes, clamped to the form's range
 */
export function configMinutes(spanMs, minMinutes = 5, maxMinutes = 131040) {
  if (!(spanMs > 0)) {return minMinutes;}
  const minutes = Math.round(spanMs / 60000);
  return Math.min(maxMinutes, Math.max(minMinutes, minutes));
}

/**
 * A form's fields as one url-encoded body, with a field replaced.
 *
 * <p>One value per name, the checked boxes only, exactly as a real submit sends them.
 * The console's Jetty does not parse a multipart body - a multipart POST arrives with
 * no parameters at all, saves with a 200, and changes nothing - so the body is built
 * here rather than handed to fetch as form data.
 *
 * @function formBody
 * @param {Array<Array<string>>} fields name and value pairs, as a form holds them, a
 *                                      repeated name for each unchecked duplicate box
 * @param {Object} [overrides] values to replace, by name
 * @returns {string} the url-encoded body
 */
export function formBody(fields, overrides) {
  const one = {};
  (fields || []).forEach((pair) => {one[pair[0]] = pair[1];});
  Object.keys(overrides || {}).forEach((key) => {one[key] = String(overrides[key]);});
  return Object.keys(one).map((key) => encodeURIComponent(key) + "=" +
          encodeURIComponent(one[key])).join("&");
}

/** Metadata cache, keyed by the image URL without its cache-busting time. */
const metaCache = new Map();

/**
 * The plot geometry a page may carry on the image itself, as data-plot="left,top,
 * width,height". A graph pulled from another router's console cannot be asked for
 * its metadata - the format=meta fetch is cross-origin and the reply carries no
 * CORS header - but the page that embedded it knows the geometry it asked for, and
 * a plot rect in the tag costs no request at all.
 * @function plotOf
 * @param {HTMLImageElement} img the graph image
 * @returns {Array<number>|null} the four numbers, or null if none are carried
 */
export function plotOf(img) {
  const carried = img.getAttribute("data-plot");
  if (!carried) {return null;}
  const parts = carried.split(",").map((part) => parseInt(part, 10));
  if (parts.length !== 4 || !(parts[2] > 0) || !(parts[3] > 0)) {return null;}
  return parts;
}

/**
 * Reads a graph's plot geometry and time span.
 * @async
 * @function metaFor
 * @param {HTMLImageElement} img the graph image
 * @returns {Promise<Object|null>} the metadata, or null if it cannot be read
 */
async function metaFor(img) {
  const carried = plotOf(img);
  if (carried) {return {plot: carried};}
  const url = new URL(img.src, location.href);
  url.searchParams.delete("time");
  const key = url.toString();
  const cached = metaCache.get(key);
  if (cached) {return cached;}
  const metaUrl = new URL(img.src, location.href);
  metaUrl.searchParams.set("format", "meta");
  metaUrl.searchParams.delete("time");
  try {
    const response = await fetch(metaUrl, {credentials: "same-origin"});
    if (!response.ok) {return null;}
    const json = await response.json();
    if (!json || !Array.isArray(json.plot) || !(json.x1 > json.x0)) {return null;}
    metaCache.set(key, json);
    return json;
  } catch (error) {
    return null;
  }
}

/**
 * The metadata reader, exported so a test can pin that it hands back a document whose
 * plot is the array plotArea wants - the shape the gestures are scaled by.
 *
 * @returns {Promise<Object|null>} the metadata, or null if it cannot be read
 */
export {metaFor};

/** The window the running gesture is carrying, in wall-clock ms. */
let pending = null;
/** The graph the running gesture started on. */
let anchor = null;
/** The src each touched graph had before the gesture, for the double-click reset. */
const originals = new WeakMap();
/** Timer for the wheel commit. */
let commitTimer = null;
/** Frame handle for the drag preview. */
let frame = null;
/** Whether the running gesture has already re-pointed every graph on the page. */
let fanned = false;
/** Timer that lets the readout back in after a gesture ends. */
let holdTimer = null;
/** Timer that ends the claim a roll of the wheel holds over the page. */
let claimTimer = null;
/** ms the last graph render took, -1 until one has been timed. */
let fanOutMs = rememberedFanOut();
/** Timer for the deferred configuration write. */
let configTimer = null;
/** The span in minutes last written to the configuration, so a settled page writes once. */
let savedMinutes = -1;
/** When the configuration form's nonce was last read from the server. */
let nonceAt = 0;

/**
 * Writes a window into one graph's src, re-anchoring its cache-busting time.
 * @function applyWindow
 * @param {HTMLImageElement} img the graph image
 * @param {{spanMs: number, offsetMs: number}} win the window to show
 * @returns {void}
 */
function applyWindow(img, win) {
  const params = new URL(img.src, location.href).searchParams;
  const periodMs = periodMsOf(params);
  const maxMs = MAX_PERIODS * periodMs;
  const spanMs = Math.min(maxMs, Math.max(MIN_SPAN_MS, win.spanMs));
  const offsetMs = Math.min(maxMs, Math.max(0, win.offsetMs));
  const counts = windowParams(spanMs, offsetMs, periodMs);
  if (!counts.periodCount) {return;}
  if (!originals.has(img)) {originals.set(img, img.src);}
  const url = new URL(img.src, location.href);
  url.searchParams.set("periodCount", String(counts.periodCount));
  if (counts.end > 0) {url.searchParams.set("end", String(counts.end));}
  else {url.searchParams.delete("end");}
  url.searchParams.set("time", String(Date.now()));
  img.src = url.toString();
}

/** Every graph on the page, in document order. */
function graphs() {
  return Array.from(document.querySelectorAll(GRAPH_SELECTOR));
}

/**
 * Hands the window the gesture settled on to every graph on the page. Used when the
 * gesture was previewing one graph only; a live gesture has already re-pointed them all.
 * @function commit
 * @param {{spanMs: number, offsetMs: number}} win the window to show
 * @returns {void}
 */
function commit(win) {
  graphs().forEach((img) => applyWindow(img, win));
}

/**
 * Re-points the graph the gesture is working on and times the round trip, which is the
 * number that decides whether the next gesture re-points the whole page as it moves.
 *
 * <p>One graph is timed rather than the page: the cost of a fan-out is the same server
 * render repeated per graph, the browser serialises those requests anyway, and waiting
 * on every image to come back is how a measurement ends up counting an image that was
 * never re-requested at all.
 *
 * @function timeAnchor
 * @param {HTMLImageElement} img the graph the gesture started on
 * @param {{spanMs: number, offsetMs: number}} win the window to show
 * @returns {void}
 */
function timeAnchor(img, win) {
  const started = Date.now();
  const done = () => noteFanOut(Date.now() - started);
  img.addEventListener("load", done, {once: true});
  img.addEventListener("error", done, {once: true});
  applyWindow(img, win);
}

/**
 * Records a render measurement and re-decides how the next gesture behaves.
 * @function noteFanOut
 * @param {number} ms wall time from re-pointing a graph to that image arriving
 * @returns {void}
 */
function noteFanOut(ms) {
  fanOutMs = ms;
  try {
    localStorage.setItem(FAN_OUT_KEY, String(ms));
  } catch (e) {
    // A private-mode or quota-blocked localStorage only costs the carry-over.
  }
}

/**
 * Reads the fan-out cost measured on this box before, if any.
 * @function rememberedFanOut
 * @returns {number} ms, or -1 when nothing has been measured
 */
function rememberedFanOut() {
  try {
    const stored = parseInt(localStorage.getItem(FAN_OUT_KEY), 10);
    return isNaN(stored) ? -1 : stored;
  } catch (e) {
    return -1;
  }
}

/**
 * The form's CSRF nonce, refreshed when it has gone stale.
 *
 * <p>The console mints a nonce per page and ages it out, so a page left open through a
 * quiet afternoon posts a nonce the session no longer accepts and the save is refused.
 * A fresh one costs one GET of the page that carries the form.
 *
 * @function freshNonce
 * @param {HTMLFormElement} form the configuration form on the page
 * @returns {Promise<string>} a nonce to post, possibly the one already in the form
 */
async function freshNonce(form) {
  const field = form.querySelector("[name=nonce]");
  const current = field ? field.value : "";
  if (current && Date.now() - nonceAt < NONCE_MAX_MS) {return current;}
  try {
    const response = await fetch(form.getAttribute("action") || "/graphs", {credentials: "same-origin"});
    if (!response.ok) {return current;}
    const html = await response.text();
    const found = /name=nonce value=([^ >]*)/.exec(html);
    if (found && found[1]) {
      if (field) {field.value = found[1];}
      nonceAt = Date.now();
      return found[1];
    }
  } catch (e) {
    // A page that cannot be re-read posts the nonce it already has.
  }
  return current;
}

/**
 * Writes the span the gesture settled on into the graph configuration, so the page
 * opens on the timeframe you left it on.
 *
 * <p>The configuration is the form the page already carries, posted as it stands with
 * only its period field changed: the form holds every other setting and the nonce the
 * save needs, and re-posting it whole cannot lose a setting the page did not show. The
 * reply is discarded - the point is the write, not a new page. The offset is left out
 * on purpose: a window that ends three hours ago is a look back, not a preference.
 *
 * @function saveConfig
 * @param {{spanMs: number, offsetMs: number}} win the window the page settled on
 * @returns {Promise<void>} when the configuration has been written
 */
async function saveConfig(win) {
  const form = document.getElementById("gform");
  if (!form) {return;}
  const minutes = configMinutes(win.spanMs);
  if (savedMinutes === minutes) {return;}
  savedMinutes = minutes;
  const fields = [];
  new FormData(form).forEach((value, key) => {fields.push([key, value]);});
  const nonce = await freshNonce(form);
  if (nonce) {fields.push(["nonce", nonce]);}
  try {
    const response = await fetch(form.getAttribute("action") || "/graphs", {
      method: "POST",
      body: formBody(fields, {periodCount: minutes}),
      credentials: "same-origin",
      headers: {"content-type": "application/x-www-form-urlencoded"},
    });
    // The reply is the whole re-rendered page, which is of no use here: the graphs on
    // screen carry their own window in their src and are untouched by the write. Stop
    // reading it rather than pulling 58KB across the link to throw it away.
    if (response.body) {response.body.cancel();}
    if (!response.ok) {savedMinutes = -1;}
  } catch (e) {
    savedMinutes = -1;
  }
}

/**
 * Defers the configuration write until the gesture stops moving, so a drag that
 * crosses ten spans costs one save rather than ten.
 * @function scheduleConfigSave
 * @param {{spanMs: number, offsetMs: number}} win the window to write
 * @returns {void}
 */
function scheduleConfigSave(win) {
  if (configTimer) {clearTimeout(configTimer);}
  configTimer = setTimeout(() => {
    configTimer = null;
    saveConfig(win);
  }, CONFIG_SETTLE_MS);
}

/** Schedules a preview on the next frame, at most one per frame. */
function preview() {
  if (frame !== null || !pending || !anchor) {return;}
  const win = pending;
  const img = anchor;
  frame = requestAnimationFrame(() => {
    frame = null;
    // Once the box has proved it can redraw a graph inside the budget, the gesture
    // moves every graph with the pointer instead of only the one under it.
    if (fanOutLive(fanOutMs, FAN_OUT_BUDGET_MS)) {
      graphs().forEach((other) => applyWindow(other, win));
      fanned = true;
    } else {
      timeAnchor(img, win);
      fanned = false;
    }
    // The write is scheduled here rather than inside commit: a gesture that ends
    // between two frames has nothing left to re-point by then, but the span it
    // settled on is still worth keeping.
    scheduleConfigSave(win);
  });
}

/**
 * Claims the page for a gesture: the readout's overlay goes out of the way and the
 * pointer's own cursor says the graph is being held.
 * @function beginGesture
 * @returns {void}
 */
function beginGesture() {
  if (holdTimer) {clearTimeout(holdTimer); holdTimer = null;}
  document.documentElement.classList.add(GESTURE_CLASS);
}

/**
 * Releases the page, after a beat, so a roll that pauses between notches does not let
 * the crosshair flicker back in the middle of the gesture.
 * @function endGesture
 * @returns {void}
 */
function endGesture() {
  if (holdTimer) {clearTimeout(holdTimer);}
  holdTimer = setTimeout(() => {
    holdTimer = null;
    document.documentElement.classList.remove(GESTURE_CLASS);
  }, GESTURE_HOLD_MS);
}

/**
 * Starts a pan on a graph image.
 * @function onPointerDown
 * @param {PointerEvent} event the press
 * @returns {Promise<void>} when the window is ready to drag
 */
async function onPointerDown(event) {
  if (event.button !== 0) {return;}
  const img = event.target.closest ? event.target.closest(GRAPH_SELECTOR) : null;
  if (!img) {return;}
  beginGesture();
  const meta = await metaFor(img);
  if (!meta) {endGesture(); return;}
  const params = new URL(img.src, location.href).searchParams;
  const periodMs = periodMsOf(params);
  const area = plotArea(img.getBoundingClientRect(), img.naturalWidth, meta.plot);
  pending = windowOf(params, periodMs);
  anchor = img;
  const startOffset = pending.offsetMs;
  const startX = event.clientX;
  const spanMs = pending.spanMs;
  /**
   * Follows the pointer until it is released.
   * @function onMove
   * @param {PointerEvent} move the pointer movement
   * @returns {void}
   */
  let lastX = startX;
  let offset = startOffset;
  const onMove = (move) => {
    // Travel is taken a step at a time rather than from where the press began, so the
    // gesture outlives the graph's own width: the pointer can leave the image and keep
    // winding the timeframe, at the same time-per-pixel rate the graph sets. A single
    // span-wide measurement from the press would run out of page at the far edge.
    offset = panOffsetMs(offset, spanMs, area.width, move.clientX - lastX);
    lastX = move.clientX;
    if (Math.abs(lastX - startX) < DRAG_SLOP_PX) {return;}
    pending = {spanMs: spanMs, offsetMs: offset};
    preview();
  };
  /**
   * Ends the pan and hands the window to the rest of the page.
   * @function onUp
   * @returns {void}
   */
  const onUp = () => {
    document.removeEventListener("pointermove", onMove);
    document.removeEventListener("pointerup", onUp);
    document.removeEventListener("pointercancel", onUp);
    if (pending && anchor) {
      // A live drag has already re-pointed every graph on the last frame; only a
      // preview drag still owes the rest of the page its window.
      if (!fanned) {commit(pending);}
      scheduleConfigSave(pending);
    }
    fanned = false;
    pending = null;
    anchor = null;
    endGesture();
  };
  document.addEventListener("pointermove", onMove);
  document.addEventListener("pointerup", onUp);
  document.addEventListener("pointercancel", onUp);
}

/**
 * Rolls the mouse wheel over a graph to shorten or lengthen its span.
 * @function onWheel
 * @param {WheelEvent} event the wheel movement
 * @returns {Promise<void>} when the geometry needed to anchor the zoom is read
 */
async function onWheel(event) {
  const img = event.target.closest ? event.target.closest(GRAPH_SELECTOR) : null;
  if (!img) {return;}
  // The clamp is decided here, synchronously, before anything is awaited: the browser
  // commits a wheel scroll as the listeners run, so a preventDefault that arrives after
  // the metadata has come back is one the page never hears about. The span the image is
  // already showing and its own rate period are both in its src, which is enough to know
  // whether this notch can move the window at all.
  const params = new URL(img.src, location.href).searchParams;
  const periodMs = periodMsOf(params);
  const win = windowOf(params, periodMs);
  const notches = wheelNotches(event.deltaY, event.deltaMode);
  const nextSpan = zoomSpan(win.spanMs, notches, MAX_PERIODS * periodMs);
  // At either end of the zoom the roll has nowhere to go, and the wheel goes back to
  // being a scroll: that is how the page is reachable from a graph that fills it.
  const rolls = notches !== 0 && nextSpan !== win.spanMs;
  if (rolls) {event.preventDefault();}
  // Claimed before the await for the same reason the scroll is cancelled there: the
  // readout has to be out of the way before the first frame of the gesture paints.
  if (rolls) {beginGesture();}
  const meta = await metaFor(img);
  if (!meta || !rolls) {return;}
  if (claimTimer) {clearTimeout(claimTimer);}
  claimTimer = setTimeout(() => {claimTimer = null; endGesture();}, WHEEL_CLAIM_MS);
  const area = plotArea(img.getBoundingClientRect(), img.naturalWidth, meta.plot);
  pending = zoomWindow(win.spanMs, win.offsetMs, nextSpan, cursorFraction(event.clientX, area));
  anchor = img;
  preview();
  if (commitTimer) {clearTimeout(commitTimer);}
  commitTimer = setTimeout(() => {
    commitTimer = null;
    // In live mode every roll has already been applied to every graph by the frame it
    // was rolled on, and committing again would re-render the page a second time for a
    // window it is already showing. In preview mode the rolls only touched the one
    // graph under the pointer, so the settle is what hands the window to the rest.
    if (pending) {
      if (!fanned) {commit(pending);}
      scheduleConfigSave(pending);
    }
    fanned = false;
    pending = null;
    anchor = null;
  }, WHEEL_COMMIT_MS);
}

/**
 * Puts a graph back on the live window it was first drawn with.
 * @function onDoubleClick
 * @param {MouseEvent} event the double click
 * @returns {void}
 */
function onDoubleClick(event) {
  const img = event.target.closest ? event.target.closest(GRAPH_SELECTOR) : null;
  if (!img) {return;}
  const original = originals.get(img);
  if (!original) {return;}
  graphs().forEach((other) => {
    const src = originals.get(other);
    if (src) {other.src = src;}
  });
  originals.delete(img);
}

/**
 * Keeps the browser's own image drag from taking the press over.
 *
 * An <img> is draggable by default, and the native drag begins the moment the press
 * moves, before the metadata the gesture needs has come back, so the pointer is
 * captured by the drag and the pan never sees a move. The graph is not something to
 * drop anywhere, so the drag is cancelled outright.
 *
 * @function onDragStart
 * @param {DragEvent} event the native drag about to start
 * @returns {void}
 */
function onDragStart(event) {
  const img = event.target.closest ? event.target.closest(GRAPH_SELECTOR) : null;
  if (!img) {return;}
  event.preventDefault();
}

/** ms a roll over a graph stays claimed after the last notch, so a slow roll is one gesture. */
const WHEEL_CLAIM_MS = 400;

/**
 * The class the page carries while a gesture is running and for a moment after.
 *
 * <p>The readout's crosshair, marks and value box are children of the graph's container,
 * lying on top of the image. They are already pointer-events:none, but they are still
 * painted, and a crosshair that keeps tracking a pointer which is now dragging the graph
 * two pixels per frame is a second cursor competing with the first.
 */
const GESTURE_CLASS = "graphPanZoomGesture";

/** ms after the gesture ends before the readout is let back in. */
const GESTURE_HOLD_MS = 350;

/**
 * The rules this module needs from the page.
 *
 * <p>A drag on a touch or pen surface is a scroll gesture by default, which the browser
 * then claims for itself and the pan never sees a move; taking that gesture back is the
 * one thing the graph must be told before it is touched. The readout is hidden rather
 * than unmade: opacity and pointer-events cost no layout and no re-creating of the
 * overlay, where display:none would cost both and would re-fire pointerover the moment
 * the gesture let go.
 */
const STYLE = "\n" +
  "img.statimage{touch-action:none}\n" +
  // !important because the readout module takes the crosshair as an inline style on the
  // image, and an inline style outranks any selector a stylesheet can write.
  "." + GESTURE_CLASS + " img.statimage{cursor:grabbing !important}\n" +
  "." + GESTURE_CLASS + " .graphTooltip," +
  "." + GESTURE_CLASS + " .graphTooltipCrosshair," +
  "." + GESTURE_CLASS + " .graphTooltipMark," +
  "." + GESTURE_CLASS + " .graphTooltipLevel{opacity:0;pointer-events:none}\n";

/**
 * Starts the pan and zoom gestures. Idempotent.
 * @function initGraphPanZoom
 * @returns {void}
 */
export function initGraphPanZoom() {
  if (document.documentElement.classList.contains("graphPanZoomReady")) {return;}
  document.documentElement.classList.add("graphPanZoomReady");
  const style = document.createElement("style");
  style.textContent = STYLE;
  document.head.appendChild(style);
  document.addEventListener("pointerdown", onPointerDown);
  document.addEventListener("dragstart", onDragStart);
  // capture so the zoom is claimed before the page's own scroll handling, and
  // passive:false because the gesture cancels the scroll at the clamp boundary.
  document.addEventListener("wheel", onWheel, {passive: false, capture: true});
  document.addEventListener("dblclick", onDoubleClick);
}

// A module script is deferred, so on a page that is already parsed the event it would
// wait for has come and gone and the listener would never run. Start now when the
// document is up, and only wait for the event when it genuinely has not happened yet.
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", initGraphPanZoom);
} else {
  initGraphPanZoom();
}

/**
 * A read-only view of the module's gesture state, for a console or a harness.
 *
 * @returns {Object} the measured render cost, the last span written to the
 *                   configuration, and whether a form to write it into was found
 */
export function graphPanZoomState() {
  return {renderMs: fanOutMs, savedMinutes: savedMinutes, nonceAgeMs: Date.now() - nonceAt,
          form: !!document.getElementById("gform"), live: fanOutLive(fanOutMs, FAN_OUT_BUDGET_MS)};
}

/**
 * The gesture entry points, exported so a test can pin the order they act in: the wheel
 * handler's scroll cancellation has to land before anything is awaited, because the
 * browser commits the scroll as the listeners run.
 */
export {onWheel, onPointerDown};
