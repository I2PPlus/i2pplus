/* torrentDisplay.js by dr|z3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import { refreshTorrents } from "./refreshTorrents.js";
import { onVisible } from "./onVisible.js";
import { initLinkToggler, linkToggle, attachMagnetListeners, magnetToast } from "./toggleLinks.js";

function initFilterBar() {
  const filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {return;}

  const active = document.querySelectorAll("#snarkTbody .active");
  const allEven = document.querySelectorAll("#snarkTbody .rowEven");
  const allOdd = document.querySelectorAll("#snarkTbody .rowOdd");
  const badges = document.querySelectorAll("#torrentDisplay .badge:not(#filtercount)");
  const btnActive = document.querySelector("#torrentDisplay #active");
  const btnAll = document.querySelector("#torrentDisplay #all");
  const btnComplete = document.querySelector("#torrentDisplay #complete");
  const btnDownloading = document.querySelector("#torrentDisplay #downloading");
  const btnInactive = document.querySelector("#torrentDisplay #inactive");
  const btnIncomplete = document.querySelector("#torrentDisplay #incomplete");
  const btnSeeding = document.querySelector("#torrentDisplay #seeding");
  const btnStopped = document.querySelector("#torrentDisplay #stopped");
  const complete = document.querySelectorAll("#snarkTbody .complete");
  const debuginfo = document.querySelectorAll("#snarkTbody .debuginfo");
  const downloading = document.querySelectorAll("#snarkTbody .downloading");
  const filtered = document.querySelectorAll(".filtered");
  const inactive = document.querySelectorAll("#snarkTbody .inactive:not(.peerinfo)");
  const incomplete = document.querySelectorAll("#snarkTbody .incomplete");
  const mainsection = document.getElementById("mainsection");
  const pagenav = document.getElementById("pagenavtop");
  const path = window.location.pathname;
  const peerinfo = document.querySelectorAll("#snarkTbody .peerinfo");
  const query = window.location.search;
  const rules = ".rowOdd:not(.filtered),.rowEven:not(.filtered),.peerinfo,.debuginfo{display:none}";
  const screenlog = document.getElementById("screenlog");
  const seeding = document.querySelectorAll("#snarkTbody .seeding");
  const snarkFilter = "snarkFilter";
  const stopped = document.querySelectorAll("#snarkTbody .stopped");
  const storage = window.localStorage.getItem(snarkFilter);
  const tbody = document.getElementById("snarkTbody");
  const tfoot = document.getElementById("snarkFoot");
  const toggle = document.getElementById("linkswitch");
  const torrentform = document.getElementById("torrentlist");
  let badge;
  let filteredCount;

  checkIfActive();

  function checkIfActive() {
    if (!storage && filterbar !== null) {
      btnAll.checked = true;
    }
  }

  function clean() {
    const cssfilter = document.getElementById("cssfilter");

    cssfilter.textContent = "";

    filtered.forEach((row) => {
      row.classList.toggle("filtered", false);
    });

    const existingBadges = document.querySelectorAll("#torrentDisplay #filtercount.badge:nth-child(n+2)");
    if (existingBadges.length > 0) {
      const badgesToRemove = [];
      for (const existingBadge of existingBadges) {
        badgesToRemove.push(existingBadge);
      }

      badgesToRemove.forEach((badge) => {
        badge.textContent = "";
        badge.remove();
      });
      console.log(`Removed ${badgesToRemove.length} redundant badges`);
    }

    if (pagenav) {
      const filterActive = storage !== null;
      pagenav.style.display = filterActive ? "none" : "";
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
    const filterButtons = [
      { button: btnAll, filterFunction: showAll, localStorageKey: ""},
      { button: btnActive, filterFunction: () => {setFilter("active"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "active"},
      { button: btnInactive, filterFunction: () => {setFilter("inactive"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "inactive"},
      { button: btnDownloading, filterFunction: () => {setFilter("downloading"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "downloading"},
      { button: btnSeeding, filterFunction: () => {setFilter("seeding"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "seeding"},
      { button: btnComplete, filterFunction: () => {setFilter("complete"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "complete"},
      { button: btnIncomplete, filterFunction: () => {setFilter("incomplete"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "incomplete"},
      { button: btnStopped, filterFunction: () => {setFilter("stopped"), countFiltered(), clean(), injectCSS(), showBadge()}, localStorageKey: "stopped"},
    ];

    filterButtons.forEach(filter => {
      filter.button.addEventListener("click", () => {
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
      filteredRows.forEach(element => element.classList.toggle("filtered", true));
      const state = `.${activeFilterId} {display:tablerow;}`;
      stylesheet.textContent = rules + state;
    } else {
      stylesheet.textContent = "";
    }
  }
}

let shouldCheckFilters = true;
let cachedFilterCount = null;

function countFiltered() {
  if (!shouldCheckFilters) {
    return cachedFilterCount;
  }

  const rows = document.querySelectorAll("#snarkTbody tr.filtered:not(.peerinfo)");
  let filteredCount = 0;

  for (const row of rows) {
    if (!row.classList.contains("hidden")) {
      filteredCount++;
    }
  }

  cachedFilterCount = filteredCount;
  shouldCheckFilters = false;
  return filteredCount;
}

function showBadge() {
  shouldCheckFilters = true;
  const activeFilter = document.querySelector("#torrentDisplay input:checked + .filterbutton");

  if (activeFilter && !activeFilter.classList.contains("enabled")) {
    activeFilter.classList.add("enabled");
  }

  if (activeFilter) {
    const newBadge = document.createElement("span");
    newBadge.id = "filtercount";
    newBadge.classList.add("badge");

    requestAnimationFrame(() => {
      activeFilter.appendChild(newBadge);
      newBadge.textContent = countFiltered();
    });
    console.log(`Filter count is ${countFiltered()}`);
  }
}

function checkFilterBar() {
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sortIcon");

  if (noload !== null) {
    refreshFilters();
  }

  sortIcon.forEach((item) => {
    item.addEventListener("click", () => {
      setQuery();
    });
  });

  function setQuery() {
    if (window.location.search) {
      window.localStorage.setItem("queryString", window.location.search);
      window.location.search = window.location.search;
    }
  }
}

function checkPagenav() {
  const pagenav = document.getElementById("pagenavtop");
  const storage = window.localStorage.getItem("snarkFilter");

  if (pagenav) {
    pagenav.style.display = storage ? "none" : "";
    pagenav.hidden = !!storage;
    pagenav.removeAttribute("hidden");
  }
}

function refreshFilters() {
  const pagenav = document.getElementById("pagenavtop");
  const query = window.location.search;
  if (query.includes("ps=9999")) {
    countFiltered();
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
document.addEventListener("DOMContentLoaded", checkIfVisible);

export { initFilterBar, checkFilterBar, refreshFilters };