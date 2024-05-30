/* I2P+ snarkSort.js by dr|z3d */
/* Navigate I2PSnark torrent sorters via AJAX calls */
/* License: AGPL3 or later */

import {doRefresh} from "./refreshTorrents.js";

function snarkSort() {
  const snarkHead = document.getElementById("snarkHead");
  if (!snarkHead) {return;}
  snarkHead.removeEventListener("click", sortListener);
  snarkHead.addEventListener("click", sortListener);
  snarkHead.querySelectorAll(".sorter").forEach((sorter) => {sorter.pointerEvents = "";}
}

let sortListener = function(event) {
  let sortURL;
  let xhrSortURL;
  if (event.target.closest(".sorter")) {
    event.preventDefault();
    const clickedElement = event.target;
    sortURL = new URL(event.target.closest(".sorter").href);
    if (sortURL) {
      snarkHead.querySelectorAll(".sorter").forEach((sorter) => {sorter.pointerEvents = "none";}
      xhrSortURL = "/i2psnark/.ajax/xhr1.html" + sortURL.search;
      history.replaceState({}, "", sortURL);
      doRefresh(xhrSortURL, snarkSort);
    }
  }
};

document.addEventListener("DOMContentLoaded", snarkSort);

export {snarkSort};