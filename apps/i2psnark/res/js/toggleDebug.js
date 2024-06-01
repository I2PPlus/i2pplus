/* I2P+ I2PSnark toggleDebug.js by dr|z3d */
/* Toggle debug table-rows and panel */
/* License: AGPL3 or later */

function toggleDebug() {
  const bodyTag = document.body;
  const active = bodyTag.classList.contains("debugListener");
  const snarkFoot = document.getElementById("snarkFoot");
  if (!snarkFoot) {return;}
  document.addEventListener("click", debugListener);
}

let debugListener = function(event) {
  const bodyTag = document.body;
  if (event.target.id === "debugMode") {
    event.preventDefault();
    if (bodyTag.classList.contains("debug")) {
      bodyTag.classList.remove("debug");
    } else {
      bodyTag.classList.add("debug");
    }
  }
};

document.addEventListener("DOMContentLoaded", toggleDebug);

export {toggleDebug};