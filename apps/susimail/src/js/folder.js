/**
 * @module folder
 * @file SusiMail folder view button handlers.
 * Manages delete, select-all, clear-selection, and row-click interactions
 * in the mail folder list view.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Initialises all interactive buttons in the folder view by mapping
 * CSS class names to their respective setup handlers.
 * @function initButtons
 * @returns {void}
 */
function initButtons() {
  const buttonsMap = {
    delete1: setupDeleteClickHandler,
    markall: setupToggleSelectionHandler(true),
    clearselection: setupToggleSelectionHandler(false),
    tdclick: addDirectClickHandler
  };

  for (const [className, handler] of Object.entries(buttonsMap)) {
    const buttons = document.getElementsByClassName(className);
    Array.from(buttons).forEach(button => handler(button, button.form));
  }
  const form = document.forms[0];
  deleteboxclicked(form);
}

/**
 * Attaches a click handler that enables/disables the delete button
 * based on whether any visible checked checkboxes exist.
 * @function setupDeleteClickHandler
 * @param {HTMLElement} elem - The button element to bind.
 * @param {HTMLFormElement} form - The parent form containing the checkboxes.
 * @returns {void}
 */
function setupDeleteClickHandler(elem, form) {
  elem.addEventListener("click", () => {
    const checkboxes = form.querySelectorAll(".optbox.delete1:not(.inactive)");
    const hasOne = Array.from(checkboxes).some(checkbox => {
      const row = checkbox.closest("tr");
      return !row || row.style.display !== "none" && checkbox.checked;
    });
    form.delete.disabled = !hasOne;
  });
}

/**
 * Returns a handler factory that checks or unchecks all visible
 * delete-checkboxes, and updates the state of delete/markall/clearselection
 * buttons accordingly.
 * @function setupToggleSelectionHandler
 * @param {boolean} selectAll - True to check all, false to uncheck all.
 * @returns {Function} A function accepting (elem, form) to wire up the click listener.
 * @example
 * const handler = setupToggleSelectionHandler(true);
 * handler(myButton, myForm);
 */
function setupToggleSelectionHandler(selectAll) {
  return function(elem, form) {
    elem.addEventListener("click", function(event) {
      const checkboxes = form.querySelectorAll(".optbox.delete1:not(.inactive)");
      Array.from(checkboxes).forEach(checkbox => {
        const row = checkbox.closest("tr");
        if (!row || row.style.display !== "none") {
          checkbox.checked = selectAll;
        }
      });
      const hasOne = Array.from(checkboxes).some(checkbox => {
        const row = checkbox.closest("tr");
        return !row || row.style.display !== "none" && checkbox.checked;
      });
      const hasAll = Array.from(checkboxes).every(checkbox => {
        const row = checkbox.closest("tr");
        return !row || row.style.display !== "none" && checkbox.checked;
      });
      if (!form.delete) {return;}
      form.delete.disabled = !hasOne;
      form.markall.disabled = hasAll;
      form.clearselection.disabled = !hasOne;
      event.preventDefault();
    });
  };
}

/**
 * Attaches a click handler that navigates to the URL stored in the
 * element's `data-url` attribute.
 * @function addDirectClickHandler
 * @param {HTMLElement} elem - The element whose `data-url` contains the target URL.
 * @returns {void}
 */
function addDirectClickHandler(elem) {
  const url = elem.dataset.url;
  elem.addEventListener("click", function() { document.location = url; });
}

/**
 * Evaluates the current checkbox state and updates the delete, mark-all,
 * and clear-selection button enabled/disabled states.
 * @function deleteboxclicked
 * @param {HTMLFormElement} form - The form containing the delete checkboxes.
 * @returns {void}
 */
function deleteboxclicked(form) {
  const checkboxes = form.querySelectorAll(".optbox.delete1:not(.inactive)");
  const hasOne = Array.from(checkboxes).some(checkbox => checkbox.checked);
  const hasAll = Array.from(checkboxes).every(checkbox => checkbox.checked);
  if (!form.delete) {return;}
  form.delete.disabled = !hasOne;
  form.markall.disabled = hasAll;
  form.clearselection.disabled = !hasOne;
}

document.addEventListener("DOMContentLoaded", () => { initButtons(); });