/* I2P+ I2PSnark pageNav.js by dr|z3d */
/* AJAX page navigation */
/* License: AGPL3 or later */

import {doRefresh} from "./refreshTorrents.js";

function pageNav() {
  const bodyTag = document.body;
  const active = bodyTag.classList.contains("pagenavListener");
  const topNav = document.getElementById("pagenavtop");
  if (!topNav || active) {return;}
  bodyTag.classList.add("pagenavListener");
  document.addEventListener("click", pagenavListener);
}

let pagenavListener = function(event) {
  if (event.target.closest(".pagenavcontrols a:not(disabled)")) {
    event.preventDefault();
    const clickedElement = event.target;
    const pagenavURL = new URL(event.target.closest(".pagenavcontrols a:not(disabled)").href);
    if (pagenavURL) {
      const xhrPagenavURL = "/i2psnark/.ajax/xhr1.html" + pagenavURL.search;
      history.replaceState({}, "", new URL(pagenavURL));
      doRefresh(xhrPagenavURL);
    }
  }
};

document.addEventListener("DOMContentLoaded", pageNav);

export {pageNav};