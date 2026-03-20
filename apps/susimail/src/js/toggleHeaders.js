/**
 * @module toggleHeaders
 * @file SusiMail toggle-headers utility.
 * Provides expand/collapse controls for showing or hiding debug email
 * headers in the message view.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Initialises the expand/collapse toggle buttons for debug headers.
 * @function initToggleHeaders
 * @returns {void}
 */
function initToggleHeaders() {

  const expand = document.getElementById("expand");
  const collapse = document.getElementById("collapse");
  const headers = document.getElementsByClassName("debugHeader");
  collapse.addEventListener("click", toggleHeaders, false);
  expand.addEventListener("click", toggleHeaders, false);

  /**
   * Toggles the display of each debug header row between "table-row"
   * and "none", and swaps the visibility of the expand/collapse buttons.
   *
   * @function toggleHeaders
   * @returns {void}
   */
  function toggleHeaders() {
    collapse.style.display == 'none';
    expand.style.display == 'inline-block';
    for (var i = 0; i < headers.length; i++) {
      headers[i].style.display = headers[i].style.display == 'table-row' ? 'none' : 'table-row';
      if (headers[i].style.display == 'table-row') {
        collapse.style.display = 'inline-block';
        expand.style.display = 'none';
      } else {
        collapse.style.display = 'none';
        expand.style.display = 'inline-block';
      }
    }
  }
}

document.addEventListener("DOMContentLoaded", function() {
  initToggleHeaders();
}, false);
