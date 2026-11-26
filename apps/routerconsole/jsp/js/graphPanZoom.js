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

import { freeGraphSize, graphBox, pinGraphBoxes, tallestBox } from "./graphBoxes.js";

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

/**
 * ms of gesture silence after which the graph boxes are re-pinned from the pictures that
 * actually came back, rather than from the size the gesture asked for.
 */
const PIN_SETTLE_MS = 600;

/**
 * The page that owns the graph settings: it carries the form, and it is the only one
 * whose POST writes them. The single-graph page has no form of its own, so a gesture
 * there posts here.
 */
const SETTINGS_PAGE = "/graphs";

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

/**
 * The size bounds the console draws a graph at, the same ones GraphHelper's setters
 * clamp to. A graph asked for outside them is drawn at the bound, so a gesture stops
 * there too rather than spending drags and rolls on a size the router will not draw.
 */
const MIN_GRAPH_W = 160;
/** Largest graph width the console draws, in px. */
const MAX_GRAPH_W = 7680;
/** Smallest graph height the console draws, in px. */
const MIN_GRAPH_H = 40;
/** Largest graph height the console draws, in px. */
const MAX_GRAPH_H = 4320;

/** Wheel notches that count as one step, so a fast flick moves several. */
const WHEEL_PIXELS_PER_NOTCH = 100;

/** Drag distance before a press becomes a pan, so a click stays a click. */
const DRAG_SLOP_PX = 4;

/**
 * ms after a pan is released that the click the browser fires from the same press is
 * thrown away. Every graph is a link - to the single-graph page on /graphs, back to the
 * list on /graph - and a pan released over one of them is otherwise a visit to it.
 */
const CLICK_SUPPRESS_MS = 300;

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
 * How far apart the positions a drag may leave the window on are, by how wide the window
 * is.
 *
 * <p>The largest rung of {@link SNAP_LADDER} the window is at least {@link SNAP_STEPS}
 * steps wide: an hour across an eight hour window, a day across a three day one, a week
 * across a month, a month across three months. A rung the window is only one or two steps
 * wide is skipped, because then the drag spends itself crossing a single step and the
 * graph looks stuck rather than quick.
 *
 * @function snapQuantumMs
 * @param {number} spanMs how wide the window is, in ms
 * @param {Array<number>} [ladder=SNAP_LADDER] the quanta, ascending
 * @param {number} [minSteps=3] how many steps the window must be wide enough to have
 * @returns {number} the quantum in ms, 0 when the window is too short to snap
 */
export function snapQuantumMs(spanMs, ladder = SNAP_LADDER, minSteps = SNAP_STEPS) {
  let quantum = 0;
  for (const rung of ladder) {
    if (!(spanMs >= minSteps * rung)) {break;}
    quantum = rung;
  }
  return quantum;
}

/**
 * The offset a drag leaves the window at, snapped to the nearest quantum.
 *
 * <p>This is what makes a drag cheap rather than merely coarse: consecutive frames of a
 * drag land on the same quantum, so they ask for the same picture, and the same picture is
 * not asked for twice.
 *
 * @function snapOffsetMs
 * @param {number} offsetMs how far back from now the pointer put the window, in ms
 * @param {number} quantumMs the quantum, 0 to leave the offset where the pointer put it
 * @returns {number} the nearest multiple of the quantum, never negative
 */
