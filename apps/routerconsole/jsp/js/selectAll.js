function initSelectAll() {
  var inputs = document.getElementsByClassName("readonly");
  for (index = 0; index < inputs.length; index++) {
    var input = inputs[index];
    addSelectAllHander(input);
  }
}

function addSelectAllHander(elem) {
  elem.addEventListener("click", function() {
    selectAll(elem);
  });
}

function selectAll(element) {
  element.select();
}

document.addEventListener("DOMContentLoaded", function() {
  initSelectAll();
}, true);
