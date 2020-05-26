function initToggleHeaders() {

  const expand = document.getElementById("expand");
  const collapse = document.getElementById("collapse");
  const headers = document.getElementsByClassName("debugHeader");
  collapse.addEventListener("click", toggleHeaders, false);
  expand.addEventListener("click", toggleHeaders, false);

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
