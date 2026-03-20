/**
 * @module transitsummary
 * @description Handles automatic refresh and table sorting for the /transitsummary
 * page. Converts KB values to MB and refreshes transit peer/tunnel data.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {convertKBtoMB} from "/js/convertKBtoMB.js";
import {refreshElements} from "/js/refreshElements.js";

const main = document.getElementById("tunnels");
const peers = document.getElementById("transitPeers");
const summary = document.getElementById("transitSummary");
const REFRESH_INTERVAL = 10 * 1000;
let sorter = null;

/**
 * Initializes the Tablesort instance on the transit summary table.
 * @function setupSort
 * @returns {void}
 */
function setupSort() {
  if (summary && sorter === null) {
    sorter = new Tablesort(summary, {descending: true});
    const rows = summary.querySelectorAll("tbody tr");
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
  if (sorter === null) { setupSort(); }
  let selectors = ["#tunnels"];
  if (peers) { selectors = ["#transitPeers"]; }
  refreshElements(selectors, "/transitsummary", REFRESH_INTERVAL);
}

document.addEventListener("refreshComplete", () => {
  sorter?.refresh();
  convertKBtoMB(".tcount+td");
});

document.addEventListener("DOMContentLoaded", () => {
  updateTunnels();
});