/**
 * @module tunnelpeercount
 * @description Handles automatic and manual refresh with table sorting and
 * advanced filtering for the /tunnelpeercount page. Supports key=value filter
 * syntax (tier=, cc=, ip=, hash=, etc.) with URL and localStorage persistence.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from "/js/refreshElements.js";

const d = document;
const header = d.getElementById("peercount");
const peers = d.getElementById("allPeers");
const refreshButton = d.getElementById("refreshPage");
const tunnels = d.getElementById("tunnelPeerCount");
const footer = tunnels?.querySelector(".tablefooter");
const REFRESH_INTERVAL = 10 * 1000;
const REFRESH_INTERVAL_SHORT = 5 * 1000;
const sorter = tunnels ? new Tablesort(tunnels, { descending: true }) : null;

let debugging = false;
let refreshId;
let filterListener = false;
let displayed = 0;
let debounceTimeout;

/**
 * Refreshes tunnel peer data elements via the refreshElements system.
 * @async
 * @function updateTunnels
 * @returns {Promise<void>}
 */
const updateTunnels = async () => {
  if (peers) {
    refreshElements(["#allPeers", ".tablefooter"], "/tunnelpeercount", REFRESH_INTERVAL);
  } else if (tunnels) {
    refreshElements("#tunnelPeerCount", "/tunnelpeercount", REFRESH_INTERVAL_SHORT);
  }
  checkForCachedFilter();
  applyQueryParamsFilter();
};

/**
 * Starts the periodic data refresh for tunnel peer counts.
 * @function initRefresh
 * @returns {void}
 */
const initRefresh = () => {
  updateTunnels();
};

/**
 * Creates and appends the filter input UI with clear button and localStorage persistence.
 * @function initFilter
 * @returns {void}
 */
function initFilter() {
  const table = tunnels;
  const addFilter = () => {
    const tnlFilter = d.createElement("span");
    tnlFilter.id = "tnlfilter";
    const filterInput = d.createElement("input");
    filterInput.type = "text";
    filterInput.id = "filterInput";
    filterInput.value = localStorage.getItem("filterValue") || "";
    tnlFilter.appendChild(filterInput);
    const clearFilter = d.createElement("button");
    clearFilter.textContent = "X";
    clearFilter.addEventListener("click", () => {
      filterInput.value = "";
      localStorage.removeItem("filterValue");
      history.replaceState({}, d.title, window.location.pathname);
      table.querySelectorAll(".lazy").forEach(row => row.style.display = "table-row");
      applyFilter("");
      displayPeerCount();
    });
    tnlFilter.appendChild(clearFilter);
    header.appendChild(tnlFilter);
    applyFilter(filterInput.value);
  };
  if (!d.getElementById("filterInput")) {
    addFilter();
  }
}

/**
 * Restores the filter value from localStorage and applies it.
 * @function checkForCachedFilter
 * @returns {void}
 */
function checkForCachedFilter() {
  const filterInput = d.getElementById("filterInput");
  if (filterInput) {
    filterInput.value = localStorage.getItem("filterValue") || "";
    if (filterInput.value) { applyFilter(filterInput.value); }
  }
  displayPeerCount();
}

/**
 * Filters tunnel peer rows based on key=value filter syntax.
 * Supports tier, cc/country, hash/id, hostname/host/h, ip, and v/version keys.
 * @function applyFilter
 * @param {string} filterValue - The filter string (plain text or key=value)
 * @returns {void}
 */
function applyFilter(filterValue) {
  const rows = peers.querySelectorAll(".lazy");
  displayed = 0;

  const filterParts = filterValue.split("=");
  const filterKey = filterParts[0].trim();
  const filterTerm = filterParts.length > 1 ? filterParts[1].trim() : filterValue.trim();

  rows.forEach(row => {
    const country = row.querySelector("td:first-child span.cc")?.textContent || "";
    const routerId = row.querySelector("td:nth-child(2) a")?.textContent || "";
    const version = row.querySelector("td:nth-child(3) a")?.textContent || "";
    const bwTier = row.querySelector("td:nth-child(4) a")?.textContent || "";
    const ipAddress = row.querySelector("td:nth-child(5) .ipaddress")?.textContent || "";
    const rlookup = row.querySelector("td:nth-child(6) .rlookup")?.textContent || "";

    let shouldDisplay = false;

    switch (filterKey) {
      case "tier":
        shouldDisplay = bwTier.includes(filterTerm);
        break;
      case "cc":
      case "country":
        shouldDisplay = country.includes(filterTerm.toUpperCase());
        break;
      case "hash":
      case "id":
        shouldDisplay = routerId.includes(filterTerm);
        break;
      case "hostname":
      case "host":
      case "h":
        shouldDisplay = rlookup.includes(filterTerm);
        break;
      case "ip":
        shouldDisplay = ipAddress.includes(filterTerm);
        break;
      case "v":
      case "version":
        shouldDisplay = version.includes(filterTerm);
        break;
      default:
        shouldDisplay = country.includes(filterTerm) || routerId.includes(filterTerm) ||
                        version.includes(filterTerm) || bwTier.includes(filterTerm) ||
                        ipAddress.includes(filterTerm) || (rlookup && rlookup.includes(filterTerm));
        break;
    }

    row.style.display = shouldDisplay ? "table-row" : "none";
    if (shouldDisplay) {displayed++;}
  });

  if (filterValue === "") {displayPeerCount();}
  else {
    clearTimeout(debounceTimeout);
    debounceTimeout = setTimeout(() => { displayPeerCount(); }, 500);
  }
}

