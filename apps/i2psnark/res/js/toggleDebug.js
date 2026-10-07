/**
 * @module toggleDebug
 * @file toggleDebug.js - Toggle debug table-rows and panel in I2PSnark.
 * @description Adds a click listener that toggles a "debug" class on the document body,
 * allowing debug-related table rows and panels to be shown or hidden in the I2PSnark UI.
 * The chosen state is persisted in localStorage so the panel keeps its visibility across
 * page loads and refreshes.
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

/**
 * @type {string}
 * @description Current debug panel state: "on" or "off". Persisted in localStorage.
 */
let debugToggleConfig = localStorage.getItem("snarkDebugMode") || "off";

/**
 * @function applyDebugMode
 * @description Applies the current debug state to the UI: toggles the "debug" class on the
 * body, and syncs the toggle link's label and href. The link text is swapped between the two
 * labels the servlet embedded as data attributes, so the displayed label stays translated
 * rather than being hardcoded here.
 *
 * The link is looked up on each call rather than cached, because an AJAX refresh replaces
 * the footer markup (mainsection.innerHTML) and would leave a cached reference detached.
 * @returns {void}
 */
function applyDebugMode() {
  const isOn = debugToggleConfig === "on";
  document.body.classList.toggle("debug", isOn);
  const link = document.getElementById("debugMode");
  if (!link) { return; }
  const debugLabel = link.dataset.debugLabel;
  const normalLabel = link.dataset.normalLabel;
  if (debugLabel && normalLabel) { link.textContent = isOn ? normalLabel : debugLabel; }
  // Keep the href truthful so the link still works with scripting disabled. "on" means the
  // panel is showing, so the link has to point at the page that turns it off.
  link.href = isOn ? "?p" : "?p=2";
}

/**
 * @function toggleDebug
 * @description Initializes the debug toggle listener and (re)applies the persisted state.
 * Registers a delegated click handler on the document that toggles the "debug" class when the
 * #debugMode element is clicked. Prevents duplicate listeners via a body class check.
 *
 * Re-applies unconditionally so callers on the refresh path pick up the new footer markup;
 * only the listener registration is guarded.
 * @returns {void}
 */
function toggleDebug() {
  applyDebugMode();
  const bodyTag = document.body;
  if (bodyTag.classList.contains("debugListener")) {return;}
  if (!document.getElementById("snarkFoot")) {return;}
  bodyTag.classList.add("debugListener");
  document.addEventListener("click", debugListener);
}

/**
 * @type {Function}
 * @description Delegated click listener that toggles the "debug" class on the document body
 * when the #debugMode element is clicked, persisting the new state.
 * @param {MouseEvent} event - The click event.
 * @returns {void}
 */
let debugListener = function(event) {
  if (event.target.id !== "debugMode") { return; }
  event.preventDefault();
  debugToggleConfig = debugToggleConfig === "on" ? "off" : "on";
  localStorage.setItem("snarkDebugMode", debugToggleConfig);
  applyDebugMode();
};

document.addEventListener("DOMContentLoaded", toggleDebug);

export {toggleDebug};
