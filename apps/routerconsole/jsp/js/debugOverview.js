/**
 * @module debugOverview
 * @description Auto-refreshes the /debug overview tab: morphs the stat ring
 * grid via refreshElements and reloads the mini-graph images with a
 * preload-then-swap src update so the visible graphs never blank.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import {refreshElements} from "/js/refreshElements.js";

const REFRESH_INTERVAL = 10 * 1000;

/**
 * Reloads each mini-graph image the way /graphs does: the cache-busted image
 * is loaded into a detached Image and the live src is swapped only once the
 * new image has loaded, so the visible graph never blanks while it renders.
 * @function refreshGraphs
 * @returns {void}
 */
function refreshGraphs() {
  document.querySelectorAll(".debug_graphs img").forEach(img => {
    const src = img.src.replace(/t=\d+/, "t=" + Date.now());
    if (src === img.src) { return; }
    const pre = new Image();
    pre.onload = () => { img.src = src; };
    pre.src = src;
  });
}

refreshElements("#debugstats", "/debug?d=8", REFRESH_INTERVAL);
document.addEventListener("elementsRefreshed", refreshGraphs);
