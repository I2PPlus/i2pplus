/* I2P+ tunnels.js by dr|z3d */
/* Handle automatic refresh for console tunnels page and */
/* enable persistent toggling of tunnel ids and tunnel rows */
/* License: AGPL3 or later */

const bodyTag = document.querySelector("body");
const container = document.querySelector("#tunnelsContainer");
const nav = document.querySelector(".confignav");
const tables = document.querySelectorAll("#tunnels table");
const toggleIds = document.getElementById("toggleTunnelIds");
const toggleTunnels = document.getElementById("toggleTunnels");
const tunnelIdsHidden = document.querySelector(".idsHidden");
const tunnelsHidden = document.querySelector(".tunnelsHidden");
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
  document.querySelector("body").classList.add("js");
  const visibility = document.visibilityState;
  if (visibility === "visible") {
    setInterval(function() {
      const xhrtunn = new XMLHttpRequest();
      xhrtunn.open('GET', '/tunnels', true);
      xhrtunn.responseType = "document";
      xhrtunn.onload = function () {
        const tunnels = document.getElementById("tunnelsContainer");
        const tunnelsResponse = xhrtunn.responseXML?.getElementById("tunnelsContainer");
        if (tunnels.innerHTML !== tunnelsResponse.innerHTML) {
          tunnels.innerHTML !== tunnelsResponse.innerHTML;
        }
      }
      xhrtunn.send();
    }, 15000);
  }
});