/**
 * Updates the displayed peer count in the navigation tab based on filtered results.
 * @function displayPeerCount
 * @returns {void}
 */
function displayPeerCount() {
  if (parseInt(displayed, 10) >= 0) {
    const navTab = d.querySelector(".confignav .tab2");
    const totalCountCell = tunnels.querySelector("tfoot .tablefooter td:first-child")?.textContent || "";
    const totalCount = totalCountCell.match(/\d+/)?.[0] || 0;
    const filterCount = d.querySelector("#filterCount") || d.createElement("span");
    const filterInput = d.getElementById("filterInput");
    const filterValue = filterInput?.value || "";
    if (filterInput) {
      filterCount.id = "filterCount";
      if (displayed > 0) {
        filterCount.textContent = ` (${displayed})`;
        if (!navTab.querySelector("#filterCount")) {navTab.appendChild(filterCount);}
        d.body.classList.remove("noresults");
      } else {
        filterCount.textContent = "";
        if (navTab.querySelector("#filterCount")) {navTab.removeChild(filterCount);}
        d.body.classList.add("noresults");
      }
    } else {filterCount.textContent = ` (${totalCount})`;}
  }
}

/**
   * Attaches an input listener to the filter field for real-time filtering
   * and URL synchronization.
   * @function addFilterListener
   * @returns {void}
   */
function addFilterListener() {
  const filterInput = d.getElementById("filterInput");
  if (filterInput) {
    filterInput.addEventListener("input", event => {
      const filterValue = event.target.value;
      if (filterValue === "") {localStorage.removeItem("filterValue");}
      else {localStorage.setItem("filterValue", filterValue);}
      applyFilter(filterValue);
      updateURLWithFilter(filterValue);
    });
    filterListener = true;
  }
}

/**
   * Applies filter values from URL query parameters on page load.
   * @function applyQueryParamsFilter
   * @returns {void}
   */
function applyQueryParamsFilter() {
  const urlParams = new URLSearchParams(window.location.search);
  const filterParam = urlParams.get("filter");
  const typeParam = urlParams.get("type");
  if (filterParam) {
    const filterInput = d.getElementById("filterInput");
    if (filterInput) {
      if (typeParam && ["tier", "cc", "country", "hash", "id", "h", "hostname", "host", "ip", "v", "version"].includes(typeParam)) {
        filterInput.value = `${typeParam}=${filterParam}`;
        localStorage.setItem("filterValue", `${typeParam}=${filterParam}`);
        applyFilter(`${typeParam}=${filterParam}`);
      } else {
        filterInput.value = filterParam;
        localStorage.setItem("filterValue", filterParam);
        applyFilter(filterParam);
      }
    }
  } else {
    const filterValue = localStorage.getItem("filterValue") || "";
    if (filterValue) {
      const filterInput = d.getElementById("filterInput");
      if (filterInput) {
        filterInput.value = filterValue;
        applyFilter(filterValue);
        updateURLWithFilter(filterValue);
      }
    }
  }
}

/**
   * Updates the browser URL with the current filter value using replaceState.
   * @function updateURLWithFilter
   * @param {string} filterValue - The current filter string
   * @returns {void}
   */
function updateURLWithFilter(filterValue) {
  const urlParams = new URLSearchParams(window.location.search);
  if (filterValue) {
    const filterParts = filterValue.split("=");
    const filterKey = filterParts[0].trim();
    const filterTerm = filterParts.length > 1 ? filterParts[1].trim() : filterValue.trim();

    if (["tier", "cc", "country", "hash", "id", "h", "hostname", "host", "ip", "v", "version"].includes(filterKey)) {
      urlParams.set("filter", filterTerm);
      urlParams.set("type", filterKey);
    } else {
      urlParams.set("filter", filterValue);
      urlParams.delete("type");
    }
  } else {
    urlParams.delete("filter");
    urlParams.delete("type");
  }
  history.replaceState({}, d.title, `${window.location.pathname}${urlParams.toString() ? `?${urlParams.toString()}` : ""}`);
}

(function init() {
  d.addEventListener("DOMContentLoaded", () => {
    progressx.hide();
    initRefresh();
    initFilter();
    if (!filterListener) {addFilterListener();}
    refreshButton?.addEventListener("click", async () => { await updateTunnels(); });
    refreshButton?.addEventListener("mouseenter", () => { refreshButton?.removeAttribute("href"); });
    tunnels?.addEventListener("beforeSort", () => { progressx.show(theme); });
    tunnels?.addEventListener("afterSort", () => { progressx.hide(); checkForCachedFilter(); });
    document.addEventListener("refreshComplete", () => { sorter?.refresh(); });
  });
})();