/* I2P+ tunnels.js by dr|z3d */
/* Handle automatic refresh for console tunnels page and */
/* enable persistent toggling of tunnel ids and tunnel rows */
/* License: AGPL3 or later */

import { refreshElements } from './refreshElements.js';

const bodyTag = document.querySelector("body");
const container = document.querySelector("#tunnelsContainer");
const nav = document.querySelector(".confignav");
const tables = document.querySelectorAll("#tunnels table");
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
  } else if (event.target.id === "toggleTunnelIds") {
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

function updateTunnelCounts() {
  const pools = container.querySelectorAll(".tablewrap");
  pools.forEach(pool => {
    const summary = pool.querySelector("table.poolsummary");
    const tunnelTable = pool.querySelector("table.tunnels_client");
    if (!summary || !tunnelTable) return;

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

document.addEventListener("DOMContentLoaded", function() {
  persistTunnelTableVisibility();
  persistTunnelIdVisibility();
  bodyTag.classList.add("js");

  document.addEventListener("elementsRefreshed", function(event) {
    if (event.detail.selectors.includes("#tunnelsContainer")) {
      const currentTables = container.querySelectorAll("table").length;
      fetch("/tunnels")
        .then(response => response.text())
        .then(html => {
          const parser = new DOMParser();
          const doc = parser.parseFromString(html, "text/html");
          const fetchedTables = doc.querySelectorAll("#tunnelsContainer table").length;

          if (fetchedTables > currentTables) {
            const newContainer = doc.querySelector("#tunnelsContainer");
            if (newContainer) {
              container.innerHTML = newContainer.innerHTML;
            }
          }
          updateTunnelCounts();
        })
        .catch(error => {});
    }
  });

  refreshElements("#tunnelsContainer", "/tunnels", 10000);
});