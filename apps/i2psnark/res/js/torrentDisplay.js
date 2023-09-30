/* torrentDisplay.js by dr|z3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import {refreshTorrents} from "./refreshTorrents.js";
import {onVisible} from "./onVisible.js";
import {initLinkToggler, linkToggle, attachMagnetListeners, magnetToast} from "./toggleLinks.js";

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
  var mainsection = document.getElementById("mainsection");
  var pagenav = document.getElementById("pagenavtop");
  var peerinfo = document.querySelectorAll(".peerinfo");
  var screenlog = document.getElementById("screenlog");
  var seeding = document.querySelectorAll(".seeding");
  var stopped = document.querySelectorAll(".stopped");
  var tbody = document.getElementById("snarkTbody");
  var tfoot = document.getElementById("snarkFoot");
  var toggle = document.getElementById("linkswitch");
  var badge = document.getElementById("filtercount");
  var badges = document.querySelectorAll("#filtercount.badge");
  var torrentform = document.getElementById("torrentlist");
  var rules = ".rowOdd,.rowEven,.peerinfo,.debuginfo{visibility:collapse}";

  var filterbar = document.getElementById("torrentDisplay");
  var filtered = document.querySelectorAll(".filtered");
  var pagenav = document.getElementById("pagenavtop");
  var query = window.location.search;
  var path = window.location.pathname;
  var snarkFilter = "snarkFilter";
  var storage = window.localStorage.getItem(snarkFilter);

  checkIfActive();

  function checkIfActive() {
    if (!storage && filterbar !== null) {
      btnAll.checked = true;
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
      var filterActive = storage !== null;
      if (filterActive) {pagenav.style.display = "none";}
      else {pagenav.style.display = "";}
    }

    if (badge) {badges.forEach((element) => {element.remove();});}
  }

  var filteredCount = 0;

  function showBadge() {
    var filtered = document.querySelectorAll("#snarkTbody tr.filtered:not(.peerinfo)");
    var activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");

    if (activeFilter && !activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
    }
    if (!activeFilter.querySelector("#filtercount")) {
      activeFilter.innerHTML += "<span id='filtercount' class='badge'></span>";
    }
    const badge = document.getElementById("filtercount");
    const count = filtered.length;
    if (count !== filteredCount) {
      filteredCount = count;
      badge.innerText = filteredCount;
    }
  }

  function showAll() {
    clean();
    checkPagenav();
    if (pagenav !== null) {
      pagenav.removeAttribute("hidden");
      pagenav.style.display = "";
    }
    window.localStorage.removeItem(snarkFilter);
    btnAll.checked = true;
    btnAll.classList.add("noPointer");
  }

  function showActive() {
    btnActive.checked = true;
    window.localStorage.setItem(snarkFilter, btnActive.id);
  }

  function showInactive() {
    btnInactive.checked = true;
    window.localStorage.setItem(snarkFilter, btnInactive.id);
  }

  function showDownloading() {
    btnDownloading.checked = true;
    window.localStorage.setItem(snarkFilter, btnDownloading.id);
  }

  function showSeeding() {
    btnSeeding.checked = true;
    window.localStorage.setItem(snarkFilter, btnSeeding.id);
  }

  function showComplete() {
    btnComplete.checked = true;
    window.localStorage.setItem(snarkFilter, btnComplete.id);
  }

  function showIncomplete() {
    btnIncomplete.checked = true;
    window.localStorage.setItem(snarkFilter, btnIncomplete.id);
  }

  function showStopped() {
    btnStopped.checked = true;
    window.localStorage.setItem(snarkFilter, btnStopped.id);
  }

  if (filterbar) {
    const filters = [
      { button: btnAll, filterFunction: showAll, localStorageKey: "all" },
      { button: btnActive, filterFunction: showActive, localStorageKey: "active" },
      { button: btnInactive, filterFunction: showInactive, localStorageKey: "inactive" },
      { button: btnDownloading, filterFunction: showDownloading, localStorageKey: "downloading" },
      { button: btnSeeding, filterFunction: showSeeding, localStorageKey: "seeding" },
      { button: btnComplete, filterFunction: showComplete, localStorageKey: "complete" },
      { button: btnIncomplete, filterFunction: showIncomplete, localStorageKey: "incomplete" },
      { button: btnStopped, filterFunction: showStopped, localStorageKey: "stopped" }
    ];

    filters.forEach(filter => {
      filter.button.addEventListener("click", () => {
        filter.filterFunction();
        window.localStorage.setItem(snarkFilter, filter.localStorageKey);
        clean();
        injectCSS();
        showBadge();
        refreshFilters();
      });

      if (window.localStorage.getItem(snarkFilter) === filter.localStorageKey) {
        filter.button.checked = true;
        filter.button.click();
        filter.filterFunction();
      }
    });
  }

  function injectCSS() {
    let state;
    const stylesheet = document.getElementById("cssfilter");
    const activeFilterId = document.querySelector("#torrentDisplay input:checked").id;
    if (activeFilterId !== "all") {
      const filteredRows = document.querySelectorAll(`${"#snarkTbody tr." + activeFilterId}`);
      filteredRows.forEach((element) => {element.classList.add("filtered");});
      const state = "." + activeFilterId + "{visibility:visible}";
      stylesheet.innerText = rules + state;
    } else {
       stylesheet.textContent = "";
    }
  }

}

function checkFilterBar() {
  var down = document.getElementById("down");
  var filterbar = document.getElementById("torrentDisplay");
  var noload = document.getElementById("noload");
  var query = window.location.search;
  var sortIcon = document.querySelectorAll(".sortIcon");

  if (filterbar) {
    initFilterBar();
    checkPagenav();
    refreshFilters();
  }

  if (noload || down) {
    refreshAll();
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
  magnetToast();
  initLinkToggler();
  attachMagnetListeners();
}

function checkPagenav() {
  const pagenav = document.getElementById("pagenavtop");
  const path = window.location.pathname;
  const snarkFilter = !path.endsWith("i2psnark/") ? "filter_" + path.replace("/", "") : "snarkFilter";
  const storage = window.localStorage.getItem(snarkFilter);

  if (pagenav) {
    pagenav.style.display = storage ? "none" : "block";
    pagenav.hidden = !!storage;
    pagenav.removeAttribute("hidden");
  }
}

function refreshFilters() {
  var filterbar = document.getElementById("torrentDisplay");
  var pagenav = document.getElementById("pagenavtop");
  var query = window.location.search;
  if (query.includes("ps=9999") && pagenav && filterbar) {
    checkPagenav();
    debouncedRefreshTorrents(initFilterBar);
    magnetToast();
    initLinkToggler();
    attachMagnetListeners();
  }
}

function checkIfVisible() {
  var torrentform = document.getElementById("torrentlist");
  if (torrentform !== null) {
    onVisible(torrentform, () => {checkFilterBar();});
    magnetToast();
    initLinkToggler();
    attachMagnetListeners();
  }
}

function refreshAll() {
  var filterbar = document.getElementById("torrentDisplay");
  if (filterbar) {refreshFilters();}
  magnetToast();
  initLinkToggler();
  attachMagnetListeners();
}

document.addEventListener("DOMContentLoaded", checkIfVisible);

export {initFilterBar, checkFilterBar, refreshFilters};