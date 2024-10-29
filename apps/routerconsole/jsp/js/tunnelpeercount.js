/* I2P+ tunnelpeercount.js by dr|z3d */
/* Handle automatic and manual refresh for /tunnelcountpeer */
/* License: AGPL3 or later */

import {onVisible} from "/js/onVisible.js";

const footer = document.querySelector(".tablefooter");
const main = document.getElementById("tunnels");
const peers = document.getElementById("allPeers");
const refresh = document.getElementById("refreshPage");
const tunnels = document.getElementById("tunnelPeerCount");
const xhrtunnels = new XMLHttpRequest();
const visible = document.visibilityState;
let refreshId; // This variable is reassigned, so it remains a let
let sorter = null; // Initialize sorter at the top level

if (tunnels) {sorter = new Tablesort(tunnels, {descending: true});}

function initRefresh() {
  if (refreshId) {clearInterval(refreshId);}
  refreshId = setInterval(updateTunnels, 60000);
  if (tunnels && sorter === null) {
    sorter = new Tablesort(tunnels, {descending: true});
    removeHref();
  }
  addSortListeners();
  updateTunnels();
}

function removeHref() {
  if (refresh) {refresh.removeAttribute("href");}
}

function addSortListeners() {
  if (tunnels) {
    tunnels.addEventListener('beforeSort', () => {
      progressx.show(theme);
      progressx.progress(0.5);
    });
    tunnels.addEventListener('afterSort', () => progressx.hide());
  }
}

function updateTunnels() {
  xhrtunnels.open("GET", "/tunnelpeercount", true);
  xhrtunnels.responseType = "document";
  xhrtunnels.onload = function () {
    const mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
    const peersResponse = xhrtunnels.responseXML.getElementById("allPeers");
    const footerResponse = xhrtunnels.responseXML.querySelector(".tablefooter");
    if (peersResponse) {
      addSortListeners();
      if (peers && peers !== peersResponse) {
        peers.innerHTML = peersResponse.innerHTML;
        if (sorter) {sorter.refresh();}
        removeHref();
      }
      if (footer && footerResponse && footer !== footerResponse) {
        footer.innerHTML = footerResponse.innerHTML;
      }
    } else if ((!tunnels || !peersResponse) && mainResponse) {
      main.innerHTML = mainResponse.innerHTML;
    }
  }
  if (sorter) {sorter.refresh();}
  xhrtunnels.send();
}

if (refresh) {
  refresh.addEventListener("click", () => {
    progressx.show(theme);
    progressx.progress(0.5);
    updateTunnels();
    progressx.hide();
  });
  refresh.addEventListener("mouseenter", removeHref);
}

onVisible(main, () => updateTunnels());

if (visible === "hidden") {clearInterval(refreshId);}

window.addEventListener("DOMContentLoaded", () => progressx.hide());
document.addEventListener("DOMContentLoaded", initRefresh);