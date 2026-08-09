/**
 * @module transitsummary
 * @description Handles automatic refresh and table sorting for the /transitsummary
 * page. Converts KB values to MB and refreshes transit peer/tunnel data.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {convertKBtoMB} from "/js/convertKBtoMB.js";
import {refreshElements} from "/js/refreshElements.js";

const REFRESH_INTERVAL = 10 * 1000;
let peers = document.getElementById("transitPeers");
let summary = document.getElementById("transitSummary");
let stopRefresh = null;
let sorter = null;

/**
 * Initializes the Tablesort instance on the transit summary table.
 * @function setupSort
 * @returns {void}
 */
function setupSort() {
  if (summary && sorter === null) {
    sorter = new Tablesort(summary, {descending: true});
    summary.addEventListener("beforeSort", () => {
      progressx.show(theme);
    });
    summary.addEventListener("afterSort", () => {
      progressx.hide();
    });
  }
}

/**
   * Triggers an element refresh for transit tunnel and peer data.
   * @function updateTunnels
   * @returns {void}
   */
function updateTunnels() {
  if (stopRefresh) { stopRefresh(); stopRefresh = null; }
  if (sorter === null) { setupSort(); }
  let selectors = ["#tunnels"];
  if (peers) { selectors = ["#transitPeers"]; }
  stopRefresh = refreshElements(selectors, "/transitsummary", REFRESH_INTERVAL);
}

document.addEventListener("refreshComplete", () => {
  sorter?.refresh();
  convertKBtoMB(".tcount+td");
});

// A whole-div refresh replaces the table, invalidating the cached refs and
// the Tablesort instance, so re-query them after such a patch
document.addEventListener("elementsRefreshed", (e) => {
  const selectors = e.detail?.selectors || [];
  if (!selectors.includes("#tunnels")) { return; }
  sorter = null;
  peers = document.getElementById("transitPeers");
  summary = document.getElementById("transitSummary");
  setupSort();
  if (peers && stopRefresh) {
    stopRefresh();
    stopRefresh = null;
    updateTunnels();
  }
});

// The server stopped rendering the peer table; fall back to the whole-div
// loop until peers are available again
document.addEventListener("elementsMissing", (e) => {
  const selectors = e.detail?.selectors || [];
  if (!selectors.includes("#transitPeers")) { return; }
  if (stopRefresh) { stopRefresh(); stopRefresh = null; }
  peers = null;
  updateTunnels();
});

document.addEventListener("DOMContentLoaded", () => {
  updateTunnels();
});