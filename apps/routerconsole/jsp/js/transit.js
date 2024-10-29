/* I2P+ transit.js by dr|z3d */
/* Handle automatic and manual refresh for /transit */
/* License: AGPL3 or later */

import {onVisible} from "/js/onVisible.js";

const main = document.getElementById("tunnels");
const peers = document.getElementById("transitPeers");
const tunnels = document.getElementById("allTransit");
const refresh = document.getElementById("refreshPage");
const visible = document.visibilityState;
const xhrtunnels = new XMLHttpRequest();
const REFRESH_INTERVAL = 60*1000;
let refreshId;
let sorter = null;

if (tunnels) {const sorter = new Tablesort(tunnels, {descending: true});}

function updateNotes() {
  const notes = document.querySelectorAll(".statusnotes");
  if (notes[0] !== null) {
    const notesResponse = xhrtunnels.responseXML.querySelectorAll(".statusnotes");
    for (let i = 0; i < notes.length; i++) {
      if (notesResponse[i] !== null) {
        notes[i].innerHTML = notesResponse[i].innerHTML;
      }
    }
  }
}

function initRefresh() {
  if (refreshId) {clearInterval(refreshId);}
  refreshId = setInterval(updateTunnels, REFRESH_INTERVAL);
  if (tunnels && sorter === null) {
    const sorter = new Tablesort(tunnels, {descending: true});
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
    tunnels.addEventListener("beforeSort", () => {
      progressx.show(theme);
      progressx.progress(0.5);
    });
    tunnels.addEventListener("afterSort", () => progressx.hide());
  }
}

function updateTunnels() {
  xhrtunnels.open("GET", "/transit", true);
  xhrtunnels.responseType = "document";
  xhrtunnels.onreadystatechange = function () {
    if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
      const mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
      const peersResponse = xhrtunnels.responseXML.getElementById("transitPeers");
      if (peersResponse) {
        addSortListeners();
        if (peers && peers !== peersResponse) {
          peers.innerHTML = peersResponse.innerHTML;
          updateNotes();
          if (sorter) {sorter.refresh();}
          removeHref();
        }
      } else if (!tunnels || !peersResponse) {main.innerHTML = mainResponse.innerHTML;}
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