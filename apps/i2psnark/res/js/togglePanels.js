/**
 * @module togglePanels
 * @file togglePanels.js - Scroll expanded info-page panels into view and restore scroll on collapse.
 * @description Applies the same viewport treatment as the main page's add/create sections and the
 * configuration page panels to the Files and Comments sections of torrent detail pages: when a
 * collapsed panel is expanded, it is smoothly scrolled into view; when collapsed again, the prior
 * scroll position is restored. Works standalone and embedded in the router console iframe.
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

/**
 * @type {boolean}
 * @description Whether the page is running inside an iframe.
 */
const isIframed = document.documentElement.classList.contains("iframed") || window.self != window.top;

/**
 * @type {?Object}
 * @description Scroll offsets captured when a panel is expanded, used to restore
 * the position when the panel is collapsed again.
 */
let savedScroll = null;

/**
 * @function saveScroll
 * @description Records the current scroll offsets of this window and, when embedded,
 * the parent window as well.
 * @returns {void}
 */
function saveScroll() {
  savedScroll = { y: window.pageYOffset, parentY: isIframed ? parent.window.pageYOffset : 0 };
}

/**
 * @function restoreScroll
 * @description Smoothly returns this window and, when embedded, the parent window to
 * the offsets captured when the panel was expanded.
 * @returns {void}
 */
function restoreScroll() {
  if (savedScroll === null) {
    return;
  }
  window.scrollTo({ top: savedScroll.y, behavior: "smooth" });
  if (isIframed) {
    parent.window.scrollTo({ top: savedScroll.parentY, behavior: "smooth" });
  }
  savedScroll = null;
}

/**
 * @function runAfterLayout
 * @description Runs the given callback after the layout and, when embedded, the parent
 * iframe resize have settled.
 * @param {Function} callback - The function to invoke.
 * @returns {void}
 */
function runAfterLayout(callback) {
  setTimeout(() => {requestAnimationFrame(callback);}, 60);
}

/**
 * @function togglePanel
 * @description Handles expansion and collapse of the Files and Comments panels. When
 * expanded, saves the scroll position and scrolls the panel tab into view. When
 * collapsed, restores the saved scroll position.
 * @param {Event} event - The change event fired by the toggle checkbox.
 * @returns {void}
 */
function togglePanel(event) {
  const id = event.target.id;
  const panel = id === "toggle_files" ? document.getElementById("snarkFiles") :
                id === "toggle_comments" ? document.getElementById("commentSection") : null;
  if (panel === null) {
    return;
  }
  const tab = id === "toggle_files" ? "tab_files" : "tab_comments";
  if (event.target.checked) {
    saveScroll();
    runAfterLayout(() => {
      const anchor = document.getElementById(tab) || panel;
      anchor.scrollIntoView({block: "center", inline: "center", behavior: "smooth"});
    });
  } else {
    runAfterLayout(restoreScroll);
  }
}

document.addEventListener("change", togglePanel, true);
