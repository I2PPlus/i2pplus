/**
 * @module transit-common
 * @description Shared transit page logic for both /transit and /transitfast.
 * Provides auto-refresh, table sorting, and progress bar integration.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {refreshElements} from "/js/refreshElements.js";

/**
 * Initializes a transit-style page with auto-refresh and tablesort.
 * @function initTransit
 * @param {Object} config
 * @param {string} config.fetchUrl - URL to fetch updates from
 * @param {number} [config.refreshInterval=10000] - Refresh interval in ms
 * @param {number} [config.retryDelay=3000] - Retry delay when no DOM found
 * @returns {void}
 */
export function initTransit(config) {
  const REFRESH_INTERVAL = config.refreshInterval || 10000;
  const RETRY_DELAY = config.retryDelay || 3000;
  const FETCH_URL = config.fetchUrl;

  let main, peers, tunnels, refreshBtn;
  let sorter = null;
  let isSetup = false;
  let isRefreshing = false;
  let stopRefresh = null;

  /** Caches DOM element references. */
  function getDOM() {
    if (main) return;
    main = document.getElementById("tunnels");
    peers = document.getElementById("transitPeers");
    tunnels = document.getElementById("allTransit");
    refreshBtn = document.getElementById("refreshPage");
  }

  /** Binds the refresh link to trigger an immediate refresh. */
  function bindRefreshBtn() {
    if (!refreshBtn || refreshBtn.dataset.bound === "1") { return; }
    refreshBtn.dataset.bound = "1";
    refreshBtn.addEventListener("click", (e) => {
      e.preventDefault();
      refreshData(true);
    });
  }

  /** Initializes Tablesort on the transit table. */
  function setupTablesort() {
    if (isSetup || !tunnels) return;
    sorter = sorter || new Tablesort(tunnels, {descending: true});
    tunnels.addEventListener("beforeSort", () => progressx?.show?.(theme));
    tunnels.addEventListener("afterSort", () => progressx?.hide?.());
    isSetup = true;
  }

  /** Re-caches DOM refs and Tablesort after a whole-div refresh replaced the table. */
  function revalidate() {
    sorter = null;
    isSetup = false;
    main = peers = tunnels = refreshBtn = null;
    getDOM();
    bindRefreshBtn();
    setupTablesort();
  }

  /** Shows the progress bar. */
  function startRefresh() {
    if (isRefreshing) return;
    isRefreshing = true;
    requestAnimationFrame(() => progressx?.show?.(theme));
  }

  /** Hides the progress bar. */
  function endRefresh() {
    requestAnimationFrame(() => progressx?.hide?.());
    isRefreshing = false;
  }

  /** Fetches fresh transit data via SharedWorker and replaces matching DOM elements. */
  function refreshData(immediate = false) {
    startRefresh();
    getDOM();
    if (stopRefresh) { stopRefresh(); stopRefresh = null; }
    let stop = null;
    if (peers) {
      stop = refreshElements("#transitPeers, #statusnotes", FETCH_URL, REFRESH_INTERVAL, immediate);
    } else if (main) {
      // No tunnels yet: poll the whole div until the table appears
      stop = refreshElements("#tunnels", FETCH_URL, RETRY_DELAY, immediate);
    }
    setupTablesort();
    stopRefresh = stop;
    endRefresh();
  }

  /** Starts the refresh cycle. */
  function init() {
    getDOM();
    bindRefreshBtn();
    refreshData();
  }

  document.addEventListener("refreshComplete", () => {
    if (sorter) sorter.refresh();
  });

  // A whole-div refresh replaces the table, invalidating cached refs and the
  // Tablesort instance; re-cache and switch back to the tbody loop once present
  document.addEventListener("elementsRefreshed", (e) => {
    const selectors = e.detail?.selectors || [];
    if (!selectors.includes("#tunnels")) { return; }
    revalidate();
    if (peers && stopRefresh) {
      stopRefresh();
      stopRefresh = null;
      refreshData();
    }
  });

  // The server stopped rendering the table; drop the stale tbody ref and fall
  // back to the whole-div loop until tunnels are available again
  document.addEventListener("elementsMissing", (e) => {
    const selectors = e.detail?.selectors || [];
    if (!selectors.includes("#transitPeers")) { return; }
    if (stopRefresh) { stopRefresh(); stopRefresh = null; }
    peers = null;
    refreshData();
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => init());
  } else {
    init();
  }
}
