/**
 * @module tunnels
 * @description Handles automatic refresh for the console tunnels page and
 * enables persistent toggling of tunnel IDs and tunnel table row visibility
 * via localStorage. Updates tunnel in/out counts on refresh.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from './refreshElements.js';

const bodyTag = document.querySelector("body");
const container = document.querySelector("#tunnelsContainer");
const nav = document.querySelector(".confignav");
const toggleIds = document.getElementById("toggleTunnelIds");
const toggleTunnels = document.getElementById("toggleTunnels");
const tunnelIdsHidden = document.querySelector(".idsHidden");
const tunnelsHidden = document.querySelector(".tunnelsHidden");
const isAdvancedMode = document.documentElement.classList.contains("advmode");
let tunnelTableVisibility = localStorage.getItem("tunnelTableVisibility");
let tunnelIdVisibility = localStorage.getItem("tunnelIdVisibility");

nav.addEventListener("click", function(event) {
  if (event.target.id === "toggleTunnels") {
    const isHidden = document.querySelector("body").classList.contains("tunnelsHidden");
    if (isHidden) {
      bodyTag.classList.remove("tunnelsHidden");
      if (toggleTunnels.classList.contains("off")) {toggleTunnels.classList.remove("off");}
      localStorage.removeItem("tunnelTableVisibility");
    } else {
      bodyTag.classList.add("tunnelsHidden");
      if (!toggleTunnels.classList.contains("off")) {toggleTunnels.classList.add("off");}
      localStorage.setItem("tunnelTableVisibility", "hidden");
    }
  }
  if (!isAdvancedMode) {
    toggleIds.remove();
    return;
  }
  if (event.target.id === "toggleTunnelIds") {
    const isHidden = document.querySelector("body").classList.contains("idsHidden");
    if (isHidden) {
      bodyTag.classList.remove("idsHidden");
      if (toggleIds.classList.contains("off")) {toggleIds.classList.remove("off");}
      localStorage.removeItem("tunnelIdVisibility");
    } else {
      bodyTag.classList.add("idsHidden");
      if (!toggleIds.classList.contains("off")) {toggleIds.classList.add("off");}
      localStorage.setItem("tunnelIdVisibility", "hidden");
    }
  }
});

/**
 * Restores tunnel table visibility state from localStorage on page load.
 * @function persistTunnelTableVisibility
 * @returns {void}
 */
function persistTunnelTableVisibility() {
  if (tunnelTableVisibility) {
    if (!tunnelsHidden) {
      bodyTag.classList.add("tunnelsHidden");
      if (!toggleTunnels.classList.contains("off")) {toggleTunnels.classList.add("off");}
    }
  } else {
    bodyTag.classList.remove("tunnelsHidden");
    if (toggleTunnels.classList.contains("off")) {toggleTunnels.classList.remove("off");}
    localStorage.removeItem("tunnelTableVisibility");
  }
}

/**
 * Restores tunnel ID visibility state from localStorage on page load.
 * @function persistTunnelIdVisibility
 * @returns {void}
 */
function persistTunnelIdVisibility() {
  if (!isAdvancedMode) {return;}
  if (tunnelIdVisibility) {
    if (!tunnelIdsHidden) {
      document.querySelector("body").classList.add("idsHidden");
      if (!toggleIds.classList.contains("off")) {toggleIds.classList.add("off");}
    }
  } else {
    document.querySelector("body").classList.remove("idsHidden");
    if (toggleIds.classList.contains("off")) {toggleIds.classList.remove("off");}
    localStorage.removeItem("tunnelIdVisibility");
  }
}

/**
   * Recalculates and updates tunnel in/out count displays for each pool.
   * @function updateTunnelCounts
   * @returns {void}
   */
function updateTunnelCounts() {
  const pools = container.querySelectorAll(".tablewrap");
  pools.forEach(pool => {
    const summary = pool.querySelector("table.poolsummary");
    const tunnelTable = pool.querySelector("table.tunnels_client");
    if (!summary || !tunnelTable) { return; }

    const inCount = tunnelTable.querySelectorAll('td.direction[data-sort="in"]').length;
    const outCount = tunnelTable.querySelectorAll('td.direction[data-sort="out"]').length;

    const inCell = summary.querySelector("th.inCount");
    const outCell = summary.querySelector("th.outCount");
    if (inCell) {
      const parts = inCell.textContent.split(" / ");
      if (parts.length === 2) {inCell.textContent = inCount + " / " + parts[1];}
    }
    if (outCell) {
      const parts = outCell.textContent.split(" / ");
      if (parts.length === 2) {outCell.textContent = outCount + " / " + parts[1];}
    }
  });
}

