/**
 * @module streams
 * @description Initializes the streaming connections table with sortable columns
 * and periodic auto-refresh. Respects the current direction query parameter
 * (?direction=inbound|outbound) so filtered views stay in sync.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from '/js/refreshElements.js';

/**
 * DOMContentLoaded handler — sets up Tablesort on the streams table
 * and starts a 10-second refresh cycle for live connection updates.
 */
document.addEventListener("DOMContentLoaded", function() {
  const table = document.querySelector("table#streams");
  if (table) {
    new Tablesort(table);
    table.addEventListener("beforeSort", () => progressx?.show?.(theme));
    table.addEventListener("afterSort", () => progressx?.hide?.());
  }
  refreshElements("#streams tbody", "/streams" + (window.location.search || ""), 10000);
});