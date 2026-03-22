/**
 * @module transitfast
 * @description Handles automatic and manual refresh with table sorting for the
 * /transitfast page. Similar to transit.js but for fast transit tunnels.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {refreshElements} from "/js/refreshElements.js";

(function() {
  const REFRESH_INTERVAL = 10*1000;
  const RETRY_DELAY = 3000;
  let main, peers, tunnels, refreshBtn;
  let sorter = null;
  let isSetup = false;
  let isRefreshing = false;

  /**
   * Caches references to frequently used DOM elements.
   * @function getDOM
   * @returns {void}
   */
  function getDOM() {
    if (main) return;
    main = document.getElementById("tunnels");
    peers = document.getElementById("transitPeers");
    tunnels = document.getElementById("allTransit");
    refreshBtn = document.getElementById("refreshPage");
  }

  /**
   * Initializes the Tablesort instance on the transit tunnels table.
   * @function setupTablesort
   * @returns {void}
   */
  function setupTablesort() {
    if (isSetup || !tunnels) return;
    sorter = sorter || new Tablesort(tunnels, {descending: true});
    tunnels.addEventListener("beforeSort", () => progressx.show(theme));
    tunnels.addEventListener("afterSort", () => progressx.hide());
    if (refreshBtn) refreshBtn.removeAttribute("href");
    isSetup = true;
  }

  /**
   * Shows the progress bar and marks refresh as active.
   * @function startRefresh
   * @returns {void}
   */
  function startRefresh() {
    if (isRefreshing) return;
    isRefreshing = true;
    requestAnimationFrame(() => progressx?.show?.(theme));
  }

  /**
   * Hides the progress bar and marks refresh as complete.
   * @function endRefresh
   * @returns {void}
   */
  function endRefresh() {
    requestAnimationFrame(() => progressx?.hide?.());
    isRefreshing = false;
  }

  /**
   * Initiates a data refresh, selecting appropriate elements based on current state.
   * @function refreshData
   * @returns {void}
   */
  function refreshData() {
    startRefresh();
    getDOM();
    if (tunnels) {
      setupTablesort();
      if (peers) {
        refreshElements("#statusnotes, #transitPeers", "/transitfast", REFRESH_INTERVAL);
      } else if (main) {
        refreshElements("#tunnels", "/transitfast", RETRY_DELAY);
      }
    }
    endRefresh();
  }

  /**
   * Initializes the transit fast page with retry logic for missing DOM elements.
   * @function init
   * @param {number} [retryCount=0] - Current retry attempt count
   * @returns {void}
   */
  function init(retryCount = 0) {
    getDOM();
    if (!refreshBtn) {
      if (retryCount < 30) { setTimeout(() => init(retryCount + 1), RETRY_DELAY); }
      return;
    }
    refreshBtn.addEventListener("click", (e) => { e.preventDefault(); refreshData(); });
    refreshData();
  }

  document.addEventListener("refreshComplete", () => { if (sorter) sorter.refresh(); });

  if (document.readyState === "loading") { document.addEventListener("DOMContentLoaded", () => init()); }
  else { init(); }
})();