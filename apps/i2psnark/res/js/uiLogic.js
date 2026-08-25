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