export function snapOffsetMs(offsetMs, quantumMs) {
  if (!quantumMs) {return Math.max(0, offsetMs);}
  return Math.max(0, Math.round(offsetMs / quantumMs) * quantumMs);
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
 * A graph's new width after a ctrl-drag, in rendered px.
 *
 * <p>One pixel of pointer, one pixel of graph: the pointer is holding the graph's own
 * edge, and any other rate makes that edge drift away from the finger. Travel is added a
 * step at a time, exactly as a pan's is, so the gesture keeps growing the graph after the
 * pointer has left the image it started on.
 *
 * @function sizeFromDrag
 * @param {number} size the size the graph is drawn at now, in px
 * @param {number} dragPx how far the pointer moved since the previous step
 * @param {number} min the smallest size the console draws
 * @param {number} max the largest size the console draws
 * @returns {number} the new size, clamped to what the console draws
 */
export function sizeFromDrag(size, dragPx, min, max) {
  const from = Number.isFinite(size) ? size : min;
  return Math.min(max, Math.max(min, Math.round(from + dragPx)));
}

/**
 * A graph's new height after a ctrl-roll, in rendered px.
 *
 * <p>One notch is one ZOOM_STEP of the size, the same step a roll takes through the
 * timeframe: a 280px graph becomes 350px, and back to 280px on the notch that undoes it.
 * A fixed number of px per notch would be a no-op on the 60px images of the graphs page
 * and a jump on a 4000px one.
 *
 * @function sizeFromRoll
 * @param {number} size the size the graph is drawn at now, in px
 * @param {number} notches whole notches, negative to shrink
 * @param {number} min the smallest size the console draws
 * @param {number} max the largest size the console draws
 * @returns {number} the new size, clamped to what the console draws
 */
export function sizeFromRoll(size, notches, min, max) {
  const from = Number.isFinite(size) ? size : min;
  const next = notches ? from * Math.pow(ZOOM_STEP, notches) : from;
  return Math.min(max, Math.max(min, Math.round(next)));
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
 * Decides whether a gesture may re-point the graphs on the screen as it moves, or only the
 * graph it started on.
 *
 * <p>The cost is measured, not assumed: it is the wall time from re-pointing a graph to
 * that image coming back, and it is dominated by the router's render, so a fast box does it
 * in tens of milliseconds and a loaded one takes seconds. The measurement carries across
 * page loads because the box does not change between gestures.
 *
 * <p>What it rules is how many graphs move, not whether anything does: on a slow box even
 * the graphs on the screen cannot be redrawn sixty times a second, so only the one under
 * the pointer follows the gesture and the rest take the window when it ends.
 *
 * @function fanOutLive
 * @param {number} lastMs how long the most recent render took, or -1 if none has been timed
 * @param {number} budgetMs what the page will absorb per step
 * @returns {boolean} true to re-point the graphs on the screen during the gesture
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
 * The fields a settled gesture writes to the graph configuration.
 *
 * <p>The span is one number for the whole page. A size is written under the name the page
 * that owns it reads: the graphs page posts width and height, which are the size of the
 * two dozen small images it draws, and the single-graph page posts singleW and singleH,
 * which are its own. They are different names because they are different sizes - saving a
 * 1500px single graph into the grid's keys turns the graphs page into a wall of 1500px
 * images.
 *
 * <p>A gesture that did not touch the size says nothing about it, so the form's own
 * width and height fields, which hold whatever is saved, go through untouched. A save that
 * did not come from a gesture says nothing about the timeframe either: the page's own
 * c= parameter is a look at one stat's history, and on a link from the graphs page it is
 * three times the saved count for the tiles that plot send and receive together. Writing
 * that back would triple the timeframe every time such a link is followed.
 *
 * @function configFields
 * @param {Object} win the window the gesture settled on, carrying a span only if the
 *                     gesture moved through time and a width and height only if it sized
 *                     the graph
 * @param {boolean} single true on the single-graph page
 * @returns {Object} the fields to override, named as the settings page reads them
 */
export function configFields(win, single) {
  const fields = {};
  if (win.spanMs) {fields.periodCount = configMinutes(win.spanMs);}
  if (win.width) {
    if (single) {fields.singleW = win.width;}
    else {fields.width = win.width;}
  }
  if (win.height) {
    if (single) {fields.singleH = win.height;}
    else {fields.height = win.height;}
  }
  return fields;
}

/**
 * A form's fields as one url-encoded body, with a field replaced.
 *
 * <p>One value per name, the checked boxes only, exactly as a real submit sends them.
 * The console's Jetty does not parse a multipart body - a multipart POST arrives with
 * no parameters at all, saves with a 200, and changes nothing - so the body is built
 * here rather than handed to fetch as form data.
 *
 * <p>Where a name appears twice, the FIRST value wins, because that is what the server
 * reads: the slider boxes in the graph form are a checked input followed by a hidden
 * one carrying the opposite value, and a servlet's getParameter() answers with the first
 * of them. Keeping the last instead posts the hidden value for every option the page
 * has switched on, and one save turns glow, smoothing, the legend and the rest off.
 *
 * @function formBody
 * @param {Array<Array<string>>} fields name and value pairs, as a form holds them, a
 *                                      repeated name for each unchecked duplicate box
 * @param {Object} [overrides] values to replace, by name
 * @returns {string} the url-encoded body
 */
export function formBody(fields, overrides) {
  const one = new Map();
  (fields || []).forEach((pair) => {
    if (!one.has(pair[0])) {one.set(pair[0], pair[1]);}
  });
  Object.keys(overrides || {}).forEach((key) => {one.set(key, String(overrides[key]));});
  let body = "";
  one.forEach((value, key) => {
    body += (body ? "&" : "") + encodeURIComponent(key) + "=" + encodeURIComponent(value);
  });
  return body;
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

/**
 * The quanta a drag may leave the window on, smallest first: a minute, five, ten, thirty,
 * an hour, a day, a week, a month, a year. A drag takes the largest one the window is
 * comfortably wider than, so the window always has a few steps across it and never
 * hundreds - which is what keeps a drag cheap at every zoom, not only at the wide end.
 *
 * <p>A month is 30 days and a year 365. The offset a drag moves is a duration measured
 * back from now, not a timestamp, so there is no calendar month boundary for it to land
 * on: what the ladder buys is the number of distinct pictures, not an alignment.
 *
 * <p>The ends of the ladder are out of reach today and stay in anyway. Nothing narrower
 * than {@link MIN_SPAN_MS} can be shown, so the minute and five-minute rungs wait for that
 * floor to come down; the year rung needs a window of three years, which only a slow-rate
 * stat can reach, because {@link MAX_PERIODS} counts periods, not minutes.
 */
const SNAP_LADDER = [60000, 5 * 60000, 10 * 60000, 30 * 60000, 60 * 60000, 24 * 60 * 60000,
                     7 * 24 * 60 * 60000, 30 * 24 * 60 * 60000, 365 * 24 * 60 * 60000];

/**
 * How many steps a window must be wide enough to have before a drag will snap it to a
 * quantum. Below three the quantum is most of the window, and a drag spends itself
 * crossing one step.
 */
const SNAP_STEPS = 3;

/**
 * The window a gesture is carrying, in wall-clock ms, plus the box it is asking the graphs
 * to be drawn into - the picture's own rendered size, plot and title and legend together -
 * when the gesture is a ctrl-drag or a ctrl-roll rather than a move through time.
 */
let pending = null;
/** The graph the running gesture started on. */
let anchor = null;
/** Timer for the wheel commit. */
let commitTimer = null;
/** Frame handle for the drag preview. */
let frame = null;
/** Timer that lets the readout back in after a gesture ends. */
let holdTimer = null;
/** Timer that ends the claim a roll of the wheel holds over the page. */
let claimTimer = null;
/** Timer for the deferred re-pin of the graph boxes. */
let pinTimer = null;
/** ms after a pan's release during which the browser's own click is swallowed. */
let suppressClickUntil = 0;
/** ms the last graph render took, -1 until one has been timed. */
let fanOutMs = rememberedFanOut();
/** Timer for the deferred configuration write. */
let configTimer = null;
/** The fields last written to the configuration, so a settled page writes once. */
let savedFields = "";
/** When the configuration form's nonce was last read from the server. */
let nonceAt = 0;

/**
 * Whether any part of a box is on the screen the user can see.
 *
 * <p>Partly on screen counts as on screen: a graph whose top half is under the fold is
 * still half of it looking back at you, and leaving that half stale while the gesture
 * moves is exactly the lag this filter exists to remove.
 *
 * @function inViewport
 * @param {{top: number, left: number, bottom: number, right: number}} box the element's
 *        bounding client rect
 * @param {number} width the viewport width in px
 * @param {number} height the viewport height in px
 * @returns {boolean} true if the box overlaps the viewport
 */
export function inViewport(box, width, height) {
  return box.bottom > 0 && box.right > 0 && box.top < height && box.left < width;
}

/** Every graph on the page, in document order. */
function graphs() {
  return Array.from(document.querySelectorAll(GRAPH_SELECTOR));
}

/**
 * The graphs the viewport is showing, the ones a gesture redraws as it moves.
 *
 * <p>The graphs page is two dozen images and only a handful of them fit on the screen.
 * Re-pointing all of them on every frame spends the router on images nobody is looking at,
 * and that is where a drag's lag comes from. The ones off the screen are not slow to
 * update, they are simply not there yet: the commit at the end of the gesture gives them
 * the window, and scrolling to one afterwards finds it already drawn.
 *
 * @function visibleGraphs
 * @returns {Array<HTMLImageElement>} the graphs with any part of them on screen
 */
function visibleGraphs() {
  const root = document.documentElement;
  const width = window.innerWidth || root.clientWidth;
  const height = window.innerHeight || root.clientHeight;
  return graphs().filter((img) => inViewport(img.getBoundingClientRect(), width, height));
}

/**
 * The counts one graph would need to show this window, clamped the way applyWindow
 * clamps them, so a comparison against the graph's src is made against what would
 * actually be asked for.
 *
 * @function countsFor
 * @param {HTMLImageElement} img the graph image
 * @param {Object} win the window to show
 * @returns {{periodCount: number, end: number}} counts of the graph's own period
 */
function countsFor(img, win) {
  const params = new URL(img.src, location.href).searchParams;
  const periodMs = periodMsOf(params);
  const maxMs = MAX_PERIODS * periodMs;
  const spanMs = Math.min(maxMs, Math.max(MIN_SPAN_MS, win.spanMs));
  const offsetMs = Math.min(maxMs, Math.max(0, win.offsetMs));
  return windowParams(spanMs, offsetMs, periodMs);
}

/**
 * Whether a graph is already showing this window at this size.
 *
 * <p>The cache-busting time is deliberately not part of the comparison: it says when the
 * image was last asked for, not what it shows, and comparing it would make every graph
 * look stale.
 *
 * @function shows
 * @param {HTMLImageElement} img the graph image
 * @param {Object} win the window being asked for
 * @param {{periodCount: number, end: number}} counts the counts that window becomes
 * @param {?{width: number, height: number}} size the plot this graph would be asked for,
 *                                              null if the gesture is not sizing it
 * @returns {boolean} true if the graph's src already carries them
 */
function shows(img, win, counts, size) {
  const params = new URL(img.src, location.href).searchParams;
  if (params.get("periodCount") !== String(counts.periodCount)) {return false;}
  if ((params.get("end") || "0") !== String(counts.end)) {return false;}
  if (size) {
    if (params.get("width") !== String(size.width)) {return false;}
    if (params.get("height") !== String(size.height)) {return false;}
  }
  return true;
}

/**
 * Writes a window, and a size when the gesture carries one, into one graph's src,
 * re-anchoring its cache-busting time.
 *
 * @function applyWindow
 * @param {HTMLImageElement} img the graph image
 * @param {Object} win the window to show, and the box to draw it in if the gesture sized it
 * @param {{width: number, height: number}} fallback the chrome to size a graph with whose
 *        picture has not been drawn, read once per fan-out
 * @returns {void}
 */
function applyWindow(img, win, fallback) {
  const counts = countsFor(img, win);
  if (!counts.periodCount) {return;}
  // Either axis on its own is a size the gesture settled on: a ctrl-drag moves the width
  // and leaves the height of every graph where it is.
  const size = win.width || win.height ? sizeFor(img, win, fallback) : null;
  // A graph already showing this window at this size is left where it is. Rewriting the src
  // re-anchors the cache-busting time, and the router draws, and the browser decodes and
  // paints, a picture identical to the one already on screen.
  if (shows(img, win, counts, size)) {return;}
  const url = new URL(img.src, location.href);
  url.searchParams.set("periodCount", String(counts.periodCount));
  if (counts.end > 0) {url.searchParams.set("end", String(counts.end));}
  else {url.searchParams.delete("end");}
  if (size) {
    url.searchParams.set("width", String(size.width));
    url.searchParams.set("height", String(size.height));
    // The picture is the thing that changes size. A width or height attribute on the image
    // is a box the browser scales the new picture into, so a graph redrawn larger inside the
    // box it was first drawn at comes out squashed instead of bigger.
    freeGraphSize(img);
  }
  url.searchParams.set("time", String(Date.now()));
  img.src = url.toString();
}

/**
 * The plot size a graph's src asks the router to draw, in px, 0 where the src does not say.
 *
 * <p>This is not the size of the picture. It is the size of the plot inside it, and it is
 * the number the console's own width and height fields hold.
 *
 * @function paramSizeOf
 * @param {HTMLImageElement} img the graph image
 * @returns {{width: number, height: number}} requested plot px, 0 if unsaid
 */
function paramSizeOf(img) {
  const params = new URL(img.src, location.href).searchParams;
  return {width: parseInt(params.get("width") || "", 10) || 0,
          height: parseInt(params.get("height") || "", 10) || 0};
}

/**
 * What the router draws around a plot: the title, the axis labels and, unless the options
 * hide it, the legend.
 *
 * <p>Measured off the graph rather than assumed, because it is not one number. A plain
 * stat with its legend on is 135px of chrome; the combined bandwidth tile, which carries a
 * legend for send and another for receive, is 169px. That is why the graphs page does not
 * ask for every tile at the same plot size - it takes 26px off the combined and group
 * tiles so their pictures come out the same height as everyone else's.
 *
 * @function chromeOf
 * @param {HTMLImageElement} img the graph image
 * @returns {?{width: number, height: number}} the px around the plot, null if the graph
 *                                            has not been drawn or its src says no size
 */
function chromeOf(img) {
  const asked = paramSizeOf(img);
  if (!(asked.width > 0) || !(asked.height > 0) ||
      !(img.naturalWidth > 0) || !(img.naturalHeight > 0)) {return null;}
  return {width: img.naturalWidth - asked.width, height: img.naturalHeight - asked.height};
}

/**
 * The chrome of the plainest graph on the page.
 *
 * <p>This is the chrome the configuration's width and height are measured against: the
 * graphs page asks a plain stat for exactly the configured plot and compensates only the
 * combined and group tiles. It is also what a graph whose picture has not been drawn yet
 * is sized with, which is the same size its neighbours were drawn at.
 *
 * @function referenceChrome
 * @returns {{width: number, height: number}} the smallest chrome on the page, 0 if none
 */
function referenceChrome() {
  return referenceChromeFor(graphs());
}

/**
 * The smallest chrome a set of graphs draws - the plainest graph among them.
 *
 * @function referenceChromeFor
 * @param {HTMLImageElement[]} imgs the graphs to measure
 * @returns {{width: number, height: number}} the smallest chrome, 0 if none has been drawn
 * @since 0.9.72+
 */
function referenceChromeFor(imgs) {
  let width = Infinity;
  let height = Infinity;
  for (const img of imgs) {
    const chrome = chromeOf(img);
    if (!chrome) {continue;}
    width = Math.min(width, chrome.width);
    height = Math.min(height, chrome.height);
  }
  return {width: Number.isFinite(width) ? width : 0, height: Number.isFinite(height) ? height : 0};
}

/**
 * The box a graph's picture occupies, in rendered px.
 *
 * <p>The plot plus its chrome. This is the size the page lays out and the size a size
 * gesture moves: the pointer is on the picture, not on the plot, and the box the console
 * pins its graphs to is measured in pictures.
 *
 * @function boxOf
 * @param {HTMLImageElement} img the graph image
 * @returns {{width: number, height: number}} rendered px
 */
function boxOf(img) {
  const drawn = graphBox(img);
  if (drawn) {return drawn;}
  const asked = paramSizeOf(img);
  const chrome = referenceChrome();
  return {width: (asked.width || MIN_GRAPH_W) + chrome.width,
          height: (asked.height || MIN_GRAPH_H) + chrome.height};
}

/**
 * The plot size a box needs, given the chrome one graph carries.
 *
 * @function plotFor
 * @param {{width: number, height: number}} chrome the px around the plot
 * @param {{width: number, height: number}} box the picture to draw
 * @returns {{width: number, height: number}} plot px, clamped to what the console draws
 */
export function plotFor(chrome, box) {
  return {width: Math.min(MAX_GRAPH_W, Math.max(MIN_GRAPH_W, box.width - chrome.width)),
          height: Math.min(MAX_GRAPH_H, Math.max(MIN_GRAPH_H, box.height - chrome.height))};
}

/**
 * The picture one graph has to be drawn at so that it fills the box the gesture settled on.
 *
 * <p>Either axis may be absent, and an absent axis means the graph keeps the size it has: a
 * ctrl-drag widens a graph and a ctrl-roll makes it taller, and a graph whose height was
 * never asked about must not find itself redrawn at the anchor graph's height, which is the
 * number the gesture happened to start on. That is what keeps a graph's proportions: the
 * axis nobody touched stays where it was.
 *
 * <p>The box, not the plot, is what the gesture moves. The plot inside it is this graph's box
 * less the chrome this graph draws, and every graph gets its own - the graphs page pins every
 * .graphContainer to one size, so graphs asked for the same plot draw pictures of different
 * heights (with the legend showing, a tile that plots send and receive together spends 38px
 * more on two legends than the plain stats beside it) and the pin then clips the tall one or
 * leaves a band of empty box under the short one. Asking each for its box less its own chrome
 * makes the pictures equal, which is what the pin expects.
 *
 * @function boxFor
 * @param {HTMLImageElement} img the graph image
 * @param {Object} win the window, carrying either axis of the box the gesture settled on
 * @param {{width: number, height: number}} fallback the chrome to use for a graph whose
 *        picture has not been drawn, read once per fan-out
 * @returns {{width: number, height: number, chrome: {width: number, height: number}}} the
 *          picture px, and the px around the plot that graph draws
 */
function boxFor(img, win, fallback) {
  const asked = paramSizeOf(img);
  const chrome = chromeOf(img) || fallback;
  return {width: win.width || asked.width + chrome.width,
          height: win.height || asked.height + chrome.height, chrome: chrome};
}

/**
 * The plot one graph has to be asked for so that its picture fills the box the gesture
 * settled on.
 *
 * @function sizeFor
 * @param {HTMLImageElement} img the graph image
 * @param {Object} win the window, carrying either axis of the box the gesture settled on
 * @param {{width: number, height: number}} fallback the chrome to use for a graph whose
 *        picture has not been drawn
 * @returns {{width: number, height: number}} plot px, clamped to what the console draws
 */
function sizeFor(img, win, fallback) {
  const box = boxFor(img, win, fallback);
  return plotFor(box.chrome, box);
}

/**
 * The biggest box the page would hold if this window were written into every graph - the
 * size to pin the graph boxes to.
 *
 * <p>Measured from what each graph would be asked for rather than from the gesture's own
 * box, because the two differ whenever the gesture sized only one axis: a ctrl-drag has
 * nothing to say about height, and a page that pinned every box to the height of the graph
 * the drag happened to start on would shrink every other graph into a picture of different
 * proportions.
 *
 * @function requestedBox
 * @param {Object} win the window, carrying either axis of the box the gesture settled on
 * @returns {?{width: number, height: number}} the box, null if no graph has a size to go by
 */
function requestedBox(win) {
  return requestedBoxFor(graphs(), win);
}

/**
 * The biggest box a set of graphs would hold under this window.
 *
 * <p>The graphs are passed in rather than read off the page, so the rule the pin depends on
 * can be tested against graphs that are not on any page.
 *
 * @function requestedBoxFor
 * @param {HTMLImageElement[]} imgs the graphs to measure
 * @param {Object} win the window, carrying either axis of the box the gesture settled on
 * @returns {?{width: number, height: number}} the box, null if no graph has a size to go by
 * @since 0.9.72+
 */
export function requestedBoxFor(imgs, win) {
  const fallback = referenceChromeFor(imgs);
  let width = 0;
  let height = 0;
  let sized = false;
  for (const img of imgs) {
    const box = boxFor(img, win, fallback);
    if (!(box.width > 0) || !(box.height > 0)) {continue;}
    sized = true;
    width = Math.max(width, box.width);
    height = Math.max(height, box.height);
  }
  return sized ? {width: width, height: height} : null;
}

/** The style element the graphs page pins its graph boxes in, null on the single-graph page. */
function boxStyle() {
  return document.getElementById("gwrap");
}

/**
 * Keeps the page's graph boxes the size of the pictures the gesture is asking for.
 *
 * <p>The graphs page pins every .graphContainer to one size and writes that pin once, when
 * the graphs first arrive. A gesture that changes the size of the pictures leaves the pin
 * behind: the box stays the old size, the theme's max-width:100% scales the new and larger
 * picture down into it, and the graph being resized appears not to resize at all.
 *
 * <p>Pinned from the biggest box the page would hold once the window is written into every
 * graph, not from the size the gesture named: a ctrl-drag says nothing about height, and a
 * page that pinned height to the height of the graph the drag happened to start on would
 * squash every other graph into different proportions. Pinned in the same frame as the
 * pictures rather than a render later, so the box grows with the pointer. The picture that
 * comes back is a few px off what was asked for, because the router's title and axis space
 * grows a little along with the plot, so the measured size follows once it is known.
 *
 * @function pinBoxes
 * @param {Object} win the window, carrying either axis of the box the gesture settled on
 * @returns {void}
 */
function pinBoxes(win) {
  if (!win.width && !win.height) {return;}
  pinGraphBoxes(boxStyle(), requestedBox(win));
}

/**
 * Re-pins the graph boxes from the pictures that actually came back, a beat after the last
 * size change, so the boxes end up the size of the tallest graph rather than the size asked
 * for. Nothing happens on the single-graph page, which pins its own box as each picture
 * arrives.
 *
 * @function schedulePin
 * @returns {void}
 */
function schedulePin() {
  if (pinTimer) {clearTimeout(pinTimer);}
  pinTimer = setTimeout(() => {
    pinTimer = null;
    pinGraphBoxes(boxStyle(), tallestBox(graphs()));
  }, PIN_SETTLE_MS);
}

/**
 * Whether this is the single-graph page, whose size is kept apart from the grid's.
 * @function isSinglePage
 * @returns {boolean} true on /graph, false on the graphs page
 */
function isSinglePage() {
  return !!document.getElementById("graphSingle");
}

/**
 * Hands the window the gesture settled on to every graph it did not already move.
 *
 * <p>That is the graphs that were off the screen while the gesture ran, and on a slow box
 * every graph but the one under the pointer. A graph already showing the window is left
 * alone rather than asked for a second time: the router would redraw it and the browser
 * would reload it, for a picture the page is already displaying.
 *
 * @function commit
 * @param {Object} win the window to show, and the box to draw it in if the gesture sized it
 * @returns {void}
 */
function commit(win) {
  const fallback = referenceChrome();
  graphs().forEach((img) => applyWindow(img, win, fallback));
  if (win.width || win.height) {schedulePin();}
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
 * @param {{width: number, height: number}} fallback the chrome to size a graph with whose
 *        picture has not been drawn
 * @returns {void}
 */
function timeAnchor(img, win, fallback) {
  const started = Date.now();
  const done = () => noteFanOut(Date.now() - started);
  img.addEventListener("load", done, {once: true});
  img.addEventListener("error", done, {once: true});
  applyWindow(img, win, fallback);
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
 * A CSRF nonce for our own POST, minted by re-reading the page that carries the form.
 *
 * <p>The console issues one nonce per form and consumes it on the first POST that
 * carries it, so posting the nonce already sitting in the page's form spends it: our
 * save succeeds and the Save button the user then presses is refused for a bad nonce.
 * Minting our own costs one GET of the page and leaves the form's nonce unspent.
 *
 * <p>The nonce also ages out - a page left open through a quiet afternoon carries one
 * the session no longer accepts - which re-reading the page fixes in the same pass.
 *
 * @function mintNonce
 * @param {string} url the settings page to re-read, which carries a fresh nonce
 * @param {string} fallback the nonce already on this page, if it has one
 * @returns {Promise<string>} a nonce to post; the page's own only if the settings page
 *                            cannot be re-read, and that fallback spends it
 */
async function mintNonce(url, fallback) {
  try {
    const response = await fetch(url, {credentials: "same-origin"});
    if (response.ok) {
      const html = await response.text();
      const found = /name=nonce value=([^ >]*)/.exec(html);
      if (found && found[1]) {
        nonceAt = Date.now();
        return found[1];
      }
    }
  } catch (e) {
    // A page that cannot be re-read falls back to the nonce it already has.
  }
  return fallback;
}

/**
 * A stable key for a set of configuration fields, so a page that settles on the same
 * values twice writes once.
 * @function fieldKey
 * @param {Object} fields the fields about to be written
 * @returns {string} the fields sorted by name, as name=value pairs
 */
function fieldKey(fields) {
  return Object.keys(fields).sort().map((name) => name + "=" + fields[name]).join("&");
}

/**
 * The plot size the configuration's own width and height fields describe, read off the
 * plainest graph on the page.
 *
 * <p>Read from the images rather than from the form: the form's width and height fields hold
 * the size the page was rendered with, and nothing re-renders the form when a gesture changes
 * the size, so a pan that posted them would undo a resize the same page made a minute earlier.
 *
 * <p>The plainest graph rather than the first one that carries a size: the graphs page asks a
 * plain stat for exactly the configured plot and takes a compensation off the combined and
 * group tiles, so a plain stat's src is the only one carrying the number the configuration
 * holds. A graph whose src carries no size - one not yet lazy-loaded, or pulled from another
 * router's console - is used only if there is nothing better to go by.
 *
 * @function pageParam
 * @returns {?{width: number, height: number}} plot px, or null if no graph states one
 */
function pageParam() {
  let asked = null;
  let narrowest = null;
  for (const img of graphs()) {
    const size = paramSizeOf(img);
    if (!(size.width > 0) || !(size.height > 0)) {continue;}
    const chrome = chromeOf(img);
    if (!chrome) {asked = asked || size; continue;}
    if (!narrowest || chrome.width < narrowest.width || chrome.height < narrowest.height) {
      asked = size;
      narrowest = chrome;
    }
  }
  return asked;
}

/**
 * The box a gesture settled on, as the plot size the configuration holds.
 *
 * <p>The two are not the same number: the gesture moves the picture, the configuration sizes
 * the plot inside it, and the difference is the title, the axis and the legend drawn around
 * it. Reducing the box by the chrome of the page's plainest graph gives the plot that graph
 * is asked for, which is the number the settings page writes.
 *
 * <p>An axis the gesture never moved is written back as the plot the page is already drawn
 * at, so a ctrl-drag that only widened a graph cannot save a height nobody set, and a
 * gesture that sized nothing at all leaves both axes as they are.
 *
 * @function configSize
 * @param {Object} win the window, carrying either axis of the box the gesture settled on
 * @returns {Object} the same window with width and height in plot px
 */
function configSize(win) {
  const current = pageParam();
  if (!current || (!win.width && !win.height)) {return Object.assign({}, win, current);}
  const chrome = referenceChrome();
  const plot = plotFor(chrome, {width: win.width || current.width + chrome.width,
                                height: win.height || current.height + chrome.height});
  return {spanMs: win.spanMs, offsetMs: win.offsetMs, width: plot.width, height: plot.height};
}

/**
 * Writes the window and size the gesture settled on into the graph configuration, so the
 * page opens on the timeframe and the size you left it on.
 *
 * <p>On the graphs page the configuration is the form the page already carries, posted as
 * it stands with only the gesture's own fields changed: the form holds every other
 * setting and the save needs them all, because the servlet writes the whole group at
 * once. The single-graph page carries no form, so a save there names only what the gesture
 * changed and every setting it does not name keeps the value the router already holds.
 * Either way the reply is discarded - the point is the write, not a new page. The offset
 * is left out on purpose: a window that ends three hours ago is a look back, not a
 * preference.
 *
 * @function saveConfig
 * @param {Object} win the window the page settled on, and its box if the gesture sized it
 * @returns {Promise<void>} when the configuration has been written
 */
async function saveConfig(win) {
  const single = isSinglePage();
  const form = document.getElementById("gform");
  if (!form && !single) {return;}
  // A gesture that did not size a graph still writes the size the page is showing, so a
  // pan cannot undo a resize made earlier on the same page.
  const sized = configSize(win);
  const fields = configFields(sized, single);
  const key = fieldKey(fields);
  if (key === savedFields) {return;}
  savedFields = key;
  const posted = [];
  if (form) {
    new FormData(form).forEach((value, name) => {posted.push([name, value]);});
  } else {
    posted.push(["action", "Save"]);
  }
  const field = form ? form.querySelector("[name=nonce]") : null;
  const nonce = await mintNonce(SETTINGS_PAGE, field ? field.value : "");
  try {
    const response = await fetch(SETTINGS_PAGE, {
      method: "POST",
      // The minted nonce goes in as an override, not another field: the form's own
      // nonce is already in the field list, and the first value for a name is the one
      // the server reads, so appending a second would post the spent one and leave this
      // one unused.
      body: formBody(posted, Object.assign({}, fields, {nonce: nonce})),
      credentials: "same-origin",
      headers: {"content-type": "application/x-www-form-urlencoded"},
    });
    // The reply is the whole re-rendered page, which is of no use here: the graphs on
    // screen carry their own window in their src and are untouched by the write. Stop
    // reading it rather than pulling 58KB across the link to throw it away.
    if (response.body) {response.body.cancel();}
    if (!response.ok) {savedFields = "";}
  } catch (e) {
    savedFields = "";
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

/**
 * Schedules a preview on the next frame, at most one per frame.
 *
 * <p>A box that has proved it can redraw a graph inside the budget moves every graph the
 * viewport is showing along with the pointer; a slow one moves only the graph under it and
 * leaves the rest to the commit at the end. Either way the graphs off the screen wait for
 * that commit, because redrawing images nobody can see is what puts the lag in a drag.
 *
 * @returns {void}
 */
function preview() {
  if (frame !== null || !pending || !anchor) {return;}
  const win = pending;
  const img = anchor;
  frame = requestAnimationFrame(() => {
    frame = null;
    const fallback = referenceChrome();
    if (fanOutLive(fanOutMs, FAN_OUT_BUDGET_MS)) {
      visibleGraphs().forEach((other) => applyWindow(other, win, fallback));
    } else {
      timeAnchor(img, win, fallback);
    }
    // A sizing gesture moves the boxes along with the pictures, in the same frame: a box
    // left at the old size is a frame in which the graph being resized has not moved.
    if (win.width || win.height) {
      pinBoxes(win);
      schedulePin();
    }
    // The write is scheduled here rather than inside commit: a gesture that ends
    // between two frames has nothing left to re-point by then, but the window it
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
 * Starts a gesture on a graph image: a plain press moves the timeframe through the
 * graph, a ctrl (or cmd) press takes hold of the graph's own edge and sizes it instead.
 *
 * @function onPointerDown
 * @param {PointerEvent} event the press
 * @returns {Promise<void>} when the gesture is ready to follow the pointer
 */
async function onPointerDown(event) {
  if (event.button !== 0) {return;}
  const img = graphUnder(event.target);
  if (!img) {return;}
  if (event.ctrlKey || event.metaKey) {startSizeDrag(event, img); return;}
  await startPan(event, img);
}

/**
 * Puts a gesture's pointer handlers on the page and hands back the function that takes
 * them off again, so a pan and a size drag cannot disagree about which events they own.
 *
 * @function holdPointer
 * @param {function(PointerEvent): void} onMove follows the pointer
 * @param {function(): void} onUp ends the gesture
 * @returns {function(): void} removes the handlers this put on the page
 */
function holdPointer(onMove, onUp) {
  document.addEventListener("pointermove", onMove);
  document.addEventListener("pointerup", onUp);
  document.addEventListener("pointercancel", onUp);
  return () => {
    document.removeEventListener("pointermove", onMove);
    document.removeEventListener("pointerup", onUp);
    document.removeEventListener("pointercancel", onUp);
  };
}

/**
 * Ends a gesture held by the pointer. A gesture that travelled hands its window to the
 * rest of the page, writes it to the configuration, and throws away the click the browser
 * fires from the same press; one that never travelled past the slop was a click, changed
 * nothing, and owes the page neither a re-render nor a write.
 *
 * @function finishDrag
 * @param {function(): void} release takes this gesture's pointer handlers off the page
 * @param {boolean} moved whether the pointer travelled far enough to be a gesture
 * @returns {void}
 */
function finishDrag(release, moved) {
  release();
  if (!moved) {
    pending = null;
    anchor = null;
    endGesture();
    return;
  }
  suppressClickUntil = Date.now() + CLICK_SUPPRESS_MS;
  if (pending && anchor) {
    // commit() only touches the graphs the gesture left alone: a drag that moved
    // everything on screen costs nothing here, and a preview drag owes only the images
    // that were off the screen while it ran.
    commit(pending);
    scheduleConfigSave(pending);
  }
  pending = null;
  anchor = null;
  endGesture();
}

/**
 * Starts a pan: the timeframe moves under the pointer.
 *
 * @function startPan
 * @param {PointerEvent} event the press
 * @param {HTMLImageElement} img the graph under it
 * @returns {Promise<void>} when the plot geometry the drag scales by has been read
 */
async function startPan(event, img) {
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
  /** Whether this press travelled far enough to be a pan rather than a click. */
  let panned = false;
  let lastX = startX;
  // The pointer's own travel, unsnapped. Snapping this instead of what it produces would
  // throw away every step smaller than half a quantum, and a slow drag would never move
  // the window at all.
  let raw = startOffset;
  /** The window the gesture has published, the raw travel snapped to the quantum. */
  let offset = startOffset;
  // How far apart the window's positions may be, and never finer than one pixel of the
  // graph: below that the picture cannot change, and a step finer than a pixel is a render
  // nobody could see. On a window of hours the hour is the binding rule; on a window of
  // months the week is, and the pixel only ever binds between them.
  const quantum = Math.max(snapQuantumMs(spanMs), spanMs / Math.max(1, area.width));
  /**
   * Follows the pointer until it is released.
   * @function onMove
   * @param {PointerEvent} move the pointer movement
   * @returns {void}
   */
  const onMove = (move) => {
    // Travel is taken a step at a time rather than from where the press began, so the
    // gesture outlives the graph's own width: the pointer can leave the image and keep
    // winding the timeframe, at the same time-per-pixel rate the graph sets. A single
    // span-wide measurement from the press would run out of page at the far edge.
    raw = panOffsetMs(raw, spanMs, area.width, move.clientX - lastX);
    offset = snapOffsetMs(raw, quantum);
    lastX = move.clientX;
    if (Math.abs(lastX - startX) < DRAG_SLOP_PX) {return;}
    panned = true;
    pending = {spanMs: spanMs, offsetMs: offset};
    preview();
  };
  /** Ends the pan. @function onUp @returns {void} */
  const onUp = () => {finishDrag(release, panned);};
  const release = holdPointer(onMove, onUp);
}

/**
 * Starts a size drag: with ctrl held, the graph's edge follows the pointer and its width
 * changes while the window it shows stays where it was.
 *
 * <p>No plot geometry is asked for here. A pan has to turn pointer px into time using the
 * plot's width; a size drag's rate is one px of graph per px of pointer by definition, so
 * the gesture starts on the frame it is pressed on instead of one metadata round trip
 * later.
 *
 * @function startSizeDrag
 * @param {PointerEvent} event the press
 * @param {HTMLImageElement} img the graph under it
 * @returns {void}
 */
function startSizeDrag(event, img) {
  beginGesture();
  const params = new URL(img.src, location.href).searchParams;
  const win = windowOf(params, periodMsOf(params));
  const box = boxOf(img);
  // The pointer moves the picture, but the bounds the console draws within are on the plot
  // inside it, so the box is clamped at the size that plot makes, chrome included.
  const chrome = chromeOf(img) || referenceChrome();
  const startX = event.clientX;
  /** Whether this press travelled far enough to be a gesture rather than a click. */
  let dragged = false;
  let lastX = startX;
  let width = box.width;
  // Only the axis the pointer moves. The other is left at whatever each graph is already
  // drawn at, so widening a graph never redraws it shorter.
  pending = {spanMs: win.spanMs, offsetMs: win.offsetMs, width: width};
  anchor = img;
  /**
   * Follows the pointer until it is released.
   * @function onMove
   * @param {PointerEvent} move the pointer movement
   * @returns {void}
   */
  const onMove = (move) => {
    width = sizeFromDrag(width, move.clientX - lastX,
                         MIN_GRAPH_W + chrome.width, MAX_GRAPH_W + chrome.width);
    lastX = move.clientX;
    if (Math.abs(lastX - startX) < DRAG_SLOP_PX) {return;}
    dragged = true;
    pending = {spanMs: win.spanMs, offsetMs: win.offsetMs, width: width};
    preview();
  };
  /** Ends the size drag. @function onUp @returns {void} */
  const onUp = () => {finishDrag(release, dragged);};
  const release = holdPointer(onMove, onUp);
}

/**
 * The graph a gesture belongs to.
 *
 * <p>The console wraps every graph in a .graphContainer span, and on the single-graph
 * page the image sits inside a link back to the graph list. A press or a roll that
 * lands on the box's border, or on the link rather than on the plot, belongs to that
 * graph all the same, so the lookup walks up to the box and back down to its image.
 *
 * @function graphUnder
 * @param {Element} target the event's target
 * @returns {?HTMLImageElement} the graph under the pointer, null if there is none
 */
function graphUnder(target) {
  if (!target || !target.closest) {return null;}
  const img = target.closest(GRAPH_SELECTOR);
  if (img) {return img;}
  const box = target.closest(".graphContainer");
  return box ? box.querySelector(GRAPH_SELECTOR) : null;
}

/**
 * Rolls the mouse wheel over a graph: a plain roll shortens or lengthens the span it
 * shows, a ctrl (or cmd) roll sizes the graph instead.
 *
 * @function onWheel
 * @param {WheelEvent} event the wheel movement
 * @returns {Promise<void>} when the geometry needed to anchor the zoom is read
 */
async function onWheel(event) {
  // Claimed over a graph image or the box that holds it: the console wraps every graph
  // in .graphContainer, and a roll that lands on its border belongs to that graph as
  // much as one that lands on the plot.
  const img = graphUnder(event.target);
  if (!img) {return;}
  // The wheel belongs to the graph whenever the pointer is over it - at the ends of the
  // zoom as much as in the middle of one. A roll that has run out of span is swallowed,
  // not handed back: a page that suddenly scrolls under a wheel that is zooming a graph
  // moves the graph the user is looking at off the screen mid-gesture. This is also what
  // keeps the browser's own ctrl-wheel page zoom away from a graph.
  //
  // Cancelled here, synchronously, before anything is awaited: the browser commits a
  // wheel scroll as the listeners run, so a preventDefault that arrives after the
  // metadata has come back is one the page never hears about.
  event.preventDefault();
  const params = new URL(img.src, location.href).searchParams;
  const periodMs = periodMsOf(params);
  const win = windowOf(params, periodMs);
  const notches = wheelNotches(event.deltaY, event.deltaMode);
  if (event.ctrlKey || event.metaKey) {sizeRoll(img, win, notches); return;}
  const nextSpan = zoomSpan(win.spanMs, notches, MAX_PERIODS * periodMs);
  // At either end of the zoom the window cannot move, and the roll is spent on that.
  const rolls = notches !== 0 && nextSpan !== win.spanMs;
  // Claimed before the await for the same reason the scroll is cancelled there: the
  // readout has to be out of the way before the first frame of the gesture paints.
  if (rolls) {beginGesture();}
  const meta = await metaFor(img);
  if (!meta || !rolls) {return;}
  holdRoll();
  const area = plotArea(img.getBoundingClientRect(), img.naturalWidth, meta.plot);
  pending = zoomWindow(win.spanMs, win.offsetMs, nextSpan, cursorFraction(event.clientX, area));
  anchor = img;
  preview();
  scheduleRollCommit();
}

/**
 * Holds the page for a roll and lets go of it a beat later, so a roll that pauses
 * between notches does not let the readout back in mid-gesture.
 * @function holdRoll
 * @returns {void}
 */
function holdRoll() {
  if (claimTimer) {clearTimeout(claimTimer);}
  claimTimer = setTimeout(() => {claimTimer = null; endGesture();}, WHEEL_CLAIM_MS);
}

/**
 * Ends a roll a beat after its last notch.
 *
 * <p>In live mode every roll has already been applied to every graph by the frame it was
 * rolled on, and committing again would re-render the page a second time for a window it
 * is already showing. In preview mode the rolls only touched the one graph under the
 * pointer, so the settle is what hands the window to the rest.
 *
 * @function scheduleRollCommit
 * @returns {void}
 */
function scheduleRollCommit() {
  if (commitTimer) {clearTimeout(commitTimer);}
  commitTimer = setTimeout(() => {
    commitTimer = null;
    // Every roll has already been applied to every graph the viewport was showing on the
    // frame it was rolled on. What is left is the images that were off screen, and the
    // graphs a slow box never moved at all.
    if (pending) {
      commit(pending);
      scheduleConfigSave(pending);
    }
    pending = null;
    anchor = null;
  }, WHEEL_COMMIT_MS);
}

/**
 * Rolls the height of a graph: with ctrl held, the roll sizes the graph instead of
 * moving the timeframe through it.
 *
 * <p>Rolling up grows the graph, the direction a roll up on a timeframe zoom also brings
 * more detail in - which is the opposite sign to the span, where up shortens it.
 *
 * @function sizeRoll
 * @param {HTMLImageElement} img the graph under the pointer
 * @param {{spanMs: number, offsetMs: number}} win the window the graph is showing
 * @param {number} notches how far the wheel turned, negative to grow the graph
 * @returns {void}
 */
function sizeRoll(img, win, notches) {
  const box = boxOf(img);
  const chrome = chromeOf(img) || referenceChrome();
  const height = sizeFromRoll(box.height, -notches,
                              MIN_GRAPH_H + chrome.height, MAX_GRAPH_H + chrome.height);
  // At either end of the size the graph cannot change, and the roll is spent on that.
  if (notches === 0 || height === box.height) {return;}
  beginGesture();
  holdRoll();
  pending = {spanMs: win.spanMs, offsetMs: win.offsetMs, height: height};
  anchor = img;
  preview();
  scheduleRollCommit();
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
  if (!graphUnder(event.target)) {return;}
  event.preventDefault();
}

/**
 * Throws away the click a pan leaves behind.
 *
 * <p>A pan is a press, a travel and a release, and the browser reads exactly that as a
 * click on whatever is under the pointer when the hand stops. Every graph is wrapped in
 * a link - to the single-graph page from the list, back to the list from the single
 * graph - so a pan released over its own graph would navigate to it. Only a press that
 * travelled is swallowed: a click that was a click still opens the graph.
 *
 * @function onClick
 * @param {MouseEvent} event the click the pan generated
 * @returns {void}
 */
function onClick(event) {
  if (Date.now() < suppressClickUntil && graphUnder(event.target)) {
    event.preventDefault();
    event.stopPropagation();
  }
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
 * Writes the size this page was asked to draw at into the configuration, on the single-graph
 * page only.
 *
 * <p>That page's Larger, Taller and Wider links are plain hrefs: each reloads the page at a
 * new size, and nothing about that size ever reaches the configuration, so the next visit
 * comes back at the old one. Saving what the page was asked for makes those links stick, the
 * way a resize made by hand already does.
 *
 * <p>The timeframe is deliberately not saved here. A link from the graphs page carries a
 * period count of its own - three times the saved count for a tile that plots send and
 * receive together - and that is a look at one stat's history, not a preference.
 *
 * <p>When the link already carries the saved size, which is what the graphs page emits, the
 * write changes nothing on the server.
 *
 * @function saveRequestedSize
 * @returns {void}
 */
function saveRequestedSize() {
  const size = pageParam();
  if (!size) {return;}
  saveConfig({width: size.width, height: size.height});
}

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
  // capture, so the click a pan leaves behind is stopped before the link around the
  // graph can act on it
  document.addEventListener("click", onClick, true);
  if (isSinglePage()) {saveRequestedSize();}
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
 * @returns {Object} the measured render cost, the fields last written to the
 *                   configuration, how many graphs the page holds and how many of them the
 *                   viewport is showing, and whether a gesture moves those or only its own
 */
export function graphPanZoomState() {
  const count = graphs().length;
  const saved = /periodCount=(\d+)/.exec(savedFields);
  const box = tallestBox(graphs());
  return {renderMs: fanOutMs, savedMinutes: saved ? Number(saved[1]) : -1,
          savedFields: savedFields, nonceAgeMs: Date.now() - nonceAt,
          form: !!document.getElementById("gform"), graphs: count,
          visible: visibleGraphs().length, single: isSinglePage(),
          box: box, plot: box ? plotFor(referenceChrome(), box) : null,
          live: fanOutLive(fanOutMs, FAN_OUT_BUDGET_MS)};
}

/**
 * The gesture entry points, exported so a test can pin the order they act in: the wheel
 * handler's scroll cancellation has to land before anything is awaited, because the
 * browser commits the scroll as the listeners run.
 */
export {onWheel, onPointerDown, onDragStart, onClick};
