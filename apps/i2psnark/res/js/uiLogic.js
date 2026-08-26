/**
 * @module uiLogic
 * @file uiLogic.js - Pure decision helpers shared by i2psnark UI modules.
 * @description DOM-free predicates and formatters extracted from UI modules so they
 * can be unit-tested under Node (tools/test/js/pure) without a DOM
 * implementation. Nothing here may touch document/window, at module scope or
 * inside any function, and nothing here may import a module that does.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Decides what to do with the transient {@code <span class=loading>} indicator in a
 * torrent action cell. The span marks rows whose action buttons are absent while a
 * start/restart is in flight; it is dropped again once the buttons come back.
 *
 * @param {boolean} hasSubmitButton - whether the action cell currently renders action buttons
 * @param {boolean} hasLoadingSpan - whether the transient loading span is currently present
 * @returns {"add"|"keep"|"remove"|"none"} the mutation to apply; "none" is a no-op
 * @since 0.9.71+
 */
export function loadingSpanDecision(hasSubmitButton, hasLoadingSpan) {
  if (hasSubmitButton) {return hasLoadingSpan ? "remove" : "none";}
  return hasLoadingSpan ? "keep" : "add";
}

/**
 * Resolves the active filter bar target from URL parameters. The realtime search
 * parameter wins over the status filter; absence of both means "all". An empty
 * {@code search} value ({@code ?search=}) still selects the search filter, matching
 * the historical {@code !== null} check.
 *
 * @param {{get: function(string): ?string}} params - URLSearchParams-like accessor
 * @returns {string} the filter element id: "search", a status filter id, or "all"
 * @since 0.9.71+
 */
export function resolveFilterId(params) {
  if (params.get("search") !== null) {return "search";}
  return params.get("filter") || "all";
}

/**
 * Formats native title text into the multi-line form styled tooltips expect:
 * bullet-separated segments are rewritten one per line. The split is intentionally
 * greedy so it lands on the LAST bullet, byte-for-byte matching the original inline
 * regex {@code /^(.*)\s•\s(.*)$/} this helper replaced; tests pin that behaviour.
 *
 * @param {string} text - raw title attribute text
 * @returns {string} formatted tooltip text
 * @since 0.9.71+
 */
export function formatTooltipText(text) {
  return text.replace(/^(.*)\s•\s(.*)$/, '• $1\n• $2');
}

/**
 * Per-element memory of the last HTML this helper wrote, so change detection never
 * has to re-serialize a live subtree via the innerHTML getter. WeakMap keys keep
 * detached elements collectable.
 */
const lastAppliedHTML = new WeakMap();

/**
 * Writes {@code html} into {@code element} only when it differs from what was last
 * applied here, returning whether the DOM was actually touched. Callers feed this
 * from refresh payloads to keep volatile cells in sync without paying an
 * innerHTML-serialization cost on every no-op tick.
 *
 * Contract: once an element is managed by this helper, its content should only
 * change through it (or by wholesale replacement of the element); divergent external
 * edits are deliberately not detected, because detecting them is exactly the
 * serialization cost this exists to avoid.
 *
 * @param {Element} element - target element; null/undefined is a safe no-op
 * @param {string} html - desired inner HTML
 * @returns {boolean} whether an innerHTML assignment was performed
 * @since 0.9.71+
 */
export function applyIfChanged(element, html) {
  if (!element) {return false;}
  if (lastAppliedHTML.get(element) === html) {return false;}
  const changed = element.innerHTML !== html;
  if (changed) {element.innerHTML = html;}
  lastAppliedHTML.set(element, html);
  return changed;
}

/**
 * Parses the bandwidth graph wire format "tsSec,rxBytes,txBytes;..." into sample
 * objects, oldest-first. Tolerates trailing separators and drops malformed entries;
 * non-finite numbers are rejected so a corrupted payload can never poison the
 * renderer's scale.
 *
 * @param {?string} csv - raw sample list from a refresh payload
 * @returns {{t: number, rx: number, tx: number}[]} samples oldest-first; empty on null input
 * @since 0.9.71+
 */
export function parseGraphSamples(csv) {
  if (!csv) {return [];}
  const out = [];
  for (const entry of csv.split(";")) {
    const parts = entry.split(",");
    if (parts.length !== 3) {continue;}
    const t = Number(parts[0]);
    const rx = Number(parts[1]);
    const tx = Number(parts[2]);
    if (!Number.isFinite(t) || !Number.isFinite(rx) || !Number.isFinite(tx)) {continue;}
    out.push({t, rx, tx});
  }
  return out;
}

/**
 * Builds SVG cubic-Bézier path segments through {@code points} using a
 * Catmull-Rom spline, matching the console miniGraph renderer's smoothing
 * (control points offset by (p2-p0)/(6*tension)); endpoint neighbors are
 * clamped to the first/last points. Returns only the segment commands — callers
 * prepend their own "M x,y".
 *
 * When {@code bounds} is supplied, every emitted Y coordinate (anchors and
 * control points) is clamped into it, so spline overshoot can never escape the
 * drawing area or cross a baseline.
 *
 * @param {{x: number, y: number}[]} points - polyline vertices, at least two
 * @param {number} [tension=0.5] - Catmull-Rom tension (0.5 matches miniGraph)
 * @param {{minY?: number, maxY?: number}} [bounds] - optional Y clamp
 * @returns {string} SVG path segment commands, empty string when fewer than two points
 * @since 0.9.71+
 */
export function catmullRomSegments(points, tension = 0.5, bounds) {
  const n = points ? points.length : 0;
  if (n < 2) {return "";}
  const clampY = (y) => {
    if (!bounds) {return y;}
    let v = y;
    if (bounds.minY !== undefined && bounds.minY !== null) {v = Math.max(v, bounds.minY);}
    if (bounds.maxY !== undefined && bounds.maxY !== null) {v = Math.min(v, bounds.maxY);}
    return v;
  };
  const fmtX = (x) => x.toFixed(1);
  const fmtY = (y) => clampY(y).toFixed(1);
  let d = "";
  for (let i = 0; i < n - 1; i++) {
    const p0 = i > 0 ? points[i - 1] : points[0];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = i + 2 < n ? points[i + 2] : points[n - 1];
    const c1x = (p1.x + ((p2.x - p0.x) / (6 * tension))).toFixed(1);
    const c1y = fmtY(p1.y + ((p2.y - p0.y) / (6 * tension)));
    const c2x = (p2.x - ((p3.x - p1.x) / (6 * tension))).toFixed(1);
    const c2y = fmtY(p2.y - ((p3.y - p1.y) / (6 * tension)));
    if (i > 0) {d += " ";}
    d += `C ${c1x},${c1y} ${c2x},${c2y} ${fmtX(p2.x)},${fmtY(p2.y)}`;
  }
  return d;
}
