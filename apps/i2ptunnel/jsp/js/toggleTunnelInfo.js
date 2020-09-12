function initToggleInfo() {
  var toggle = document.getElementById("toggleInfo");
  var i;
  var x = document.getElementsByClassName("tunnelInfo");
  for (i = 0; i < x.length; i++) {
    if (x[i].style.display === "none") {
      x[i].style.display = "table-row";
      toggle.innerHTML = "<img src='/themes/console/dark/images/sort_up.png' title='Hide Tunnel Info'>";
    } else {
      x[i].style.display = "none";
      toggle.innerHTML = "<img src='/themes/console/dark/images/sort_down.png' title='Show Tunnel Info'>";
    }
  }
}

export {initToggleInfo};