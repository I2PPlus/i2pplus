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
