/* torrentDisplay.js by dr|3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

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
    if (!storage && filterbar !== null) {
      btnAll.checked = true;
    }
  }

  function clean() {
    const cssFilter = document.getElementById("cssfilter");
    checkIfActive();
    cssFilter.textContent = "";
    allOdd.forEach(element => element.classList.remove("filtered"));
    allEven.forEach(element => element.classList.remove("filtered"));
    if (storage === null && pagenav) {
      pagenav.style.display = "";
    } else if (pagenav) {
      pagenav.style.display = "none";
    }
    badges.forEach(element => element.remove());
    initLinkToggler();
  }

/**
  function showBadge() {
    const filtered = document.querySelectorAll(".filtered");
    const activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");
    const count = filtered.length;
    activeFilter.classList.add("enabled");
    if (count > 0) {
      const badge = document.createElement("span");
      badge.classList.add("badge");
      badge.textContent = count;
      badge.setAttribute("id", "filtercount");
      activeFilter.appendChild(badge);
    }
  }
**/

  function showBadge() {
    const filtered = document.querySelectorAll(".filtered");
    const activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");
    const count = filtered.length;
    activeFilter.classList.add("enabled");
    if (count > 0) {
      const badge = document.createElement("span");
      badge.classList.add("badge");
      badge.setAttribute("id", "filtercount");
      activeFilter.appendChild(badge);
      getCount(filtered, count).then((result) => {
        badge.textContent = result;
      });
    }
  }

  function getCount(filtered, count) {
    return new Promise((resolve) => {
      if (filtered.length === count) {
        resolve(count);
      } else {
        setTimeout(() => {
          resolve(getCount(filtered, count));
        }, 50);
      }
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

/**
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
    btnAll.classList.add("noPointer");
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
**/

  function showFiltered(filterClass, filterButton) {
    clean();
    const state = `.${filterClass}{visibility:visible}`;
    rules += state;
    injectCSS();
    filterButton.checked = true;
    window.localStorage.setItem(storageFilter, filterButton.id);
    const filteredElements = document.querySelectorAll(`.${filterClass}`);
    filteredElements.forEach(element => element.classList.add("filtered"));
    showBadge();
  }

  function showAll() {
    clean();
    checkPagenav();
    if (pagenav !== null) {
      pagenav.removeAttribute("hidden");
      pagenav.style.display = "";
    }
    window.localStorage.removeItem(storageFilter);
    btnAll.checked = true;
    btnAll.classList.add("noPointer");
  }

  function showActive() {
    showFiltered("active", btnActive);
  }

  function showInactive() {
    showFiltered("inactive", btnInactive);
  }

  function showDownloading() {
    showFiltered("downloading", btnDownloading);
  }

  function showSeeding() {
    showFiltered("seeding", btnSeeding);
  }

  function showComplete() {
    showFiltered("complete", btnComplete);
  }

  function showIncomplete() {
    showFiltered("incomplete", btnIncomplete);
  }

  function showStopped() {
    showFiltered("stopped", btnStopped);
  }

  function onClick() {
    if (xhrsnark.status !== null) {xhrsnark.abort();}
    if (xhrfilter.status !== null) {xhrfilter.abort();}
    refreshFilters();
  }

  if (filterbar) {
    function addFilterEventListeners() {
      function handleFilterClick(filterFunction) {
        disableBar();
        onClick();
        filterFunction();
        enableBar();
      }

      btnAll.addEventListener("click", () => handleFilterClick(showAll));
      btnActive.addEventListener("click", () => handleFilterClick(showActive));
      btnInactive.addEventListener("click", () => handleFilterClick(showInactive));
      btnDownloading.addEventListener("click", () => handleFilterClick(showDownloading));
      btnSeeding.addEventListener("click", () => handleFilterClick(showSeeding));
      btnComplete.addEventListener("click", () => handleFilterClick(showComplete));
      btnIncomplete.addEventListener("click", () => handleFilterClick(showIncomplete));
      btnStopped.addEventListener("click", () => handleFilterClick(showStopped));
    }

    function selectFilter() {
      const filter = window.localStorage.getItem(storageFilter);
      const btnFilters = {
        "all": btnAll,
        "active": btnActive,
        "inactive": btnInactive,
        "downloading": btnDownloading,
        "seeding": btnSeeding,
        "complete": btnComplete,
        "incomplete": btnIncomplete,
        "stopped": btnStopped
      };
      const selectedBtn = btnFilters[filter] || btnAll;

      selectedBtn.checked = true;
      selectedBtn.click();
    }

    addFilterEventListeners();
    selectFilter();

    }
    initLinkToggler();
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
  var screenlog = document.getElementById("screenlog");
  var storageFilter = "filter";
  if (!path.endsWith("i2psnark/")) {storageFilter = "filter_" + path.replace("/", "");}
  var storage = window.localStorage.getItem(storageFilter);
  var url = ".ajax/xhr1.html";
  checkPagenav();
  initLinkToggler();

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
          initFilterBar();
          var filterbarResponse = xhrfilter.responseXML.getElementById("torrentDisplay");
          if (!filterbar && filterbarResponse !== null) {filterbar.outerHTML = filterbarResponse.outerHTML;}
        }
        if (screenlog) {
          var screenlogResponse = xhrfilter.responseXML.getElementById("screenlog");
          if (screenlogResponse !== null && screenlog.innerHTML !== screenlogResponse.innerHTML) {
            screenlog.innerHTML = screenlogResponse.innerHTML;
          }
        }
      } else {
        function noAjax() {
          var failMessage = "<div class=routerdown id=down><span>Router is down</span></div>";
          if (mainsection) {mainsection.innerHTML = failMessage;}
          else {snarkInfo.innerHTML = failMessage;}
        }
        setTimeout(noAjax, 5000);
      }
    }
  };
  xhrfilter.send();
  initLinkToggler();
}

function checkIfVisible() {
  var torrentform = document.getElementById("torrentlist");
  onVisible(torrentform, () => {checkFilterBar();});
}

function refreshAll() {
  var mainsectionResponse = xhrsnark.responseXML.getElementById("mainsection");
  var filterbar = document.getElementById("torrentDisplay");
  var tbody = document.getElementById("snarkTbody");
  var tfoot = document.getElementById("snarkFoot");
  var toggle = document.getElementById("linkswitch");
  if (mainsectionResponse !== null && mainsection !== mainsectionResponse) {
    var tbodyResponse = xhrsnark.responseXML.getElementById("snarkTbody");
    var tfootResponse = xhrsnark.responseXML.getElementById("snarkFoot");
    //mainsection.innerHTML = mainsectionResponse.innerHTML;
    tbody.innerHTML = tbodyResponse.innerHTML;
    tfoot.innerHTML = tfootResponse.innerHTML;
    if (filterbar !== null) {
      filterbarResponse = xhrsnark.responseXML.getElementById("torrentDisplay");
      if (filterbar.innerHTML != filterbarResponse.innerHTML) {
        filterbar.outerHTML = filterbarResponse.outerHTML;
      }
    }
  }
}

document.addEventListener("DOMContentLoaded", checkIfVisible, true);

export {initFilterBar, checkFilterBar, refreshFilters};
