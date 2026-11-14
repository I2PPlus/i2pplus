/**
 * @module resetScroll
 * @description Resets the horizontal scroll position of elements with the
 * "resetScrollLeft" class when they lose focus.
 * @license AGPL3 or later
 */
(function resetScroll() {

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
    const buttons = document.getElementsByClassName("resetScrollLeft");
    for (let i = 0; i < buttons.length; i++) {
      buttons[i].addEventListener("blur", function() {
        resetScrollLeft(this);
      });
    }
  }

  document.addEventListener("DOMContentLoaded", initResetScroll);

  // these stay on the window: other scripts and the page's inline
  // handlers reach them there
  window.resetScrollLeft = resetScrollLeft;
}());
