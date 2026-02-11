/* I2P+ tunnels.js by dr|z3d */
/* Handle automatic refresh for console tunnels page and */
/* enable persistent toggling of tunnel ids and tunnel rows */
/* License: AGPL3 or later */

import { refreshElements } from './refreshElements.js';

const bodyTag = document.querySelector("body");
const container = document.querySelector("#tunnelsContainer");
const nav = document.querySelector(".confignav");
const tables = document.querySelectorAll("#tunnels table");
const tiers = document.getElementById("tiers");
const toggleIds = document.getElementById("toggleTunnelIds");
const toggleTunnels = document.getElementById("toggleTunnels");
const tunnelIdsHidden = document.querySelector(".idsHidden");
const tunnelsHidden = document.querySelector(".tunnelsHidden");
const isAdvancedMode = document.documentElement.classList.contains("advmode");
let tunnelTableVisibility = localStorage.getItem("tunnelTableVisibility");
let tunnelIdVisibility = localStorage.getItem("tunnelIdVisibility");

function moveStatusNotesToHeader(show) {
  const tablewraps = document.querySelectorAll("#tunnelsContainer .tablewrap");
  tablewraps.forEach(tablewrap => {
    const h3 = tablewrap.querySelector(":scope > h3.tabletitle");
    const table = tablewrap.querySelector(":scope > table");
    const statusnotes = table ? table.querySelector("tfoot#statusnotes") : null;
    const bandwidthRow = statusnotes ? statusnotes.querySelector("tr.bwUsage") : null;

    if (!bandwidthRow) return;

    let headerSpan = h3.querySelector(".statusnotes");

    if (!show && !headerSpan) {
      const rawHTML = bandwidthRow.innerHTML;
      const inMatch = rawHTML.match(/([0-9.,]+\s*[KMGT]?B)\s*(?:in)?/i);
      const outMatch = rawHTML.match(/([0-9.,]+\s*[KMGT]?B)\s*out/i);

      if (inMatch && outMatch) {
        headerSpan = document.createElement("span");
        headerSpan.className = "statusnotes";
        headerSpan.innerHTML = `<span class="dataIn">${inMatch[1]}</span> <span class="dataOut">${outMatch[1]}</span>`;
        h3.appendChild(headerSpan);
      }
    } else if (show && headerSpan) {
      headerSpan.remove();
    }
  });
}

nav.addEventListener("click", function(event) {
  if (event.target.id === "toggleTunnels") {
    const isHidden = document.querySelector("body").classList.contains("tunnelsHidden");
    if (isHidden) {
      bodyTag.classList.remove("tunnelsHidden");
      if (toggleTunnels.classList.contains("off")) {toggleTunnels.classList.remove("off");}
      localStorage.removeItem("tunnelTableVisibility");
      moveStatusNotesToHeader(true);
      tiers.style.display = "";
    } else {
      bodyTag.classList.add("tunnelsHidden");
      if (!toggleTunnels.classList.contains("off")) {toggleTunnels.classList.add("off");}
      localStorage.setItem("tunnelTableVisibility", "hidden");
      moveStatusNotesToHeader(false);
      tiers.style.display = "none";
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
  setTimeout(() => {
    moveStatusNotesToHeader(!bodyTag.classList.contains("tunnelsHidden"));
  }, 0);
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
        })
        .catch(error => {})
        .finally(() => {
          if (bodyTag.classList.contains("tunnelsHidden")) {
            moveStatusNotesToHeader(false);
          }
        });
    }
  });

  refreshElements("#tunnelsContainer", "/tunnels", 10000);
});