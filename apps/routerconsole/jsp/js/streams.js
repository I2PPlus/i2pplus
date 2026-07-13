/**
 * @module streams
 * @description Initializes streaming connection tables with sortable columns
 * and periodic auto-refresh. Respects the current direction query parameter
 * (?direction=inbound|outbound) so filtered views stay in sync.
 *
 * Initially refreshes #streamsWrap so the "not available" placeholder is
 * replaced by the live table(s) once the router finishes starting. After
 * tables appear, switches to refreshing just table.streams tbody to preserve
 * the Tablesort instances.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from '/js/refreshElements.js';

let tablesortInitialized = false;
let currentRefreshTarget = "#streamsWrap";
const baseUrl = "/streams" + (window.location.search || "");

/**
 * Attach Tablesort to each .streams table if not yet initialized.
 * Returns true if at least one table was found.
 */
function initTablesort() {
  if (tablesortInitialized) return true;
  const tables = document.querySelectorAll("table.streams");
  if (tables.length > 0) {
    tables.forEach(table => {
      new Tablesort(table);
      table.addEventListener("beforeSort", () => progressx?.show?.(theme));
      table.addEventListener("afterSort", () => progressx?.hide?.());
    });
    tablesortInitialized = true;
    return true;
  }
  return false;
}

/**
 * Switch refresh target to the table tbodies so morphdom diffs rows
 * without destroying Tablesort instances.
 */
function switchToBodyRefresh() {
  if (currentRefreshTarget === "table.streams tbody") return;
  currentRefreshTarget = "table.streams tbody";
  refreshElements(currentRefreshTarget, baseUrl, 10000);
}

/**
 * DOMContentLoaded handler — starts by refreshing the full wrapper
 * (handles the transition from "not available" to live table(s)),
 * then switches to tbody-only refresh once tables are present.
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
