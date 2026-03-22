/**
 * @module toggleThrottler
 * @file toggleThrottler.js - Toggle server throttler section and persist state
 * @description I2PTunnel Manager component that toggles the throttler panel visibility
 * and remembers the user's preference via localStorage
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * IIFE that initializes the throttler toggle functionality.
 * Sets up click handler on throttle header to show/hide the throttler panel.
 * Restores previous toggle state from localStorage on page load.
 * @function
 * @returns {void}
 */
(() => {
  const toggleThrottler = document.getElementById("toggleThrottler");
  const throttleHeader = document.getElementById("throttleHeader");
  const tunnelThrottler = document.getElementById("tunnelThrottler");

  if (toggleThrottler.hidden) { toggleThrottler.hidden = false; }
  if (!throttleHeader.classList.length) { tunnelThrottler.style.display = "none"; }

  /**
   * Toggles the throttler panel visibility and persists state.
   * @function toggleElement
   * @returns {void}
   */
  const toggleElement = () => {
    const isDisplayed = tunnelThrottler.style.display === "none";
    tunnelThrottler.style.display = isDisplayed ? "table-row" : "none";
    throttleHeader.classList.toggle("isDisplayed", isDisplayed);
    localStorage.setItem("toggleState", tunnelThrottler.style.display);
  };

  const toggleState = localStorage.getItem("toggleState");
  if (toggleState) {
    tunnelThrottler.style.display = toggleState;
    throttleHeader.classList.toggle("isDisplayed", toggleState === "table-row");
  }

  throttleHeader.addEventListener("click", toggleElement);
})();