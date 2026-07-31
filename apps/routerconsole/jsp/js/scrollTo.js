/**
 * @module scrollTo
 * @description Native smooth-scrolling utilities for click-triggered
 * navigation scrolling. Replaces the legacy setTimeout-based polyfill.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Smoothly scrolls the given target element into view.
 * @function smoothScroll
 * @param {HTMLElement} target - The element to scroll to
 * @returns {void}
 */
window.smoothScroll = function(target) {
  if (!target) { return; }
  target.scrollIntoView({behavior: "smooth", block: "start"});
};

/**
 * Attaches click handlers to all "scrollToNav" elements that smooth-scroll
 * the page back to the top, e.g. for submit buttons in long forms.
 * @function initScrollers
 * @returns {void}
 */
function initScrollers() {
  const inputs = document.getElementsByClassName("scrollToNav");
  for (let i = 0; i < inputs.length; i++) {
    inputs[i].addEventListener("click", function() {
      window.scrollTo({top: 0, behavior: "smooth"});
    });
  }
}

document.addEventListener("DOMContentLoaded", function() {
  initScrollers();
}, false);
