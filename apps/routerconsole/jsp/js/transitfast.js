/* I2P+ transitfast.js by dr|z3d */
/* Handle automatic, manual refresh and tablesort for /transitfast */
/* License: AGPL3 or later */

import {onVisible} from "/js/onVisible.js";

(function() {
  const main = document.getElementById("tunnels");
  const peers = document.getElementById("transitPeers");
  const tunnels = document.getElementById("allTransit");
  const refresh = document.getElementById("refreshPage");
  const visible = document.visibilityState;
  const xhrtunnels = new XMLHttpRequest();
  const REFRESH_INTERVAL = 60*1000;
  let refreshId;
  let sorter;

  if (tunnels) {
    sorter = new Tablesort(tunnels, {descending: true});
  }

  function initRefresh() {
    if (refreshId) {
      clearInterval(refreshId);
    }
    refreshId = setInterval(updateTunnels, REFRESH_INTERVAL);
    if (tunnels && !sorter) {
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
      tunnels.addEventListener("beforeSort", () => progressx.show(theme));
      tunnels.addEventListener("afterSort", () => progressx.hide());
    }
  }

  function updateNotes() {
    const notes = document.querySelectorAll(".statusnotes");
    if (notes[0]) {
      const notesResponse = xhrtunnels.responseXML.querySelectorAll(".statusnotes");
      notes.forEach((note, i) => {
        if (notesResponse[i]) {note.innerHTML = notesResponse[i].innerHTML;}
      });
    }
  }

  function updateTunnels() {
    xhrtunnels.open("GET", "/transitfast", true);
    xhrtunnels.responseType = "document";
    xhrtunnels.onreadystatechange = function() {
      if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
        const mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
        const peersResponse = xhrtunnels.responseXML.getElementById("transitPeers");
        if (peersResponse) {
          addSortListeners();
          if (peers && peers !== peersResponse) {
            peers.innerHTML = peersResponse.innerHTML;
            updateNotes();
            sorter.refresh();
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

  onVisible(main, updateTunnels);

  if (visible === "hidden") {clearInterval(refreshId);}

  window.addEventListener("DOMContentLoaded", progressx.hide);
  document.addEventListener("DOMContentLoaded", initRefresh);
})();