/**
 * @module snarkGraph
 * @file snarkGraph.js - Self-contained bandwidth graph for the I2PSnark screen log.
 * @description Builds a dual-baseline (down/up) SVG graph from server-sampled
 * traffic carried in the refresh payload, then publishes it as a data URL on the
 * --snarkGraph CSS custom property. Themes already layer that property into the
 * #screenlog background, so the graph inherits all theme blending with no CSS
 * changes and no rrd4j/viewstat dependency. Replaces graphRefresh.js, which fetched
 * an rrd4j PNG that only existed when optional stats persistence was enabled.
 *
 * Native SVG (not canvas): samples update once per minute, so there is nothing to
 * gain from rasterization — SVG stays crisp at any size and the data URL is a few
 * KB of text. The svg carries both a viewBox (coordinate system) and explicit
 * width/height (intrinsic size), which background-image data URLs need for
 * consistent scaling across browsers.
 *
 * Dimensions are fitted to the live #screenlog box on every draw (they can change
 * with themes and layout), and a ResizeObserver/window-resize hook redraws on
 * container resize so the graph always matches its container exactly.
 *
 * All state is module-local; entry points are update(payload) plus repaint() for
 * post-style-wipe restoration, and version() feeding the gv request parameter.
 *
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {catmullRomSegments, parseGraphSamples} from "./uiLogic.js";

/**
 * Fallback width when #screenlog cannot be measured yet.
 * @type {number}
 */
const FALLBACK_WIDTH = 2000;

/**
 * Fallback height when #screenlog cannot be measured yet; close to the typical
 * rendered screen log box.
 * @type {number}
 */
const FALLBACK_HEIGHT = 160;

/**
 * Current drawing dimensions in CSS pixels, refreshed by measure().
 * @type {{w: number, h: number}}
 */
let view = {w: FALLBACK_WIDTH, h: FALLBACK_HEIGHT};

/**
 * Whether the resize hook has been installed.
 * @type {boolean}
 */
let resizeHooked = false;

/**
 * Reads the #screenlog box and updates the drawing dimensions. Zero-sized
 * measurements (element hidden during layout) keep the previous values.
 *
 * @function measure
 * @returns {void}
 */
function measure() {
  const el = document.getElementById("screenlog");
  if (!el) {return;}
  const w = el.clientWidth, h = el.clientHeight;
  if (w > 0 && h > 0) {view = {w: Math.round(w), h: Math.round(h)};}
}

/**
 * Installs the resize hook once: ResizeObserver on #screenlog when available so
 * container-driven layout changes recalc too, else a window resize listener.
 *
 * @function hookResize
 * @param {Function} redraw - draw() bound lazily to avoid init-order issues.
 * @returns {void}
 */
function hookResize(redraw) {
  if (resizeHooked) {return;}
  resizeHooked = true;
  const onResize = () => {measure(); redraw();};
  if (typeof ResizeObserver !== "undefined") {
    const el = document.getElementById("screenlog");
    if (el) {new ResizeObserver(onResize).observe(el); return;}
  }
  window.addEventListener("resize", onResize);
}

/**
 * Last sample version applied, as a string to match payload values verbatim.
 * @type {?string}
 */
let lastVersion = null;

/**
 * Parsed samples retained between draws: [{t, rx, tx}, ...] oldest-first.
 * @type {Object[]}
 */
let samples = [];

/**
 * Feeds new samples into the renderer. No-op unless the payload carries a sample
 * version newer than the last one applied.
 *
 * @function update
 * @param {?Object} payload - Refresh payload with graphVer/graphSamples fields.
 * @returns {void}
 */
export function update(payload) {
  if (!payload || payload.graphVer === null || payload.graphVer === undefined) {return;}
  if (payload.graphVer === lastVersion) {
    // Same version, but the property may have been wiped by a style reset;
    // self-heal instead of waiting for the next sample boundary.
    const current = getComputedStyle(document.documentElement)
        .getPropertyValue("--snarkGraph").trim();
    if (current !== "") {return;}
  }
  if (payload.graphVer !== undefined && payload.graphVer !== null) {lastVersion = payload.graphVer;}
  if (typeof payload.graphSamples === "string" && payload.graphSamples.length > 0) {
    samples = parseGraphSamples(payload.graphSamples);
  }
  renderNow();
}

/**
 * Seeds state from the inline #snarkGraphData element that server-rendered pages
 * embed, so a freshly loaded (or hard-refreshed) page draws the graph immediately
 * instead of waiting for the first refresh poll. Called once at module init.
 *
 * @function seedFromDom
 * @returns {void}
 */
function seedFromDom() {
  if (typeof document === "undefined") {return;}
  const el = document.getElementById("snarkGraphData");
  if (!el) {return;}
  const v = el.getAttribute("data-v");
  const csv = el.getAttribute("data-samples");
  if (v !== null) {lastVersion = v;}
  if (typeof csv === "string" && csv.length > 0) {
    samples = parseGraphSamples(csv);
    renderNow();
  }
}

