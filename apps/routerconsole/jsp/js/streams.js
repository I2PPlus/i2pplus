/**
 * @module streams
 * @description Initializes streaming connection tables with sortable columns
 * and periodic auto-refresh. Respects the current direction query parameter
 * (?direction=inbound|outbound) so filtered views stay in sync.
 *
 * Initially refreshes #streamsWrap so the "not available" placeholder is
 * replaced by the live table(s) once the router finishes starting. After
 * tables appear, switches to refreshing table.streams tbody and #streamstats
 * separately so morphdom diffs rows without destroying Tablesort instances
 * while keeping the ring stats live.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from "/js/refreshElements.js";

let sorters = [];
let currentRefreshTarget = "#streamsWrap, #streamstats";
const baseUrl = "/streams" + (window.location.search || "");

/**
 * Attach Tablesort to each .streams table. Safe to call repeatedly —
 * returns false if no tables found yet.
 */
function initTablesort() {
  const tables = document.querySelectorAll("table.streams");
  if (tables.length > 0) {
    sorters = [];
    tables.forEach(table => {
      const sorter = new Tablesort(table);
      sorters.push(sorter);
      table.addEventListener("beforeSort", () => progressx?.show?.(theme));
      table.addEventListener("afterSort", () => progressx?.hide?.());
    });
    return true;
  }
  return false;
}

/**
 * Switch refresh target to tbody + streamstats so morphdom preserves
 * the thead (and hence Tablesort instances) while ring stats stay live.
 */
function switchToBodyRefresh() {
  if (currentRefreshTarget === "table.streams tbody, #streamstats") return;
  currentRefreshTarget = "table.streams tbody, #streamstats";
  refreshElements(currentRefreshTarget, baseUrl, 10000);
}

/**
 * DOMContentLoaded handler — starts by refreshing the full wrapper
 * (handles the transition from "not available" to live table(s)),
 * then switches to targeted refresh once tables are present.
 */
document.addEventListener("DOMContentLoaded", function() {
  initTablesort();
  refreshElements(currentRefreshTarget, baseUrl, 10000);

  document.addEventListener("elementsRefreshed", function() {
    if (initTablesort()) {
      switchToBodyRefresh();
    }
    // Re-apply current sort so it survives the morph
    sorters.forEach(s => s.refresh());
  });
});
