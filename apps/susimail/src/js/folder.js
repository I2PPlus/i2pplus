function initButtons() {
  const buttonsMap = {
    delete1: setupDeleteClickHandler,
    markall: setupToggleSelectionHandler(true),
    clearselection: setupToggleSelectionHandler(false),
    tdclick: addDirectClickHandler,
  };

  for (const [className, handler] of Object.entries(buttonsMap)) {
    const buttons = document.getElementsByClassName(className);
    Array.from(buttons).forEach(button => handler(button, button.form));
  }

  const form = document.forms[0];
  deleteboxclicked(form);
}

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
      form.delete.disabled = !hasOne;
      form.markall.disabled = hasAll;
      form.clearselection.disabled = !hasOne;
      event.preventDefault();
    });
  };
}

function addDirectClickHandler(elem) {
  const url = elem.dataset.url;
  elem.addEventListener("click", function() { document.location = url; });
}

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