// setup torrent display buttons so we can show/hide snarks based on status

var bar = document.getElementById("torrentDisplay");
var filtered = document.querySelectorAll(".filtered");

function initFilterBar() {

  var active = document.querySelectorAll(".active:not(.peerinfo)");
  var allEven = document.querySelectorAll(".rowEven");
  var allOdd = document.querySelectorAll(".rowOdd");
  var btnActive = document.getElementById("active");
  var btnAll = document.getElementById("all");
  var btnComplete = document.getElementById("complete");
  var btnDownloading = document.getElementById("downloading");
  var btnInactive = document.getElementById("inactive");
  var btnIncomplete = document.getElementById("incomplete");
  var btnSeeding = document.getElementById("seeding");
  var btnStopped = document.getElementById("stopped");
  var complete = document.querySelectorAll(".complete");
  var css = document.createElement("link");
  var debuginfo = document.querySelectorAll(".debuginfo");
  var downloading = document.querySelectorAll(".downloading");
  var inactive = document.querySelectorAll(".inactive:not(.peerinfo)");
  var incomplete = document.querySelectorAll(".incomplete");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var seeding = document.querySelectorAll(".seeding");
  var stopped = document.querySelectorAll(".stopped");
  var tfoot = document.getElementById("snarkFoot");

  if (tfoot !== null) {
    var tfootInner = tfoot.getElementsByClassName("tr")[0];
  }

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

  function countFiltered() {
    filtered = document.querySelectorAll(".filtered");
    showResults();
  }

  function showResults() {
    var tbody = document.getElementById("snarkTbody");
    filtered = document.querySelectorAll(".filtered");
    var results = document.querySelectorAll(".results");
    cleanResults();
    var row = tbody.insertRow(0);
    row.classList.add("results");
    row.id = "filterResults";
    var cell = row.insertCell(0);
    cell.colSpan = 12;
    var on = "";
    var peers = peerinfo.length;
    if (btnActive.checked) {
      on = "active";
    } else if (btnInactive.checked) {
      on = "inactive";
    } else if (btnDownloading.checked) {
      on = "downloading";
    } else if (btnSeeding.checked) {
      on = "seeding";
    } else if (btnComplete.checked) {
      on = "completed";
    } else if (btnIncomplete.checked) {
      on = "incomplete";
    } else if (btnStopped.checked) {
      on = "stopped";
    }
    if (!btnAll.checked) {
        if (filtered.length === 1) {
          cell.innerHTML = "Displaying " + (filtered.length) + " " + on + " torrent";
        } else {
          cell.innerHTML = "Displaying " + (filtered.length) + " " + on + " torrents";
        }
    } else {
      var filterResults = document.getElementById("filterResults");
      if (filterResults) {
        cleanResults();
      }
      filterResults.style.display = "none";
    }
  }

  function cleanResults() {
    var results = document.querySelectorAll(".results");
    var resultsSize = results.length;
    if (resultsSize > 0) {
      results.forEach((elem) => {
        if (tfoot !== null && elem !== tfootInner) {
          elem.remove();
        }
      });
    }
  }

  function showAll() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/all.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnAll.checked = true;
    window.localStorage.setItem("filter", btnAll.id);
  }

  function showActive() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/active.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnActive.checked = true;
    window.localStorage.setItem("filter", btnActive.id);
    active.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  function showInactive() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/inactive.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnInactive.checked = true;
    window.localStorage.setItem("filter", btnInactive.id);
    inactive.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  function showDownloading() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/downloading.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnDownloading.checked = true;
    window.localStorage.setItem("filter", btnDownloading.id);
    downloading.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  function showSeeding() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/seeding.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnSeeding.checked = true;
    window.localStorage.setItem("filter", btnSeeding.id);
    seeding.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  function showComplete() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/complete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnComplete.checked = true;
    window.localStorage.setItem("filter", btnComplete.id);
    complete.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  function showIncomplete() {
    clean();
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/incomplete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnIncomplete.checked = true;
    window.localStorage.setItem("filter", btnIncomplete.id);
    incomplete.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  function showStopped() {
    clean();
    css.type ="text/css";
    css.rel ="stylesheet";
    css.href =".resources/filters/stopped.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnStopped.checked = true;
    window.localStorage.setItem("filter", btnStopped.id);
    stopped.forEach((element) => {
      element.classList.add("filtered");
    });
    countFiltered();
  }

  if (bar) {
     btnAll.addEventListener("click", showAll, false);
     btnActive.addEventListener("click", showActive, false);
     btnInactive.addEventListener("click", showInactive, false);
     btnDownloading.addEventListener("click", showDownloading, false);
     btnSeeding.addEventListener("click", showSeeding, false);
     btnComplete.addEventListener("click", showComplete, false);
     btnIncomplete.addEventListener("click", showIncomplete, false);
     btnStopped.addEventListener("click", showStopped, false);
     switch (window.localStorage.getItem("filter")) {
       case "all":
         btnAll.checked = true;
         showAll();
         break;
       case "active":
         btnActive.checked = true;
         showActive();
         break;
       case "inactive":
         btnInactive.checked = true;
         showInactive();
         break;
       case "downloading":
         btnDownloading.checked = true;
         showDownloading();
         break;
       case "seeding":
         btnSeeding.checked = true;
         showSeeding();
         break;
       case "complete":
         btnComplete.checked = true;
         showComplete();
         break;
       case "incomplete":
         btnIncomplete.checked = true;
         showIncomplete();
         break;
       case "stopped":
         btnStopped.checked = true;
         showStopped();
         break;
       default:
         btnAll.checked = true;
         showAll();
     }
  }
}

var query = window.location.search;
var url = location.href;

function setQuery() {
  if (query) {
    window.localStorage.setItem("queryString", url);
  }
}

function clearQuery() {
  window.localStorage.removeItem("queryString");
}

function clearFilter() {
  window.localStorage.removeItem("filter");
}

function clearLocalStorage() {
  window.localStorage.clear();
}

if (bar !== null) {
  initFilterBar();
}

var sortIcon = document.querySelectorAll(".sortIcon");
sortIcon.forEach(function (item) {
  item.addEventListener("click", () => {
    setQuery();
  });
});
