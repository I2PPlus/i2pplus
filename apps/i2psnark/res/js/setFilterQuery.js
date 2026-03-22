/**
 * @module setFilterQuery
 * @file setFilterQuery.js - Assigns filter parameter to main snark navigation and cancel search buttons.
 * @description Reads the current filter value from the URL query string or localStorage, then
 * updates the href attributes of the I2PSnark navbar link and cancel-search link so they
 * preserve the active filter across navigation.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @type {?HTMLElement}
 * @description The #navbar element containing the main navigation.
 */
const navbar = document.getElementById("navbar");

/**
 * @type {?HTMLElement}
 * @description The .nav_main link within the navbar.
 */
const navMain = navbar.querySelector(".nav_main");

/**
 * @type {?HTMLElement}
 * @description The cancel-search anchor inside the #searchwrap element.
 */
const cancelSearch = navbar.querySelector("#searchwrap a");

/**
 * @function setFilterQuery
 * @description Registers click listeners on the navbar and cancel-search elements.
 * When clicked, updates their href attributes to include the active filter parameter
 * from the URL or localStorage, preserving the filter across page navigation.
 * @returns {void}
 */
function setFilterQuery() {
  if (!navbar || !navMain || !cancelSearch) { return; }

  /**
   * @function updateHref
   * @description Reads the current filter from the URL or localStorage and updates
   * the element's href to include it as a query parameter.
   * @param {HTMLElement} element - The DOM element whose href will be updated.
   * @returns {void}
   */
  function updateHref(element) {
    const filter = new URLSearchParams(window.location.search).get("filter") || localStorage.getItem("snarkFilter");
    if (filter && filter !== "all" && filter !== "search" && filter !== "") {
      element.href = `/i2psnark/?filter=${filter}`;
    }
  }

  navbar.addEventListener("click", (event) => {
    if (event.target.classList.contains("nav_main")) {updateHref(navMain);}
  });
  cancelSearch.addEventListener("click", (event) => {
    if (event.target.href.includes("i2psnark")) {updateHref(cancelSearch);}
  });
}

document.addEventListener("DOMContentLoaded", setFilterQuery);