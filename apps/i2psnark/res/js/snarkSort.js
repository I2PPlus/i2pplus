/* I2P+ snarkSort.js by dr|z3d */
/* Navigate I2PSnark torrent sorters via AJAX calls */
/* License: AGPL3 or later */

import {xhrsnark, doRefresh, getURL} from "./refreshTorrents.js";
import {updateURLs} from "./filterBar.js";

function snarkSort() {
  let sortURL;
  let xhrSortURL;
  const snarkHead = document.getElementById("snarkHead");
  snarkHead.addEventListener("click", function(event) {
    if (event.target.closest(".sorter")) {
      event.preventDefault();
      const clickedElement = event.target;
      sortURL = new URL(event.target.closest(".sorter").href);
      if (sortURL) {
        xhrSortURL = "/i2psnark/.ajax/xhr1.html" + sortURL.search;
        history.replaceState({}, "", sortURL);
        doRefresh(xhrSortURL, updateURLs);
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", snarkSort);

export {snarkSort};