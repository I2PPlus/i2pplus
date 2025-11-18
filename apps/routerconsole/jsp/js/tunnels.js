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

document.addEventListener("DOMContentLoaded", function() {
  persistTunnelTableVisibility();
  persistTunnelIdVisibility();
  bodyTag.classList.add("js");

  refreshElements("#tunnelsContainer", "/tunnels", 10000);
});