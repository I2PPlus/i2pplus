/* torrentDisplay.js by dr|3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import {onVisible} from "./onVisible.js";

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
  var tfoot = document.getElementById("snarkFoot");
  var badge = document.getElementById("filtercount");
  var badges = document.querySelectorAll("#filtercount.badge");
  var torrentform = document.getElementById("torrentlist");
  var rules = ".rowOdd,.rowEven,.peerinfo,.debuginfo{visibility:collapse}";
  var xhrfilter = new XMLHttpRequest();
  var xhrsnark = new XMLHttpRequest();

  var filterbar = document.getElementById("torrentDisplay");
  var filtered = document.querySelectorAll(".filtered");
  var pagenav = document.getElementById("pagenavtop");
  var query = window.location.search;
  var path = window.location.pathname;
  var storageFilter = "filter";
  if (!path.endsWith("i2psnark/")) {
    storageFilter = "filter_" + path.replace("/", "");
  }
  var storage = window.localStorage.getItem(storageFilter);

  checkIfActive();

  function checkIfActive() {
    if (!storage || btnAll.checked === true) {
      btnAll.checked = true;
      if (torrentform) {
        torrentform.classList.remove("filterbarActive");
      }
    } else {
      if (torrentform && !torrentform.classList.contains("filterbarActive")) {
        torrentform.classList.add("filterbarActive");
      }
    }
  }

  function clean() {
    var cssfilter = document.getElementById("cssfilter");
    checkIfActive();
    if (cssfilter.innerText !== null) {cssfilter.innerText = "";}
    if (badge !== null) {badge.innerHTML = "";}
    allOdd.forEach((element) => {element.classList.remove("filtered");});
    allEven.forEach((element) => {element.classList.remove("filtered");});
    if (pagenav) {
      if (storage !== null) {pagenav.style.display = "none";}
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
    var stylesheet = document.getElementById("cssfilter");
    stylesheet.innerText = rules;
  }

  function showAll() {
    clean();
    checkPagenav();
    if (pagenav !== null) {
      pagenav.removeAttribute("hidden");
      pagenav.style.display = "";
    }
    var query = window.location.search;
    window.localStorage.removeItem(storageFilter);
    btnAll.checked = true;
    btnAll.style.pointerEvents = "none";
  }

  function showActive() {
    clean();
    var state = ".active{visibility:visible}";
    rules += state;
    injectCSS();
    btnActive.checked = true;
    window.localStorage.setItem(storageFilter, btnActive.id);
    active.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showInactive() {
    clean();
    var state = ".inactive{visibility:visible}";
    rules += state;
    injectCSS();
    btnInactive.checked = true;
    window.localStorage.setItem(storageFilter, btnInactive.id);
    inactive.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showDownloading() {
    clean();
    var state = ".downloading{visibility:visible}";
    rules += state;
    injectCSS();
    btnDownloading.checked = true;
    window.localStorage.setItem(storageFilter, btnDownloading.id);
    downloading.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showSeeding() {
    clean();
    var state = ".seeding{visibility:visible}";
    rules += state;
    injectCSS();
    btnSeeding.checked = true;
    window.localStorage.setItem(storageFilter, btnSeeding.id);
    seeding.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showComplete() {
    clean();
    var state = ".complete{visibility:visible}";
    rules += state;
    injectCSS();
    btnComplete.checked = true;
    window.localStorage.setItem(storageFilter, btnComplete.id);
    complete.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showIncomplete() {
    clean();
    var state = ".incomplete{visibility:visible}";
    rules += state;
    injectCSS();
    btnIncomplete.checked = true;
    window.localStorage.setItem(storageFilter, btnIncomplete.id);
    incomplete.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showStopped() {
    clean();
    var state = ".stopped{visibility:visible}";
    rules += state;
    injectCSS();
    btnStopped.checked = true;
    window.localStorage.setItem(storageFilter, btnStopped.id);
    stopped.forEach((element) => {element.classList.add("filtered");});
    var count = filtered.length;
    showBadge();
  }

  function onClick() {
    if (xhrsnark.status !== null) {xhrsnark.abort();}
    if (xhrfilter.status !== null) {xhrfilter.abort();}
    refreshFilters();
  }

  if (filterbar) {
     btnAll.addEventListener("click", () => {onClick();showAll();});
     btnActive.addEventListener("click", () => {onClick();showActive();});
     btnInactive.addEventListener("click", () => {onClick();showInactive();});
     btnDownloading.addEventListener("click", () => {onClick();showDownloading();});
     btnSeeding.addEventListener("click", () => {onClick();showSeeding();});
     btnComplete.addEventListener("click", () => {onClick();showComplete();});
     btnIncomplete.addEventListener("click", () => {onClick();showIncomplete();});
     btnStopped.addEventListener("click", () => {onClick();showStopped();});
     switch (window.localStorage.getItem(storageFilter)) {
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
  var sortIcon = document.querySelectorAll(".sortIcon");

  if (filterbar) {
    initFilterBar();
    checkPagenav();
    refreshFilters();
  }

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
  var path = window.location.pathname;
  var screenlog = document.getElementById("messages");
  var storageFilter = "filter";
  if (!path.endsWith("i2psnark/")) {
    storageFilter = "filter_" + path.replace("/", "");
  }
  var storage = window.localStorage.getItem(storageFilter);
  if (pagenav !== null) {
    if (storage !== null) {
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
  var xhrfilter = new XMLHttpRequest();
  var xhrsnark = new XMLHttpRequest();
  var filterbar = document.getElementById("torrentDisplay");
  var headers = new Headers();
  var pagenav = document.getElementById("pagenavtop");
  var pagesize = headers.get("X-Snark-Pagesize");
  var path = window.location.pathname;
  var query = window.location.search;
  var storageFilter = "filter";
  if (!path.endsWith("i2psnark/")) {storageFilter = "filter_" + path.replace("/", "");}
  var storage = window.localStorage.getItem(storageFilter);
  var url = ".ajax/xhr1.html";
  checkPagenav();

  if (query) {
    if (storage !== null && filterbar) {
      url += query + "&ps=9999";
    } else {
      url += query;
    }
  } else if (storage !== null && filterbar) {
    url += "?ps=9999";
  }
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
        if (pagenav && !storage) {
          checkPagenav();
          var pagenavResponse = xhrfilter.responseXML.getElementById("pagenavtop");
          if (pagenavResponse !== null && pagenav.innerHTML !== pagenavResponse.innerHTML) {pagenav.innerHTML = pagenavResponse.innerHTML;}
        }
        if (filterbar) {
          var filterbarResponse = xhrfilter.responseXML.getElementById("torrentDisplay");
          if (!filterbar && filterbarResponse !== null) {filterbar.outerHTML = filterbarResponse.outerHTML;}
          else if (filterbar && filterBarResponse && filterbar.innerHTML !== filterbarResponse.innerHTML) {
            filterbar.innerHTML = filterbarResponse.innerHTML;
          }
        }
        if (screenlog) {
          var screenlogResponse = xhrfilter.responseXML.getElementById("messages");
          if (screenlogResponse != null && screenlog.innerHTML !== screenlogResponse.innerHTML) {
            screenlog.innerHTML = screenlogResponse.innerHTML;
          }
        }
      }
    }
  };
  xhrfilter.send();
}

function checkIfVisible() {
  var torrentform = document.getElementById("torrentlist");
  onVisible(torrentform, () => {checkFilterBar();});
}

document.addEventListener("DOMContentLoaded", checkIfVisible(), true);

export {initFilterBar, checkFilterBar, refreshFilters};