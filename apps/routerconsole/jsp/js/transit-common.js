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
 * @param {boolean} [config.usePeerRowTracking=false] - Optimize refresh by peer row count
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
  let lastPeerRowCount = -1;
  let stopRefresh = null;

  /** Caches DOM element references. */
  function getDOM() {
    if (main) return;
    main = document.getElementById("tunnels");
    peers = document.getElementById("transitPeers");
    tunnels = document.getElementById("allTransit");
    refreshBtn = document.getElementById("refreshPage");
  }

  /** Initializes Tablesort on the transit table. */
  function setupTablesort() {
    if (isSetup || !tunnels) return;
    sorter = sorter || new Tablesort(tunnels, {descending: true});
    tunnels.addEventListener("beforeSort", () => progressx?.show?.(theme));
    tunnels.addEventListener("afterSort", () => progressx?.hide?.());
    if (refreshBtn) refreshBtn.removeAttribute("href");
    isSetup = true;
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
    if (tunnels) {
      setupTablesort();
      if (peers) {
        if (config.usePeerRowTracking) {
          const currentRows = peers.querySelectorAll("tr").length;
          if (currentRows === lastPeerRowCount && currentRows > 0) {
            stop = refreshElements("#transitPeers td>*, #statusnotes td", FETCH_URL, REFRESH_INTERVAL, immediate);
          } else if (currentRows !== lastPeerRowCount) {
            stop = refreshElements("#transitPeers, #statusnotes td", FETCH_URL, REFRESH_INTERVAL, immediate);
            lastPeerRowCount = currentRows;
          } else {
            stop = refreshElements("#tunnels", FETCH_URL, RETRY_DELAY, immediate);
          }
        } else {
          stop = refreshElements("#statusnotes td, #transitPeers", FETCH_URL, REFRESH_INTERVAL, immediate);
        }
      } else if (main) {
        stop = refreshElements("#tunnels", FETCH_URL, RETRY_DELAY, immediate);
      }
    }
    stopRefresh = stop;
    endRefresh();
  }

  /**
   * Retries DOM lookup until the refresh button is found, then starts the
   * refresh cycle.
   * @param {number} [retryCount=0] - Current retry attempt
   */
  function init(retryCount = 0) {
    getDOM();
    if (!refreshBtn) {
      if (retryCount < 30) { setTimeout(() => init(retryCount + 1), RETRY_DELAY); }
      return;
    }
    refreshBtn.addEventListener("click", (e) => {
      e.preventDefault();
      refreshData(true);
    });
    refreshData();
  }

  document.addEventListener("refreshComplete", () => {
    if (sorter) sorter.refresh();
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => init());
  } else {
    init();
  }
}
