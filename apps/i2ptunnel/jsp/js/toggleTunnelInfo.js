/**
 * @module toggleTunnelInfo
 * @file toggleTunnelInfo.js - Toggle visibility of tunnel info rows in I2PTunnel Manager
 * @description Expands or collapses tunnel detail rows and updates the toggle button icon
 * @license AGPL3 or later
 */

/**
 * Toggles the display of tunnel info rows between collapsed and expanded states.
 * Updates the toggle button icon and persists state via CSS class.
 * @function initToggleInfo
 * @returns {void}
 * @example
 * // Toggle tunnel info visibility
 * initToggleInfo();
 */
function initToggleInfo() {
  const toggle = document.getElementById("toggleInfo");
  const tunnelInfos = document.getElementsByClassName("tunnelInfo");
  const isCollapsed = toggle.classList.contains("collapse");

  for (const info of tunnelInfos) {info.style.display = isCollapsed ? "none" : "table-row";}

  toggle.innerHTML = isCollapsed
    ? '<img src="/themes/console/dark/images/expand_hover.svg" title="Show Tunnel Info">'
    : '<img src="/themes/console/dark/images/collapse_hover.svg" title="Hide Tunnel Info">';

  toggle.classList.toggle("collapse");
}

export { initToggleInfo };