function toggleHeaders() {
  var headers = document.getElementsByClassName('debugHeader');
  var expand = document.getElementById('expand');
  var collapse = document.getElementById('collapse');
  collapse.style.display == 'inline-block';
  expand.style.display == 'none';
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