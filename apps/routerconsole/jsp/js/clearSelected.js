/**
 * @module clearSelected
 * @description Removes the "tab2" CSS class from all elements with class "togglestat"
 * when a click handler is triggered.
 * @license AGPL3 or later
 */
(function initClearSelected() {

  /**
   * Initializes click handlers to clear selected tab styling from toggle elements.
   * @function init
   * @returns {void}
   */
  function init() {

      /**
       * Removes the "tab2" class from all elements with class "togglestat".
       * @function clearSelected
       * @returns {void}
       */
      function clearSelected() {
          const elements = document.getElementsByClassName("togglestat");
          for (let i = 0; i < elements.length; i++) {
              elements[i].classList.remove("tab2");
          }
      }
  }

  document.addEventListener("DOMContentLoaded", init);

  // these stay on the window: other scripts and the page's inline
  // handlers reach them there
  window.init = init;
}());
