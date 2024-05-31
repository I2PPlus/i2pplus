/* I2P+ I2PSnark toggleDebug.js by dr|z3d */
/* Toggle debug tablerows and panel via ajax */
/* License: AGPL3 or later */

import {doRefresh} from "./refreshTorrents.js";

function toggleDebug() {
  const snarkFoot = document.getElementById("snarkFoot");
  if (!snarkFoot) {return;}
  snarkFoot.removeEventListener("click", debugListener);
  snarkFoot.addEventListener("click", debugListener);
}

let debugListener = function(event) {
  if (event.target.closest("#debugMode")) {
    event.preventDefault();
    const clickedElement = event.target;
    let debugURL = new URL(event.target.href);
    let debugQuery = debugURL.search;
    let debugURLString = debugURL.toString();
    const snarkTbody = document.getElementById("snarkTbody");
    const debugRows = snarkTbody.querySelectorAll(".debuginfo");
    const snarkFoot = document.getElementById("snarkFoot");
    const debugSection = snarkFoot.querySelector("#dhtDebug");
    const url = new URL(window.location.href);
    let port = url.port != null ? ":" + url.port : "";
    let newURL = window.location.protocol + "//" + url.hostname + port + url.pathname;
    if (debugQuery) {newURL += debugQuery.toString().trim();}
    if (debugQuery.toString().includes("p=2")) {debugSection.removeAttribute("hidden");}
    else {debugSection.setAttribute("hidden", "");}
    console.log(newURL);
    history.replaceState({}, "", new URL(newURL));
    let xhrDebugURL = newURL.toString().replace("/i2psnark/", "/i2psnark/.ajax/xhr1.html");
    doRefresh(xhrDebugURL);
  }
};

document.addEventListener("DOMContentLoaded", toggleDebug);

export {toggleDebug};