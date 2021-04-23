function initToggleInfo() {
  var toggle = document.getElementById("toggleInfo");
  var i;
  var x = document.getElementsByClassName("tunnelInfo");
  for (i = 0; i < x.length; i++) {
    if (x[i].style.display === "none") {
      x[i].style.display = "table-row";
      toggle.innerHTML = "<img src='/themes/console/dark/images/collapse_hover.svg' title='Hide Tunnel Info'>";
      toggle.classList.add('collapse');
    } else {
      x[i].style.display = "none";
      toggle.innerHTML = "<img src='/themes/console/dark/images/expand_hover.svg' title='Show Tunnel Info'>";
      toggle.classList.remove('collapse');
    }
  }
}

export {initToggleInfo};