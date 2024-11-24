/* I2P+ snarkSort.js by dr|z3d */
/* Navigate I2PSnark torrent sorters via AJAX calls */
/* License: AGPL3 or later */

import {doRefresh} from "./refreshTorrents.js";

function snarkSort() {
  const b = document.body;
  const active = b.classList.contains("sortListener");
  const snarkHead = document.getElementById("snarkHead");
  if (!snarkHead || active) {return;}
  b.classList.add("sortListener");
  document.addEventListener("click", sortListener);
}

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