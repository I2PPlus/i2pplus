/**
 * @module resetScroll
 * @description Resets the horizontal scroll position of elements with the
 * "resetScrollLeft" class when they lose focus.
 * @license AGPL3 or later
 */

/**
 * Resets the horizontal scroll position of an element to 0.
 * @function resetScrollLeft
 * @param {HTMLElement} element - The element to reset scroll position for
 * @returns {void}
 */
function resetScrollLeft(element) {
  element.scrollLeft = 0;
}

/**
 * Initializes blur event handlers on all "resetScrollLeft" elements.
 * @function initResetScroll
 * @returns {void}
 */
function initResetScroll() {
  var buttons = document.getElementsByClassName("resetScrollLeft");
  for (var i = 0; i < buttons.length; i++) {
    buttons[i].addEventListener("blur", function() {
      resetScrollLeft(this);
    });
  }
}

document.addEventListener("DOMContentLoaded", initResetScroll);
