/* I2P+ transitsummary.js by dr|z3d */
/* Handle automatic refresh for /transitsummary */
/* License: AGPL3 or later */

import {onVisible} from "/js/onVisible.js";

const main = document.getElementById("tunnels");
const peers = document.getElementById("transitPeers");
const summary = document.getElementById("transitSummary");
const visible = document.visibilityState;
const xhrtunnels = new XMLHttpRequest();
const REFRESH_INTERVAL = 15 * 1000;
let refreshId;
let sorter = null;

if (summary) {sorter = new Tablesort(summary, {descending: true});}

function initRefresh() {
  if (refreshId) {clearInterval(refreshId);}
  refreshId = setInterval(updateTunnels, REFRESH_INTERVAL);
  if (summary && sorter === null) {
    sorter = new Tablesort(summary, {descending: true});
  }
  addSortListeners();
  updateTunnels();
}

function addSortListeners() {
  if (summary) {
    summary.addEventListener("beforeSort", () => {
      progressx.show(theme);
      convertKBtoMB(".tcount+td");
    });
    summary.addEventListener("afterSort", () => progressx.hide());
  }
}

function updateTunnels() {
  xhrtunnels.open("GET", "/transitsummary", true);
  xhrtunnels.responseType = "document";
  xhrtunnels.onreadystatechange = function () {
    if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
      const mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
      const peersResponse = xhrtunnels.responseXML.getElementById("transitPeers");
      if (peersResponse) {
        addSortListeners();
        if (peers && peers !== peersResponse) {
          peers.innerHTML = peersResponse.innerHTML;
          if (sorter) {sorter.refresh();}
        }
      } else if (!summary || !peersResponse) {
        main.innerHTML = mainResponse.innerHTML;
      }
    }
  }
  if (sorter) {sorter.refresh();}
  xhrtunnels.send();
}

onVisible(main, () => updateTunnels());

if (visible === "hidden") {clearInterval(refreshId);}

document.addEventListener("DOMContentLoaded", () => {
  initRefresh();
  convertKBtoMB(".tcount+td");
  progressx.hide();
});