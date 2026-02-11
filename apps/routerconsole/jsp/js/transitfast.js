/* I2P+ transitfast.js by dr|z3d */
/* Handle automatic, manual refresh and tablesort for /transitfast */
/* License: AGPL3 or later */

import {refreshElements} from "/js/refreshElements.js";
import Tablesort from "/js/tablesort/tablesort.js";
import "/js/tablesort/tablesort.number.js";

(function() {
  const REFRESH_INTERVAL = 10*1000;
  const RETRY_DELAY = 3000;
  let main, peers, tunnels, refreshBtn;
  let sorter = null;
  let isSetup = false;
  let isRefreshing = false;

  function getDOM() {
    if (main) return;
    main = document.getElementById("tunnels");
    peers = document.getElementById("transitPeers");
    tunnels = document.getElementById("allTransit");
    refreshBtn = document.getElementById("refreshPage");
  }

  function setupTablesort() {
    if (isSetup || !tunnels) return;
    sorter = sorter || new Tablesort(tunnels, {descending: true});
    tunnels.addEventListener("beforeSort", () => progressx.show(theme));
    tunnels.addEventListener("afterSort", () => progressx.hide());
    if (refreshBtn) refreshBtn.removeAttribute("href");
    isSetup = true;
  }

  function startRefresh() {
    if (isRefreshing) return;
    isRefreshing = true;
    requestAnimationFrame(() => progressx?.show?.(theme));
  }

  function endRefresh() {
    requestAnimationFrame(() => progressx?.hide?.());
    isRefreshing = false;
  }

  function refreshData() {
    startRefresh();
    getDOM();
    const notes = document.querySelectorAll(".statusnotes");
    if (peers && notes.length) {
      setupTablesort();
      refreshElements(".statusnotes, #transitPeers", "/transitfast", REFRESH_INTERVAL);
    } else if (main) { refreshElements("#tunnels", "/transitfast", RETRY_DELAY); }
    endRefresh();
  }

  function init(retryCount = 0) {
    getDOM();
    if (!refreshBtn) {
      if (retryCount < 30) setTimeout(() => init(retryCount + 1), RETRY_DELAY);
      return;
    }
    refreshBtn.addEventListener("click", (e) => { e.preventDefault(); refreshData(); });
    refreshData();
  }

  document.addEventListener("refreshComplete", () => { if (sorter) sorter.refresh(); });

  if (document.readyState === "loading") { document.addEventListener("DOMContentLoaded", () => init()); }
  else { init(); }
})();