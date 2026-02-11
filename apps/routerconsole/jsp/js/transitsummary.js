/* I2P+ transitsummary.js by dr|z3d */
/* Handle automatic refresh for /transitsummary */
/* License: AGPL3 or later */

import {convertKBtoMB} from "/js/convertKBtoMB.js";
import {refreshElements} from "/themes/js/refreshElements.js";
import Tablesort from "/js/tablesort/tablesort.js";
import "/js/tablesort/tablesort.number.js";

const main = document.getElementById("tunnels");
const peers = document.getElementById("transitPeers");
const summary = document.getElementById("transitSummary");
const REFRESH_INTERVAL = 10 * 1000;
let sorter = null;

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