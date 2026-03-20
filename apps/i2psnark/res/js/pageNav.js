/**
 * @module pageNav
 * @file pageNav.js - AJAX page navigation for I2PSnark torrent listings.
 * @description Intercepts clicks on pagination links in the I2PSnark UI, converts them
 * to AJAX requests, and updates the browser history without full page reloads. Prevents
 * duplicate listeners via a body class check.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {doRefresh} from "./refreshTorrents.js";

/**
 * @function pageNav
 * @description Initializes the AJAX pagination listener. Adds a delegated click handler
 * on the document that intercepts clicks on #paginate links and triggers a refresh
 * via the doRefresh function. Prevents duplicate listeners via a body class check.
 * @returns {void}
 */
function pageNav() {
  const bodyTag = document.body;
  const active = bodyTag.classList.contains("pagenavListener");
  const paginator = document.getElementById("paginate");
  const topNav = document.getElementById("navbar");
  if (!topNav || active) {return;}
  bodyTag.classList.add("pagenavListener");
  document.addEventListener("click", pagenavListener);
}

/**
 * @type {Function}
 * @description Delegated click listener for pagination links. Prevents default navigation,
 * extracts the URL, constructs an AJAX-compatible URL, updates browser history, and triggers
 * a torrent refresh via doRefresh.
 * @param {MouseEvent} event - The click event from a pagination link.
 * @returns {void}
 */
let pagenavListener = function(event) {
  if (event.target.closest("#paginate a:not(disabled)")) {
    event.preventDefault();
    const clickedElement = event.target;
    const pagenavURL = new URL(event.target.closest("#paginate a:not(disabled)").href);
    if (pagenavURL) {
      const xhrPagenavURL = "/i2psnark/.ajax/xhr1.html" + pagenavURL.search;
      history.replaceState({}, "", new URL(pagenavURL));
      doRefresh(xhrPagenavURL);
    }
  }
};

document.addEventListener("DOMContentLoaded", pageNav);

export {pageNav};