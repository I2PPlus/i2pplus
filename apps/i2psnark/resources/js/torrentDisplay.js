/* torrentDisplay.js by dr|3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import {refreshTorrents} from "./refreshTorrents.js";
import {onVisible} from "./onVisible.js";
import {initLinkToggler, linkToggle} from "./toggleLinks.js";

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

  async function showBadge() {
    var filtered = document.querySelectorAll(".filtered");
    var activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
    }
    if (!activeFilter.querySelector("#filtercount")) {
      activeFilter.innerHTML += "<span id=filtercount class=badge></span>";
    }
    try {
      const count = await getCount(filtered);

      if (count > 0) {
        var badge = document.getElementById("filtercount");
        badge.innerText = count;
      }
    } catch (error) {
      console.error(error);
    }
  }

  function getCount(filtered) {
    return new Promise(resolve => {
      const observer = new MutationObserver((mutations) => {
        for (let mutation of mutations) {
          if (mutation.type === "attributes" && mutation.attributeName === "class") {
            const filteredArray = Array.from(filtered);
            if (filteredArray.every(elem => elem.classList.contains("filtered"))) {
              observer.disconnect();
              resolve(filtered.length);
            }
          }
        }
      });
      filtered.forEach(elem => observer.observe(elem, {attributes: true}));
    });
  }

  function injectCSS() {
    var stylesheet = document.getElementById("cssfilter");
    stylesheet.innerText = rules;
  }

  function disableBar() {
    filterbar.classList.add("noPointer");
  }

  function enableBar() {
    filterbar.classList.remove("noPointer");
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
    clean();
    var state = ".active{visibility:visible}";
    rules += state;
    injectCSS();
    btnActive.checked = true;
    window.localStorage.setItem(snarkFilter, btnActive.id);
    active.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showInactive() {
    clean();
    var state = ".inactive{visibility:visible}";
    rules += state;
    injectCSS();
    btnInactive.checked = true;
    window.localStorage.setItem(snarkFilter, btnInactive.id);
    inactive.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showDownloading() {
    clean();
    var state = ".downloading{visibility:visible}";
    rules += state;
    injectCSS();
    btnDownloading.checked = true;
    window.localStorage.setItem(snarkFilter, btnDownloading.id);
    downloading.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showSeeding() {
    clean();
    var state = ".seeding{visibility:visible}";
    rules += state;
    injectCSS();
    btnSeeding.checked = true;
    window.localStorage.setItem(snarkFilter, btnSeeding.id);
    seeding.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showComplete() {
    clean();
    var state = ".complete{visibility:visible}";
    rules += state;
    injectCSS();
    btnComplete.checked = true;
    window.localStorage.setItem(snarkFilter, btnComplete.id);
    complete.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showIncomplete() {
    clean();
    var state = ".incomplete{visibility:visible}";
    rules += state;
    injectCSS();
    btnIncomplete.checked = true;
    window.localStorage.setItem(snarkFilter, btnIncomplete.id);
    incomplete.forEach((element) => {element.classList.add("filtered");});
    showBadge();
  }

  function showStopped() {
    clean();
    var state = ".stopped{visibility:visible}";
    rules += state;
    injectCSS();
    btnStopped.checked = true;
    window.localStorage.setItem(snarkFilter, btnStopped.id);
    stopped.forEach((element) => {element.classList.add("filtered");});
    var count = filtered.length;
    showBadge();
  }

  function onClick() {
    refreshFilters();
  }

  if (filterbar) {
    btnAll.addEventListener("click", () => {disableBar();onClick();showAll();enableBar();});
    btnActive.addEventListener("click", () => {disableBar();onClick();showActive();enableBar();});
    btnInactive.addEventListener("click", () => {disableBar();onClick();showInactive();enableBar();});
    btnDownloading.addEventListener("click", () => {disableBar();onClick();showDownloading();enableBar();});
    btnSeeding.addEventListener("click", () => {disableBar();onClick();showSeeding();enableBar();});
    btnComplete.addEventListener("click", () => {disableBar();onClick();showComplete();enableBar();});
    btnIncomplete.addEventListener("click", () => {disableBar();onClick();showIncomplete();enableBar();});
    btnStopped.addEventListener("click", () => {disableBar();onClick();showStopped();enableBar();});
    switch (window.localStorage.getItem(snarkFilter)) {
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
}

function checkPagenav() {
  var pagenav = document.getElementById("pagenavtop");
  var path = window.location.pathname;
  var snarkFilter = "snarkFilter";
  if (!path.endsWith("i2psnark/")) {
    snarkFilter = "filter_" + path.replace("/", "");
  }
  var storage = window.localStorage.getItem(snarkFilter);
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
  var filterbar = document.getElementById("torrentDisplay");
  var pagenav = document.getElementById("pagenavtop");
  var query = window.location.search;

  if (query == null) {
  } else {
    if (pagenav && filterbar) {
      checkPagenav();
      refreshTorrents(initFilterBar);
    }
  }
}

function checkIfVisible() {
  var torrentform = document.getElementById("torrentlist");
  if (torrentform !== null) {
    onVisible(torrentform, () => {checkFilterBar();});
  }
}

function refreshAll() {
  var filterbar = document.getElementById("torrentDisplay");
  if (filterbar) {
    refreshFilters();
  }
}

document.addEventListener("DOMContentLoaded", checkIfVisible, true);

export {initFilterBar, checkFilterBar, refreshFilters};