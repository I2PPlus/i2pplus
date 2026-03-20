/**
 * @module selectAll
 * @description Selects all text in readonly input fields when clicked,
 * facilitating easy copy operations.
 * @license AGPL3 or later
 */

/**
 * Initializes click handlers on readonly input elements to select their text content.
 * @function initSelectAll
 * @returns {void}
 */
function initSelectAll() {
  var inputs = document.getElementsByClassName("readonly");
  for (index = 0; index < inputs.length; index++) {
    var input = inputs[index];
    addSelectAllHander(input);
  }
}

/**
 * Attaches a click handler that selects all text in the element.
 * @function addSelectAllHander
 * @param {HTMLElement} elem - The element to attach the handler to
 * @returns {void}
 */
function addSelectAllHander(elem) {
  elem.addEventListener("click", function() {
    selectAll(elem);
  });
}

/**
 * Selects all text content within the given input element.
 * @function selectAll
 * @param {HTMLInputElement} element - The input element to select text in
 * @returns {void}
 */
function selectAll(element) {
  element.select();
}

document.addEventListener("DOMContentLoaded", initSelectAll);
