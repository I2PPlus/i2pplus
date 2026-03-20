/**
 * @module toggleDebug
 * @file toggleDebug.js - Toggle debug table-rows and panel in I2PSnark.
 * @description Adds a click listener that toggles a "debug" class on the document body,
 * allowing debug-related table rows and panels to be shown or hidden in the I2PSnark UI.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @function toggleDebug
 * @description Initializes the debug toggle listener. Registers a delegated click handler
 * on the document that toggles the "debug" class on the body when the #debugMode element
 * is clicked. Prevents duplicate listeners via a body class check.
 * @returns {void}
 */
function toggleDebug() {
  const bodyTag = document.body;
  const active = bodyTag.classList.contains("debugListener");
  const snarkFoot = document.getElementById("snarkFoot");
  if (!snarkFoot) {return;}
  document.addEventListener("click", debugListener);
}

/**
 * @type {Function}
 * @description Delegated click listener that toggles the "debug" class on the document body
 * when the #debugMode element is clicked.
 * @param {MouseEvent} event - The click event.
 * @returns {void}
 */
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