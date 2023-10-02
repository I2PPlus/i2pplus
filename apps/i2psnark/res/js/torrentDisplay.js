/* torrentDisplay.js by dr|z3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import {refreshTorrents} from "./refreshTorrents.js";
import {onVisible} from "./onVisible.js";
import {initLinkToggler, linkToggle, attachMagnetListeners, magnetToast} from "./toggleLinks.js";

function initFilterBar() {
  const active = document.querySelectorAll(".active:not(.peerinfo)");
  const allEven = document.querySelectorAll(".rowEven");
  const allOdd = document.querySelectorAll(".rowOdd");
  const btnActive = document.getElementById("active");
  const btnAll = document.getElementById("all");
  const btnComplete = document.getElementById("complete");
  const btnDownloading = document.getElementById("downloading");
  const btnInactive = document.getElementById("inactive");
  const btnIncomplete = document.getElementById("incomplete");
  const btnSeeding = document.getElementById("seeding");
  const btnStopped = document.getElementById("stopped");
  const complete = document.querySelectorAll(".complete");
  const debuginfo = document.querySelectorAll(".debuginfo");
  var filteredCount = 0;
  const downloading = document.querySelectorAll(".downloading");
  const inactive = document.querySelectorAll(".inactive:not(.peerinfo)");
  const incomplete = document.querySelectorAll(".incomplete");
  const mainsection = document.getElementById("mainsection");
  const pagenav = document.getElementById("pagenavtop");
  const peerinfo = document.querySelectorAll(".peerinfo");
  const screenlog = document.getElementById("screenlog");
  const seeding = document.querySelectorAll(".seeding");
  const stopped = document.querySelectorAll(".stopped");
  const tbody = document.getElementById("snarkTbody");
  const tfoot = document.getElementById("snarkFoot");
  const toggle = document.getElementById("linkswitch");
  var badge = document.getElementById("filtercount");
  const badges = document.querySelectorAll("#filtercount.badge");
  const torrentform = document.getElementById("torrentlist");
  const rules = ".rowOdd,.rowEven,.peerinfo,.debuginfo{visibility:collapse}";

  const filterbar = document.getElementById("torrentDisplay");
  const filtered = document.querySelectorAll(".filtered");
  const query = window.location.search;
  const path = window.location.pathname;
  const snarkFilter = "snarkFilter";
  const storage = window.localStorage.getItem(snarkFilter);

  checkIfActive();

  function checkIfActive() {
    if (!storage && filterbar !== null) {
      btnAll.checked = true;
    }
  }

  function clean() {
    const cssfilter = document.getElementById("cssfilter");

    cssfilter.textContent = "";
    if (badge) {
      badge.textContent = "";
    }

    for (let i = 0; i < allOdd.length; i++) {
      allOdd[i].classList.toggle("filtered", false);
      allEven[i].classList.toggle("filtered", false);
    }

    if (pagenav) {
      const filterActive = storage !== null;
      pagenav.style.display = filterActive ? "none" : "";
    }

    const labels = document.querySelectorAll('label');
    labels.forEach(label => {
      const badges = label.querySelectorAll('.badges');
      badges.forEach(badge => badge.remove());
    });
  }

  function showBadge() {
    const filtered = document.querySelectorAll("#snarkTbody tr.filtered:not(.peerinfo)");
    const activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");

    if (activeFilter && !activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
    }

    let badge = activeFilter.querySelector("#filtercount");

    if (!badge) {
      badge = document.createElement("span");
      badge.id = "filtercount";
      badge.classList.add("badge");
      activeFilter.appendChild(badge);
    }

    const count = filtered.length;

    if (count !== parseInt(badge.textContent)) {
      badge.textContent = count;
    }
  }

  function showAll() {
    clean();
    checkPagenav();
    if (pagenav !== null) {
      pagenav.removeAttribute("hidden");
      pagenav.style.display = "";
    }
    window.localStorage.removeItem("snarkFilter");
    btnAll.checked = true;
    btnAll.classList.add("noPointer");
  }

  function setFilter(id) {
    const filters = {
      active: { button: btnActive, key: "active" },
      all: { button: btnAll, key: "all" },
      complete: { button: btnComplete, key: "complete" },
      downloading: { button: btnDownloading, key: "downloading" },
      inactive: { button: btnInactive, key: "inactive" },
      incomplete: { button: btnIncomplete, key: "incomplete" },
      seeding: { button: btnSeeding, key: "seeding" },
      stopped: { button: btnStopped, key: "stopped" },
    };

    const filter = filters[id];
    if (!filter) return;

    filter.button.checked = true;
    window.localStorage.setItem("snarkFilter", filter.key);
  }

  if (filterbar) {
    const filters = [
      { button: btnAll, filterFunction: showAll, localStorageKey: "all" },
      { button: btnActive, filterFunction: () => setFilter("active"), localStorageKey: "active" },
      { button: btnInactive, filterFunction: () => setFilter("inactive"), localStorageKey: "inactive" },
      { button: btnDownloading, filterFunction: () => setFilter("downloading"), localStorageKey: "downloading" },
      { button: btnSeeding, filterFunction: () => setFilter("seeding"), localStorageKey: "seeding" },
      { button: btnComplete, filterFunction: () => setFilter("complete"), localStorageKey: "complete" },
      { button: btnIncomplete, filterFunction: () => setFilter("incomplete"), localStorageKey: "incomplete" },
      { button: btnStopped, filterFunction: () => setFilter("stopped"), localStorageKey: "stopped" },
    ];

    filters.forEach(filter => {
      filter.button.addEventListener("click", () => {
        clean();
        injectCSS();
        showBadge();
        filter.filterFunction();
        window.localStorage.setItem("snarkFilter", filter.localStorageKey);
      });

      if (storage === filter.localStorageKey) {
        filter.button.click();
      }
    });
  }

  function injectCSS() {
    const stylesheet = document.getElementById("cssfilter");
    const activeFilterInput = document.querySelector("#torrentDisplay input:checked");
    const activeFilterId = activeFilterInput?.id;

    if (activeFilterId !== null && activeFilterId !== "all") {
      const filteredRows = document.querySelectorAll(`#snarkTbody tr.${activeFilterId}`);
      filteredRows.forEach(element => element.classList.add("filtered"));
      const state = `.${activeFilterId} { visibility: visible; }`;
      stylesheet.textContent = rules + state;
    } else {
      stylesheet.textContent = '';
    }
  }
}

function checkFilterBar() {
  const down = document.getElementById("down");
  const filterbar = document.getElementById("torrentDisplay");
  const noload = document.getElementById("noload");
  const query = window.location.search;
  const sortIcon = document.querySelectorAll(".sortIcon");

  if (filterbar) {
    refreshFilters();
  }

  if (noload || down) {
    refreshAll();
  }

  sortIcon.forEach(item => {
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
  const filterbar = document.getElementById("torrentDisplay");
  const pagenav = document.getElementById("pagenavtop");
  const query = window.location.search;
  if (query.includes("ps=9999") && filterbar) {
    if (xhrsnark) {
      xhrsnark.abort();
    }
    refreshTorrents(checkFilterBar);
    checkPagenav();
    initLinkToggler();
    attachMagnetListeners();
    magnetToast();
  }
}

function checkIfVisible() {
  const torrentform = document.getElementById("torrentlist");
  if (torrentform !== null) {
    onVisible(torrentform, () => {
      checkFilterBar();
    });
  }
}

function refreshAll() {
  const filterbar = document.getElementById("torrentDisplay");
  if (filterbar) {
    refreshFilters();
  }
}

document.addEventListener("DOMContentLoaded", checkIfVisible);

export { initFilterBar, checkFilterBar, refreshFilters };