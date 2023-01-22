/* torrentDisplay.js by dr|3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

var bar = document.getElementById("torrentDisplay");
var count;
var filtered = document.querySelectorAll(".filtered");
var pagenav = document.getElementById("pagenavtop");
var query = window.location.search;
var storage = localStorage.getItem("filter");
if (filtered !== null) {
  count = filtered.length;
  if (count < 2) {
    count = "";
  }
} else {
  count = "";
}

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
  var filterResults = document.getElementById("filterResults");
  var pagenav = document.getElementById("pagenavtop");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var seeding = document.querySelectorAll(".seeding");
  var stopped = document.querySelectorAll(".stopped");
  var storage = window.localStorage.getItem("filter");
  var tfoot = document.getElementById("snarkFoot");

  if (!storage) {
    btnAll.checked = true;
  }

  if (tfoot !== null) {
    var tfootInner = tfoot.getElementsByClassName("tr")[0];
  }

  function clean() {
    var cssfilter = document.getElementById("cssfilter");
    if (cssfilter) {
      cssfilter.remove();
    }
    allOdd.forEach((element) => {
      element.classList.remove("filtered");
    });
    allEven.forEach((element) => {
      element.classList.remove("filtered");
    });
    if (pagenav) {
      if (storage && storage != "all") {
        pagenav.style.display = "none";
      } else {
        pagenav.style.display = "none";
      }
    }
  }

  function countFiltered() {
    var dupe = document.querySelector("#filtercount+#filtercount");
    if (dupe) {
      dupe.remove();
    }
    setTimeout(displayBadge, 3000);
  }

  function displayBadge() {
    var activeFilter = document.querySelector("#torrentDisplay input:checked + label");
    var filtercount = document.querySelector("#torrentDisplay input + label span");
    var filtered = document.querySelectorAll(".filtered");
    var results = document.querySelectorAll(".filtered");
    if (filtercount) {
      if ((!storage || storage !== "all")) {
        setTimeout(() => {
          count = results.length;
          filtercount.hidden = false;
          if (count > 0) {
            filtercount.innerHTML = count;
          }
        }, 1000);
      }
    } else {
      activeFilter.innerHTML += "<span id=filtercount hidden></span>";
    }
  }

  function showAll() {
    clean();
    checkPagenav();
    pagenav.removeAttribute("hidden");
    pagenav.style.display = "";
    var query = window.location.search;
    window.localStorage.removeItem("filter");
    btnAll.checked = true;
    btnAll.style.pointerEvents = "none";
  }

  var rules = ".rowOdd,.rowEven,.peerinfo,.debuginfo{display:none}#torrents tfoot tr:first-child th{border-top:var(--border_filtered)!important}#snarkFoot tr{display:table-row}";

  function injectCSS() {
    var stylesheet = "<style type=text/css id=cssfilter>" + rules + "</style>";
    document.head.innerHTML += stylesheet;
  }

  function showActive() {
    clean();
    var state = ".active{display:table-row}";
    rules += state;
    injectCSS();
    btnActive.checked = true;
    window.localStorage.setItem("filter", btnActive.id);
    active.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showInactive() {
    clean();
    var state = ".inactive{display:table-row}";
    rules += state;
    injectCSS();
    btnInactive.checked = true;
    window.localStorage.setItem("filter", btnInactive.id);
    inactive.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showDownloading() {
    clean();
    var state = ".downloading{display:table-row}";
    rules += state;
    injectCSS();
    btnDownloading.checked = true;
    window.localStorage.setItem("filter", btnDownloading.id);
    downloading.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showSeeding() {
    clean();
    var state = ".seeding{display:table-row}";
    rules += state;
    injectCSS();
    btnSeeding.checked = true;
    window.localStorage.setItem("filter", btnSeeding.id);
    seeding.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showComplete() {
    clean();
    var state = ".complete{display:table-row}";
    rules += state;
    injectCSS();
    btnComplete.checked = true;
    window.localStorage.setItem("filter", btnComplete.id);
    complete.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showIncomplete() {
    clean();
    var state = ".incomplete{display:table-row}";
    rules += state;
    injectCSS();
    btnIncomplete.checked = true;
    window.localStorage.setItem("filter", btnIncomplete.id);
    incomplete.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  function showStopped() {
    clean();
    var state = ".stopped{display:table-row}";
    rules += state;
    injectCSS();
    btnStopped.checked = true;
    window.localStorage.setItem("filter", btnStopped.id);
    stopped.forEach((element) => {
      element.classList.add("filtered");
    });
  }

  if (bar) {
     btnAll.addEventListener("click", () => {showAll();doRefresh();});
     btnActive.addEventListener("click", () => {showActive();doRefresh();countFiltered();});
     btnInactive.addEventListener("click", () => {showInactive();doRefresh();countFiltered();});
     btnDownloading.addEventListener("click", () => {showDownloading();doRefresh();countFiltered();});
     btnSeeding.addEventListener("click", () => {showSeeding();doRefresh();countFiltered();});
     btnComplete.addEventListener("click", () => {showComplete();doRefresh();countFiltered();});
     btnIncomplete.addEventListener("click", () => {showIncomplete();doRefresh();countFiltered();});
     btnStopped.addEventListener("click", () => {showStopped();doRefresh();countFiltered();});
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
     countFiltered();
  }
}

function checkFilterBar() {

  var pagenav = document.getElementById("pagenavtop");
  var query = window.location.search;
  var storage = window.localStorage.getItem("filter");

  function setQuery() {
    if (query) {
      window.localStorage.setItem("queryString", window.location.search);
      window.location.search = query;
    }
  }

  if (bar !== null) {
    initFilterBar();
    checkPagenav();
  }

  var sortIcon = document.querySelectorAll(".sortIcon");
  sortIcon.forEach(function (item) {
    item.addEventListener("click", () => {
      setQuery();
    });
  });

}

function checkPagenav() {
  if (pagenav) {
    if ((storage && storage !== "all")) {
      pagenav.style.display = "none";
    } else {
      pagenav.style.display = "block";
      pagenav.removeAttribute("hidden");
    }
  }
}

function doRefresh() {
  checkPagenav();
  var headers = new Headers();
  var pagesize = headers.get('X-Snark-Pagesize');
  var results = document.getElementById("filterResults");
  if (results) {
    setTimeout(() => {results.remove();}, 3000);
  }
  var url = ".ajax/xhr1.html";
  if (!storage && query && pagesize !== null) {
    url = query + "&ps=" + pagesize;
  } else if (!storage && pagesize !== null) {
    url = "?ps=" + pagesize;
  } else if ((storage && storage !== "all") && query == "") {
    url += "?ps=9999";
  } else if ((storage && storage !== "all") && query !== "") {
    url += query + "&ps=9999";
  }

  var xhrsnarkfilter = new XMLHttpRequest();
  xhrsnarkfilter.responseType = "document";
  xhrsnarkfilter.open('GET', url, true);
  xhrsnarkfilter.onreadystatechange = function() {
    if (xhrsnarkfilter.readyState === 4) {
      if (xhrsnarkfilter.status === 200) {
        var torrents = document.getElementById("torrents");
        var torrentsResponse = xhrsnarkfilter.responseXML.getElementById("torrents");
        torrents.outerHTML = torrentsResponse.outerHTML;
        if (pagenav && (!storage || storage == "all")) {
            var pagenavResponse = xhrsnarkfilter.responseXML.getElementById("pagenavtop");
            pagenav.outerHTML = pagenavResponse.outerHTML;
            checkPagenav();
        }
      }
    }
  }
  xhrsnarkfilter.send();
}

checkFilterBar();
doRefresh();

export {initFilterBar, checkFilterBar, doRefresh};