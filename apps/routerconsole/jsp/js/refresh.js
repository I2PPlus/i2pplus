// autorefresh every 10 seconds

function refresh() {
  setTimeout("location.reload(true);", 10000);
}

window.onload = refresh();