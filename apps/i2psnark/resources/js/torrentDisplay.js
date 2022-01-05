// setup torrent display buttons so we can show/hide snarks based on status

var bar = document.getElementById("torrentDisplay");

function initFilterBar() {

  var btnAll = document.getElementById("all");
  var btnActive = document.getElementById("active");
  var btnInactive = document.getElementById("inactive");
  var btnDownloading = document.getElementById("downloading");
  var btnSeeding = document.getElementById("seeding");
  var btnComplete = document.getElementById("complete");
  var btnIncomplete = document.getElementById("incomplete");
  var btnStopped = document.getElementById("stopped");

  var allOdd = document.querySelectorAll(".rowOdd");
  var allEven = document.querySelectorAll(".rowEven");
  var active = document.querySelectorAll(".active:not(.peerinfo)");
  var inactive = document.querySelectorAll(".inactive:not(.peerinfo)");
  var downloading = document.querySelectorAll(".downloading");
  var seeding = document.querySelectorAll(".seeding");
  var complete = document.querySelectorAll(".complete");
  var incomplete = document.querySelectorAll(".incomplete");
  var stopped = document.querySelectorAll(".stopped");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var debuginfo = document.querySelectorAll(".debuginfo");
  var tfoot = document.getElementById("snarkFoot");
  if (tfoot)
    var tfootInner = tfoot.getElementsByClassName("tr")[0];

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
    var tbody = document.getElementById("snarkTbody");
    var filtered = document.querySelectorAll(".filtered");
    var results = document.querySelectorAll(".results");
    cleanResults();
    var row = tbody.insertRow(0);
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
    else if (btnStopped.checked)
      on = "stopped";
    if (!btnAll.checked) {
        if (filtered.length == 1)
          cell.innerHTML= "Displaying " + (filtered.length) + " " + on + " torrent";
        else
          cell.innerHTML= "Displaying " + (filtered.length) + " " + on + " torrents";
    } else {
      var filterResults = document.getElementById("filterResults");
      if (filterResults)
        cleanResults();
      filterResults.style.display = "none";
    }
  }

  function cleanResults() {
    var results = document.querySelectorAll(".results");
    var resultsSize = results.length;
    if (resultsSize > 0) {
      results.forEach((elem) => {
        if (elem != tfootInner)
          elem.remove();
      });
    }
  }

  function showAll() {
    clean();
    var css = document.createElement("link");
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/all.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnAll.checked = true;
  }

  function showActive() {
    clean();
    var css = document.createElement("link");
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/active.css";
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
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/inactive.css";
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
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/downloading.css";
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
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/seeding.css";
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
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/complete.css";
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
    css.type = "text/css";
    css.rel = "stylesheet";
    css.href = ".resources/filters/incomplete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnIncomplete.checked = true;
    incomplete.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showStopped() {
    clean();
    var css = document.createElement("link");
    css.type ="text/css";
    css.rel ="stylesheet";
    css.href =".resources/filters/stopped.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnStopped.checked = true;
    stopped.forEach((element) => {
      element.classList.add("filtered");
    });
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
  }

}

if (typeof bar !== "undefined" || bar) {
  document.addEventListener("mouseover", function() {
    initFilterBar();
  }, false);
}
