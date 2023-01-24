/* torrentDisplay.js by dr|3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

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
  var debuginfo = document.querySelectorAll(".debuginfo");
  var downloading = document.querySelectorAll(".downloading");
  var inactive = document.querySelectorAll(".inactive:not(.peerinfo)");
  var incomplete = document.querySelectorAll(".incomplete");
  var pagenav = document.getElementById("pagenavtop");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var seeding = document.querySelectorAll(".seeding");
  var stopped = document.querySelectorAll(".stopped");
  var storage = window.localStorage.getItem("filter");
  var tfoot = document.getElementById("snarkFoot");
  var badge = document.getElementById("filtercount");
  var badges = document.querySelectorAll("#filtercount.badge");
  var rules = ".rowOdd,.rowEven,.peerinfo,.debuginfo{visibility:collapse}";

  var filterbar = document.getElementById("torrentDisplay");
  var filtered = document.querySelectorAll(".filtered");
  var pagenav = document.getElementById("pagenavtop");
  var query = window.location.search;
  var storage = localStorage.getItem("filter");

  if (!storage) {btnAll.checked = true;}

  function clean() {
    var cssfilter = document.getElementById("cssfilter");
    if (badge !== null) {badge.innerHTML = "";}
    if (cssfilter) {cssfilter.remove();}
    allOdd.forEach((element) => {element.classList.remove("filtered");});
    allEven.forEach((element) => {element.classList.remove("filtered");});
    if (pagenav) {
      if (storage && storage !== "all") {pagenav.style.display = "none";}
      else {pagenav.style.display = "";}
    }
    if (badge) {badges.forEach((element) => {element.remove();});}
  }

  function showBadge() {
    var filtered = document.querySelectorAll(".filtered");
    var activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");
    var inactiveFilters = document.querySelectorAll("#torrentDisplay input:not(checked) + .filterbutton");
    var count = filtered.length;
    activeFilter.classList.add("enabled");
    activeFilter.innerHTML += "<span id=filtercount class=badge></span>";
    if (count > 0) {
      var badge = document.getElementById("filtercount");
      badge.innerHTML = count;
    }
  }

  function injectCSS() {
    var stylesheet = "<style type=text/css id=cssfilter>" + rules + "</style>";
    document.head.innerHTML += stylesheet;
  }

  function showAll() {
    clean();
    checkPagenav();
    if (pagenav !== null) {
      pagenav.removeAttribute("hidden");
      pagenav.style.display = "";
    }
    var query = window.location.search;
    window.localStorage.removeItem("filter");
    btnAll.checked = true;
    btnAll.style.pointerEvents = "none";
  }

  function showActive() {
    clean();
    var state = ".active{visibility:visible}";
    rules += state;
    injectCSS();
    btnActive.checked = true;
    window.localStorage.setItem("filter", btnActive.id);
    active.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showInactive() {
    clean();
    var state = ".inactive{visibility:visible}";
    rules += state;
    injectCSS();
    btnInactive.checked = true;
    window.localStorage.setItem("filter", btnInactive.id);
    inactive.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showDownloading() {
    clean();
    var state = ".downloading{visibility:visible}";
    rules += state;
    injectCSS();
    btnDownloading.checked = true;
    window.localStorage.setItem("filter", btnDownloading.id);
    downloading.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showSeeding() {
    clean();
    var state = ".seeding{visibility:visible}";
    rules += state;
    injectCSS();
    btnSeeding.checked = true;
    window.localStorage.setItem("filter", btnSeeding.id);
    seeding.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showComplete() {
    clean();
    var state = ".complete{visibility:visible}";
    rules += state;
    injectCSS();
    btnComplete.checked = true;
    window.localStorage.setItem("filter", btnComplete.id);
    complete.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showIncomplete() {
    clean();
    var state = ".incomplete{visibility:visible}";
    rules += state;
    injectCSS();
    btnIncomplete.checked = true;
    window.localStorage.setItem("filter", btnIncomplete.id);
    incomplete.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showStopped() {
    clean();
    var state = ".stopped{visibility:visible}";
    rules += state;
    injectCSS();
    btnStopped.checked = true;
    window.localStorage.setItem("filter", btnStopped.id);
    stopped.forEach((element) => {element.classList.add("filtered");});
    var count = filtered.length;
    showBadge();
  }

  if (filterbar) {
     btnAll.addEventListener("click", () => {showAll();});
     btnActive.addEventListener("click", () => {showActive();});
     btnInactive.addEventListener("click", () => {showInactive();});
     btnDownloading.addEventListener("click", () => {showDownloading();});
     btnSeeding.addEventListener("click", () => {showSeeding();});
     btnComplete.addEventListener("click", () => {showComplete();});
     btnIncomplete.addEventListener("click", () => {showIncomplete();});
     btnStopped.addEventListener("click", () => {showStopped();});
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

function checkFilterBar() {

  var filterbar = document.getElementById("torrentDisplay");
  var query = window.location.search;

  if (filterbar) {
    initFilterBar();
    checkPagenav();
  }

  var sortIcon = document.querySelectorAll(".sortIcon");
  sortIcon.forEach(function (item) {
    item.addEventListener("click", () => {
      setQuery();
    });
  });

  function setQuery() {
    if (query) {
      window.localStorage.setItem("queryString", window.location.search);
      window.location.search = query;
    }
  }

}

function checkPagenav() {
  var pagenav = document.getElementById("pagenavtop");
  var storage = window.localStorage.getItem("filter");
  if (pagenav !== null) {
    if ((storage && storage !== "all")) {
      pagenav.style.display = "none";
      pagenav.hidden = true;
    } else {
      pagenav.style.display = "block";
      pagenav.hidden = false;
      pagenav.removeAttribute("hidden");
    }
  }
}

function refreshFilters() {
  checkPagenav();
  var filterbar = document.getElementById("torrentDisplay");
  var headers = new Headers();
  var pagenav = document.getElementById("pagenavtop");
  var pagesize = headers.get("X-Snark-Pagesize");
  var query = window.location.search;
  var storage = window.localStorage.getItem("filter");
  var url = ".ajax/xhr1.html";
  if (query) {
    if (storage && filterbar) {
      url += query + "&ps=9999";
    } else {
      url += query;
    }
  } else if (storage && filterbar) {
    url += "?ps=9999";
  }

  var xhrfilter = new XMLHttpRequest();
  xhrfilter.responseType = "document";
  xhrfilter.open("GET", url, true);
  xhrfilter.onreadystatechange = function() {
    if (xhrfilter.readyState === 4) {
      if (xhrfilter.status === 200) {
        var torrents = document.getElementById("torrents");
        var torrentsResponse = xhrfilter.responseXML.getElementById("torrents");
        if (torrentsResponse !== null && torrents.innerHTML !== torrentsResponse.innerHTML) {
          torrents.outerHTML = torrentsResponse.outerHTML;
        }
        if (pagenav && (!storage || storage === "all")) {
          checkPagenav();
          var pagenavResponse = xhrfilter.responseXML.getElementById("pagenavtop");
          if (pagenavResponse !== null) {pagenav.innerHTML = pagenavResponse.innerHTML;}
        }
        if (filterbar) {
          initFilterBar();
          var filterbarResponse = xhrfilter.responseXML.getElementById("torrentDisplay");
          if (!filterbar && filterbarResponse !== null) {filterbar.outerHTML = filterbarResponse.outerHTML;}
        }
      }
    }
  };
  xhrfilter.send();
}

export {initFilterBar, checkFilterBar, refreshFilters};