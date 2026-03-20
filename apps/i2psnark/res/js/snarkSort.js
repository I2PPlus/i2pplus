/**
 * @module snarkSort
 * @file snarkSort.js - Navigate I2PSnark torrent sorters via AJAX calls.
 * @description Intercepts clicks on column-sort links in the I2PSnark torrent table header,
 * converts them into AJAX requests, and updates the browser history to reflect the active sort.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {doRefresh} from "./refreshTorrents.js";

/**
 * @function snarkSort
 * @description Initializes the sort listener on the I2PSnark torrent table header.
 * Adds a delegated click listener once, preventing duplicate listeners via a body class check.
 * @returns {void}
 */
function snarkSort() {
  const b = document.body;
  const active = b.classList.contains("sortListener");
  const snarkHead = document.getElementById("snarkHead");
  if (!snarkHead || active) {return;}
  b.classList.add("sortListener");
  document.addEventListener("click", sortListener);
}

/**
 * @function sortListener
 * @description Handles click events on sort column links. Prevents default navigation,
 * constructs an AJAX URL from the link's href, updates browser history, and triggers a torrent refresh.
 * @param {MouseEvent} event - The click event from a .sorter element.
 * @returns {void}
 */
let sortListener = function(event) {
  if (event.target.closest(".sorter")) {
    event.preventDefault();
    const link = event.target.closest(".sorter");
    const sortURL = new URL(link.href);
    const sortURLString = sortURL.toString().replace("html&", "html?").replace("/&", "/?");
    const reqSortURL = "/i2psnark/.ajax/xhr1.html" + sortURL.search;
    history.replaceState({}, "", new URL(sortURLString));
    doRefresh(reqSortURL);
  }
};

document.addEventListener("DOMContentLoaded", snarkSort);

export {snarkSort};