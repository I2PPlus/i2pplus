/**
 * @module toggleAddCreate
 * @file toggleAddCreate.js - Keep the add and create torrent sections visible and restore scroll position.
 * @description Ensures the Add Torrent and Create Torrent panels are fully visible when
 * expanded and restores the prior scroll position when they are collapsed again. Works
 * both standalone and embedded in the router console iframe.
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
 * @description Scroll offsets captured when a section is expanded, used to restore
 * the position when the section is collapsed again.
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
 * the offsets captured when the section was expanded.
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
 * @function toggleSection
 * @description Handles expansion and collapse of the add and create torrent sections.
 * When expanded, saves the scroll position and scrolls the section into view. When
 * collapsed, restores the saved scroll position.
 * @param {Event} event - The change event fired by the toggle checkbox.
 * @returns {void}
 */
function toggleSection(event) {
  const id = event.target.id;
  const section = id === "toggle_addtorrent" ? document.getElementById("addSection") :
                  id === "toggle_createtorrent" ? document.getElementById("createSection") : null;
  if (section === null) {
    return;
  }
  if (event.target.checked) {
    saveScroll();
    runAfterLayout(() => {section.scrollIntoView({block: "center", inline: "center", behavior: "smooth"});});
  } else {
    runAfterLayout(restoreScroll);
  }
}

document.addEventListener("change", toggleSection, true);