/**
 * @function version
 * @description The client's last applied sample version, sent as the "gv" request
 * parameter so the server can skip resending samples the page already has.
 * @returns {?string} Version string, or null before any data was seen.
 */
export function version() {return lastVersion;}

/**
 * @function repaint
 * @description Redraws from already-held samples without waiting for a new version.
 * Style-wiping code elsewhere (e.g. documentElement.removeAttribute("style")) deletes
 * the --snarkGraph custom property; callers invoke this right after such a wipe so
 * the graph reappears instantly instead of at the next sample boundary.
 *
 * @returns {void}
 */
export function repaint() {
  if (lastVersion !== null) {renderNow();}
}

seedFromDom();

/**
 * Full render pass: re-measure the container, ensure the resize hook exists,
 * then draw. All entry points go through this so dimension changes (themes,
 * layout, window resizes) are picked up on every render.
 *
 * @function renderNow
 * @returns {void}
 */
function renderNow() {
  measure();
  hookResize(renderNow);
  draw();
}

/**
 * Renders the samples as SVG paths and publishes the document as the --snarkGraph
 * background. Layout mirrors the console sidebar minigraph: a shared center zero
 * axis with DOWNLOAD filling the bottom half (growing downward) and UPLOAD mirrored
 * into the top half; edges are Catmull-Rom smoothed. Always publishes: claiming the
 * variable immediately keeps themes from falling back to stale or broken URL layers.
 *
 * @function draw
 * @returns {void}
 */
function draw() {
  if (typeof document === "undefined") {return;}
  const style = getComputedStyle(document.documentElement);
  // Fallback palette; themes may override any of the per-direction vars.
  // Tension 1 (control offsets /6) yields the gentle, quality curve; the harder
  // miniGraph tension 0.5 overshoots and then flattens against the clamps.
  const downFill = style.getPropertyValue("--graphDownFill") || "#0bf7";
  const downStroke = style.getPropertyValue("--graphDownStroke") || "#0bfd";
  const upFill = style.getPropertyValue("--graphUpFill") || "#5d76";
  const upStroke = style.getPropertyValue("--graphUpStroke") || "#5d7d";
  const strokeWidth = parseFloat(style.getPropertyValue("--graphStrokeWidth")) || 1;

  let max = 1;
  for (const s of samples) {
    if (s.rx > max) {max = s.rx;}
    if (s.tx > max) {max = s.tx;}
  }

  const stepX = view.w > 1 && samples.length > 1 ? view.w / (samples.length - 1) : view.w;
  // Dual-baseline layout: a horizontal zero axis at mid-height with OUTBOUND
  // (tx) filling the top half and INBOUND (rx) the bottom half. Each series is
  // capped at its baseline — values below zero clamp to zero and spline
  // overshoot is bounded — so the two graphs can never cross or overlap.
  const centerY = view.h / 2;
  const scaleY = (value) => Math.max(value / max * (view.h / 2 - 2), 0);
  const downPts = samples.map((s, i) => ({
    x: i * stepX,
    y: Math.min(centerY + scaleY(s.rx), view.h)
  }));
  const upPts = samples.map((s, i) => ({
    x: i * stepX,
    y: Math.max(centerY - scaleY(s.tx), 0)
  }));

  // Edges are Catmull-Rom smoothed like the console miniGraph, with Y clamped
  // to each half so curves never cross the zero axis; fills reuse the same
  // curve closed along the baseline.
  const firstDown = `${downPts[0].x.toFixed(1)},${downPts[0].y.toFixed(1)}`;
  const firstUp = `${upPts[0].x.toFixed(1)},${upPts[0].y.toFixed(1)}`;
  const lastDown = downPts[downPts.length - 1];
  const lastUp = upPts[upPts.length - 1];
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' width='${view.w}' height='${view.h}' viewBox='0 0 ${view.w} ${view.h}'>` +
    (samples.length > 1
      ? `<path d='M ${downPts[0].x.toFixed(1)},${centerY} L ${firstDown} ${catmullRomSegments(downPts, 1, {minY: centerY, maxY: view.h})} L ${lastDown.x.toFixed(1)},${centerY} Z' fill='${downFill}'/>` +
        `<path d='M ${firstDown}${catmullRomSegments(downPts, 1, {minY: centerY, maxY: view.h})}' fill='none' stroke='${downStroke}' stroke-width='${strokeWidth}' stroke-linecap='round' stroke-linejoin='round'/>` +
        `<path d='M ${upPts[0].x.toFixed(1)},${centerY} L ${firstUp} ${catmullRomSegments(upPts, 1, {minY: 0, maxY: centerY})} L ${lastUp.x.toFixed(1)},${centerY} Z' fill='${upFill}'/>` +
        `<path d='M ${firstUp}${catmullRomSegments(upPts, 1, {minY: 0, maxY: centerY})}' fill='none' stroke='${upStroke}' stroke-width='${strokeWidth}' stroke-linecap='round' stroke-linejoin='round'/>`
      : "") +
    `</svg>`;

  document.documentElement.style.setProperty(
      "--snarkGraph", `url("data:image/svg+xml,${encodeURIComponent(svg)}")`);
}
