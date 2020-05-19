// setup torrent display buttons so we can show/hide snarks based on status

function initFilterBar() {

  var btnAll = document.getElementById("all");
  var btnActive = document.getElementById("active");
  var btnInactive = document.getElementById("inactive");
  var btnDownloading = document.getElementById("downloading");
  var btnSeeding = document.getElementById("seeding");
  var btnComplete = document.getElementById("complete");
  var btnIncomplete = document.getElementById("incomplete");

  var allOdd = document.querySelectorAll(".snarkTorrentOdd");
  var allEven = document.querySelectorAll(".snarkTorrentEven");
  var active = document.querySelectorAll(".active");
  var inactive = document.querySelectorAll(".inactive");
  var downloading = document.querySelectorAll(".downloading");
  var seeding = document.querySelectorAll(".seeding");
  var complete = document.querySelectorAll(".complete");
  var incomplete = document.querySelectorAll(".incomplete");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var debuginfo = document.querySelectorAll(".debuginfo");

  var filtered = document.querySelectorAll(".filtered");

  function clean() {
    var filter = document.getElementById("filter");
    if (filter) {
        filter.remove();
    }
    allOdd.forEach((element) => {
      element.classList.remove("filtered");
    });
    allEven.forEach((element) => {
      element.classList.remove("filtered");
    });
  }

/*  function noResults() {
    var torrents = document.getElementsByClassName("snarkTorrents")[0].getElementsByTagName("tbody")[0];
    var row = torrents.insertRow(torrents.rows.length);
    var cell = row.insertCell(0);
    cell.colSpan = 12;
    cell.innerHTML= "No results";
  }*/

  function showAll() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/all.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnAll.checked = true;
  }

  function showActive() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/active.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnActive.checked = true;
    active.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showInactive() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/inactive.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnInactive.checked = true;
    inactive.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showDownloading() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/downloading.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnDownloading.checked = true;
    downloading.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showSeeding() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/seeding.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnSeeding.checked = true;
    seeding.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showComplete() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/complete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnComplete.checked = true;
    complete.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showIncomplete() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/incomplete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnIncomplete.checked = true;
    incomplete.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  btnAll.addEventListener("click", showAll, false);
  btnActive.addEventListener("click", showActive, false);
  btnInactive.addEventListener("click", showInactive, false);
  btnDownloading.addEventListener("click", showDownloading, false);
  btnSeeding.addEventListener("click", showSeeding, false);
  btnComplete.addEventListener("click", showComplete, false);
  btnIncomplete.addEventListener("click", showIncomplete, false);

}

var main = document.getElementById("mainsection");
var bar = document.getElementById("torrentDisplay");
if (bar) {
  main.addEventListener("mouseover", function() {
    initFilterBar();
  }, false);
}
