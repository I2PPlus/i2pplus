/**
 * @module delete
 * @file delete.js - Confirmation dialog for tunnel deletion in I2PTunnel Manager
 * @description Attaches click handlers to delete buttons to prompt user confirmation before deletion
 * @license AGPL3 or later
 */

/**
 * Initializes delete button click handlers on DOM ready.
 * Finds all elements with class "delete" and attaches confirmation handlers.
 * @function init
 * @returns {void}
 */
function init() {
  var buttons = document.getElementsByClassName("delete");
  for (index = 0; index < buttons.length; index++) {
    var button = buttons[index];
    addClickHandler(button);
  }
}

/**
 * Attaches a click event listener that shows a confirmation dialog.
 * If the user cancels, the default action (navigation) is prevented.
 * @function addClickHandler
 * @param {HTMLElement} elem - The element to attach the click handler to
 * @returns {void}
 * @example
 * addClickHandler(document.querySelector('.delete'));
 */
function addClickHandler(elem) {
  elem.addEventListener("click", function() {
    if (!confirm(deleteMessage)) {
      event.preventDefault();
      return false;
    }
  });
}

document.addEventListener("DOMContentLoaded", init);
