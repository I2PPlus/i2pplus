/**
 * @module streams
 * @description Initializes the streaming connections table with sortable columns
 * and periodic auto-refresh. Respects the current direction query parameter
 * (?direction=inbound|outbound) so filtered views stay in sync.
 *
 * Initially refreshes the full #streamsContainer so the "not available"
 * placeholder is replaced by the live table once the router finishes starting.
 * After the table appears, switches to refreshing just #streams tbody to
 * preserve the Tablesort instance.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from '/js/refreshElements.js';

let tablesortInitialized = false;
let currentRefreshTarget = "#streamsContainer";
const baseUrl = "/streams" + (window.location.search || "");

/**
 * Attach Tablesort to the streams table if present and not yet initialized.
 * Returns true if the table exists.
 */
function initTablesort() {
  if (tablesortInitialized) return true;
  const table = document.querySelector("table#streams");
  if (table) {
    new Tablesort(table);
    table.addEventListener("beforeSort", () => progressx?.show?.(theme));
    table.addEventListener("afterSort", () => progressx?.hide?.());
    tablesortInitialized = true;
    return true;
  }
  return false;
}

/**
 * Switch refresh target from the full container to just the tbody,
 * so morphdom diffs rows without destroying the Tablesort instance.
 */
function switchToBodyRefresh() {
  if (currentRefreshTarget === "#streams tbody") return;
  currentRefreshTarget = "#streams tbody";
  refreshElements(currentRefreshTarget, baseUrl, 10000);
}

/**
 * DOMContentLoaded handler — starts by refreshing the full container
 * (handles the transition from "not available" to the live table),
 * then switches to tbody-only refresh once the table is present.
 */
document.addEventListener("DOMContentLoaded", function() {
  initTablesort();
  refreshElements(currentRefreshTarget, baseUrl, 10000);

  document.addEventListener("elementsRefreshed", function() {
    if (initTablesort()) {
      switchToBodyRefresh();
    }
  });
});
