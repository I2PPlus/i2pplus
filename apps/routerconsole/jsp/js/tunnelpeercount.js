/* I2P+ tunnelpeercount.js by dr|z3d */
/* Handle automatic and manual refresh for /tunnelcountpeer */
/* License: AGPL3 or later */

import { onVisible } from "/js/onVisible.js";

const d = document;
const header = d.getElementById("peercount");
const peers = d.getElementById("allPeers");
const refreshButton = d.getElementById("refreshPage");
const tunnels = d.getElementById("tunnelPeerCount");
const footer = tunnels?.querySelector(".tablefooter");
const REFRESH_INTERVAL = 60 * 1000;
const sorter = tunnels ? new Tablesort(tunnels, { descending: true }) : null;

let debugging = false;
let refreshId;
let filterListener = false;

const fetchTunnelData = async () => {
  try {
    const response = await fetch("/tunnelpeercount");
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
    const doc = new DOMParser().parseFromString(await response.text(), "text/html");
    return {
      mainResponse: doc.getElementById("tunnels"),
      peersResponse: doc.getElementById("allPeers"),
      footerResponse: doc.querySelector(".tablefooter"),
    };
    checkForCachedFilter();
  } catch (error) {
    if (debugging) console.error(error);
    return null;
  }
};

const updateTunnels = async () => {
  const { mainResponse, peersResponse, footerResponse } = await fetchTunnelData() || {};
  if (peersResponse) {
    peers.innerHTML = peersResponse.innerHTML;
    sorter?.refresh();
  } else if (mainResponse) { main.innerHTML = mainResponse.innerHTML; }
  if (footer && footerResponse) { footer.innerHTML = footerResponse.innerHTML;}
  checkForCachedFilter();
};

const initRefresh = () => {
  if (refreshId) clearInterval(refreshId);
  refreshId = setInterval(updateTunnels, REFRESH_INTERVAL);
  updateTunnels();
};

function initFilter() {
  if (theme !== "dark") return;
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
      table.querySelectorAll(".lazy").forEach(row => row.style.display = "table-row");
    });
    tnlFilter.appendChild(clearFilter);
    header.appendChild(tnlFilter);
    applyFilter(filterInput.value);
  };
  if (!d.getElementById("filterInput")) { addFilter(); }
}

function checkForCachedFilter() {
  const filterInput = d.getElementById("filterInput");
  if (filterInput) {
    filterInput.value = localStorage.getItem("filterValue") || "";
    if (filterInput.value) {applyFilter(filterInput.value);}
  }
}

function addFilterListener() {
  if (theme !== "dark") return;
  const filterInput = d.getElementById("filterInput");
  if (filterInput) {
    filterInput.addEventListener("input", event => {
      const filterValue = event.target.value;
      localStorage.setItem("filterValue", filterValue);
      applyFilter(filterValue);
    });
    filterListener = true;
  }
}

function applyFilter(filterValue) {
  if (theme !== "dark") return;
  const rows = peers.querySelectorAll(".lazy");
  rows.forEach(row => {
    const country = row.querySelector("td:first-child span.cc").textContent;
    const routerId = row.querySelector("td:nth-child(2) a").textContent;
    const version = row.querySelector("td:nth-child(3) a").textContent;
    const bwTier = row.querySelector("td:nth-child(4) a").textContent;
    const ipAddress = row.querySelector("td:nth-child(5) .ipaddress").textContent;
    const rlookup = row.querySelector("td:nth-child(6) .rlookup");
    let shouldDisplay = country.includes(filterValue) || version.includes(filterValue) || bwTier.includes(filterValue) ||
                        (rlookup && rlookup.textContent.includes(filterValue));
    if (filterValue.startsWith("tier")) {
      const tierValue = filterValue.split("tier")[1].trim();
      shouldDisplay = bwTier.includes(tierValue);
    }
    row.style.display = shouldDisplay ? "table-row" : "none";
  });
}

(function init() {
  d.addEventListener("DOMContentLoaded", () => {
    progressx.hide();
    initRefresh();
    initFilter();
    if (!filterListener) {addFilterListener();}
    refreshButton?.addEventListener("click", async () => { await updateTunnels(); });
    refreshButton?.addEventListener("mouseenter", () => refreshButton?.removeAttribute("href"));
    onVisible(tunnels, updateTunnels);
    tunnels?.addEventListener("beforeSort", () => { progressx.show(theme); });
    tunnels?.addEventListener("afterSort", () => { progressx.hide(); checkForCachedFilter(); });
    if (d.visibilityState === "hidden") clearInterval(refreshId);
  });
})();