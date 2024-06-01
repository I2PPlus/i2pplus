/* I2P+ snarkSort.js by dr|z3d */
/* Navigate I2PSnark torrent sorters via AJAX calls */
/* License: AGPL3 or later */

import {doRefresh} from "./refreshTorrents.js";

function snarkSort() {
  const bodyTag = document.body;
  const active = bodyTag.classList.contains("sortListener");
  const snarkHead = document.getElementById("snarkHead");
  if (!snarkHead || active) {return;}
  bodyTag.classList.add("sortListener");
  document.addEventListener("click", sortListener);
}

let sortListener = function(event) {
  let sortURL;
  let sortURLString;
  let xhrSortURL;
  if (event.target.closest(".sorter")) {
    event.preventDefault();
    const clickedElement = event.target;
    sortURL = new URL(event.target.closest(".sorter").href);
    if (sortURL) {
      sortURLString = sortURL.toString().replace("html&", "html?").replace("/&", "/?");
      xhrSortURL = "/i2psnark/.ajax/xhr1.html" + sortURL.search;
      xhrSortURL = xhrSortURL.replace("html&", "html?").replace("/&", "/?");
      history.replaceState({}, "", new URL(sortURLString));
      doRefresh(xhrSortURL);
    }
  }
};

document.addEventListener("DOMContentLoaded", snarkSort);

export {snarkSort};