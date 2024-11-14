function initButtons() {
  const buttonsMap = {
    delete1: addClickHandler1,
    markall: addClickHandler2,
    clearselection: addClickHandler3,
    tdclick: addClickHandler4,
  };

  for (const [className, handler] of Object.entries(buttonsMap)) {
    const buttons = document.getElementsByClassName(className);
    for (let index = 0; index < buttons.length; index++) {
      const button = buttons[index];
      handler(button);
    }
  }

  const form = document.forms[0];
  deleteboxclicked(form);
}

function addClickHandler1(elem) {
  elem.addEventListener("click", function() {
    deleteboxclicked(this.form);
  });
}

function addClickHandler2(elem) {
  elem.addEventListener("click", function() {
    const form = this.form;
    form.delete.disabled = false;
    form.markall.disabled = true;
    form.clearselection.disabled = false;
    const buttons = document.getElementsByClassName("delete1");
    for (let index = 0; index < buttons.length; index++) {
      buttons[index].checked = true;
    }
    event.preventDefault();
  });
}

function addClickHandler3(elem) {
  elem.addEventListener("click", function() {
    const form = this.form;
    form.delete.disabled = true;
    form.markall.disabled = false;
    form.clearselection.disabled = true;
    const buttons = document.getElementsByClassName("delete1");
    for (let index = 0; index < buttons.length; index++) {
      buttons[index].checked = false;
    }
    event.preventDefault();
  });
}

function addClickHandler4(elem) {
  elem.addEventListener("click", function() {
    document.location = elem.dataset.url;
  });
}

function deleteboxclicked(form) {
  let hasOne = false;
  let hasAll = true;
  let hasNone = true;

  for (let i = 0; i < form.elements.length; i++) {
    const elem = form.elements[i];
    if (elem.type === "checkbox") {
      if (elem.checked) {
        hasOne = true;
        hasNone = false;
      } else {
        hasAll = false;
      }
    }
  }

  form.delete.disabled = !hasOne;
  form.markall.disabled = hasAll;
  form.clearselection.disabled = hasNone;
}

document.addEventListener("DOMContentLoaded", function() {
  initButtons();
}, true);