// resets scroll position of element
// use with onblur to clear scroll position when element loses focus

function resetScrollLeft(element) {
  element.scrollLeft = 0;
}

function initResetScroll() {
  var buttons = document.getElementsByClassName("resetScrollLeft");
  for (var i = 0; i < buttons.length; i++) {
    buttons[i].addEventListener("blur", function() {
      resetScrollLeft(this);
    });
  }
}

document.addEventListener("DOMContentLoaded", initResetScroll);
