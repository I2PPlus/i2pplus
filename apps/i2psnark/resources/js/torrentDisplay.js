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
  var active = document.querySelectorAll(".active:not(.peerinfo)");
  var inactive = document.querySelectorAll(".inactive:not(.peerinfo)");
  var downloading = document.querySelectorAll(".downloading:not(.peerinfo)");
  var seeding = document.querySelectorAll(".seeding:not(.peerinfo)");
  var complete = document.querySelectorAll(".complete:not(.peerinfo)");
  var incomplete = document.querySelectorAll(".incomplete:not(.peerinfo)");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var debuginfo = document.querySelectorAll(".debuginfo");

  var filtered = document.querySelectorAll(".filtered");

  function clean() {
    countFiltered();
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

  function countFiltered() {
    var filtered = document.querySelectorAll(".filtered");
      showResults();
  }

  function showResults() {
    var torrents = document.getElementsByClassName("snarkTorrents")[0].getElementsByTagName("tbody")[0];
    var snarkTable = document.getElementById("snarkTorrents");
    var filtered = document.querySelectorAll(".filtered");
    var results = document.querySelectorAll(".results");
    cleanResults();
    var row = torrents.insertRow(0);
    row.classList.add("results");
    row.id = "filterResults";
    var cell = row.insertCell(0);
    cell.colSpan = 12;
    var on = "";
    var peers = peerinfo.length;
    if (btnActive.checked)
      on = "active";
    else if (btnInactive.checked)
      on = "inactive";
    else if (btnDownloading.checked)
      on = "downloading";
    else if (btnSeeding.checked)
      on = "seeding";
    else if (btnComplete.checked)
      on = "completed";
    else if (btnIncomplete.checked)
      on = "incomplete";
    if (!btnAll.checked) {
      if (filtered.length == 1)
        cell.innerHTML= "Displaying " + (filtered.length) + " " + on + " torrent";
      else
        cell.innerHTML= "Displaying " + (filtered.length) + " " + on + " torrents";
    } else {
      var row = document.getElementById(filterResults);
      if (row)
        cleanResults();
        filterResults.style.display = "none";
    }
  }

  function cleanResults() {
    var results = document.querySelectorAll(".results");
    var resultsSize = results.length;
    if (resultsSize > 0) {
      results.forEach((elem) => {
        elem.remove();
      });
    }
  }

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