/**
 * Whether the live container and the freshly realized fragment expose the
 * same per-pool row structure (same number of tunnel tables, same tbody row
 * count and tfoot shape in each). The cell morph in patchResponse pairs the
 * volatile cells (status/expiry/latency/data/footer) by global index across
 * every pool, so it is only safe to keep those patched cells when each pool
 * table has identical structure in both documents. Any drift means the initial
 * morph mis-aligned indices at the pool boundary and already rewrote cells
 * with neighbor-pool data, so the caller must wholesale-replace the container.
 * @function poolsAligned
 * @param {Element} live - the live #tunnelsContainer element
 * @param {Element} fetched - the #tunnelsContainer realized from the fresh fragment
 * @returns {boolean} true when every pool table matches structurally
 */
function poolsAligned(live, fetched) {
  const liveTables = live.querySelectorAll("table.tunnels_client");
  const fetchedTables = fetched.querySelectorAll("table.tunnels_client");
  if (liveTables.length !== fetchedTables.length) { return false; }
  for (let i = 0; i < liveTables.length; i++) {
    const liveBody = liveTables[i].tBodies[0];
    const fetchedBody = fetchedTables[i].tBodies[0];
    if (!liveBody || !fetchedBody) { return false; }
    if (liveBody.rows.length !== fetchedBody.rows.length) { return false; }
    for (let j = 0; j < liveBody.rows.length; j++) {
      if (liveBody.rows[j].cells.length !== fetchedBody.rows[j].cells.length) { return false; }
    }
    const liveFootCells = liveTables[i].tFoot && liveTables[i].tFoot.rows[0] ? liveTables[i].tFoot.rows[0].cells.length : 0;
    const fetchedFootCells = fetchedTables[i].tFoot && fetchedTables[i].tFoot.rows[0] ? fetchedTables[i].tFoot.rows[0].cells.length : 0;
    if (liveFootCells !== fetchedFootCells) { return false; }
  }
  return true;
}

document.addEventListener("DOMContentLoaded", function() {
  persistTunnelTableVisibility();
  persistTunnelIdVisibility();
  bodyTag.classList.add("js");

  document.addEventListener("elementsRefreshed", function(event) {
    if (!event.detail.selectors.some(sel => sel.includes("tunnelsContainer"))) return;

    const fragment = event.detail.fragment;
    const fetched = fragment ? fragment.querySelector("#tunnelsContainer") : null;
    if (fetched && !poolsAligned(container, fetched)) {
      // The steady-state morph in patchResponse pairs the volatile cells by
      // global index across every pool and ran before this handler, so once
      // any pool's row structure drifted it has already mis-paired cells and
      // cleared or overwritten them with neighbor-pool data. Rewriting the
      // container from the already-fetched fragment (no second fetch or
      // main-thread DOMParser pass) repairs that damage and applies the
      // structural changes wholesale.
      container.innerHTML = fetched.innerHTML;
    }
    // After morphdom, a td.data cell may have a correct data-sort attribute
    // but an empty span.right due to index mis-pairing or whitespace node
    // interference. Replicate the server-side B→KB→MB conversion inline.
    container.querySelectorAll("td.data").forEach(function(td) {
      var right = td.querySelector("span.right");
      if (right && !right.textContent) {
        var sortVal = parseInt(td.getAttribute("data-sort"), 10);
        if (sortVal > 0) {
          var sizeInKB = sortVal * 1024.0 / 1000.0;
          right.textContent = sizeInKB >= 1024
            ? (sizeInKB / 1024.0).toFixed(2)
            : Math.round(sizeInKB);
        }
      }
    });
    updateTunnelCounts();
  });

  // Steady-state refresh morphs only the volatile cells (test status,
  // expiry, latency, data, footer bandwidth) so morphdom never recombines
  // tbody/tfoot table sections; structural changes trigger the full
  // #tunnelsContainer replace above. includeContainer exposes the realized
  // contentonly fragment on the refresh detail for that comparison.
  refreshElements(
    "#tunnelsContainer td.status, #tunnelsContainer td.expiry, #tunnelsContainer td.latency, #tunnelsContainer td.data, #tunnelsContainer tfoot td",
    "/tunnels", 10000, false, false, "tunnelsContainer", null, 0, true
  );
});
