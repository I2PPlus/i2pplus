/* I2PSnark torrentDisplay.js by dr|3d */
/* Setup torrent display buttons so we can show/hide snarks based on status */
/* License: AGPL3 or later */

import {initLinkToggler} from "./toggleLinks.js";
import {initSnarkRefresh, refreshTorrents,  debouncedRefreshTorrents} from "./refreshTorrents.js";

function initFilterBar() {
  let filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {return;}
  let buttons = filterbar.querySelectorAll("input[type='radio']");
  let badges = document.querySelectorAll("#filtercount.badge");
  let snarkFilter = "snarkfilter";
  let stylesheet = document.getElementById("cssfilter");
  const cssFilter = ".rowOdd,.rowEven,.peerinfo,.debuginfo{visibility: collapse;}";
  let linkToggled = false;
  checkIfActive();
  checkFilterBar();

  function clean() {
    stylesheet.textContent = "";
    document.querySelectorAll(".filtered").forEach(element => element.classList.remove("filtered"));
    let pagenav = document.getElementById("pagenavtop");
    if (window.localStorage.getItem(snarkFilter) === null && pagenav) {
      pagenav.style.display = "";
    }
    badges.forEach(badge => badge.remove());
  }

  function showFiltered(filterClass, filterButton) {
    clean();
    const state = `.${filterClass}{visibility:visible}`;
    stylesheet.textContent = cssFilter + state;
    filterButton.checked = true;
    window.localStorage.setItem(snarkFilter, filterButton.id);
    document.querySelectorAll(`.${filterClass}`).forEach(element => element.classList.add("filtered"));
    showBadge();
    checkPagenav();
  }

  function showAll() {
    clean();
    checkPagenav();
    window.localStorage.removeItem(snarkFilter);
    buttons[0].checked = true;
    buttons[0].classList.add("noPointer");
  }

  function showBadge() {
    const filtered = document.querySelectorAll(".filtered");
    const activeFilter = filterbar.querySelector("input:checked + .filterbutton");
    const count = filtered.length;
    activeFilter.classList.add("enabled");
    if (count > 0) {
      let badge = document.createElement("span");
      badge.classList.add("badge");
      badge.setAttribute("id", "filtercount");
      activeFilter.appendChild(badge);
      getCount(filtered, count).then(result => {
        badge.textContent = result;
      });
    }
  }

  function getCount(filtered, count) {
    return new Promise(resolve => {
      const interval = setInterval(() => {
        if (filtered.length === count) {
          clearInterval(interval);
          resolve(count);
        }
      }, 50);
    });
  }

  function addFilterEventListeners() {
    buttons.forEach((button, index) => {
      button.addEventListener("click", () => {
        switch (index) {
          case 0: showAll(); break;
          case 1: showFiltered("active", button); break;
          case 2: showFiltered("inactive", button); break;
          case 3: showFiltered("downloading", button); break;
          case 4: showFiltered("seeding", button); break;
          case 5: showFiltered("complete", button); break;
          case 6: showFiltered("incomplete", button); break;
          case 7: showFiltered("stopped", button); break;
        }
      });
    });
  }

  function disableBar() {
    filterbar.classList.add("noPointer");
  }

  function enableBar() {
    filterbar.classList.remove("noPointer");
  }
  addFilterEventListeners();
}

function checkIfActive() {
  let snarkFilter = "snarkfilter";
  let filterbar = document.getElementById("torrentDisplay");
  const buttons = filterbar.querySelectorAll("input[type='radio']");
  let isActive = Boolean(false);
  if (!window.localStorage.getItem(snarkFilter) && filterbar) {
    buttons[0].checked = true;
    isActive = true;
  }
  return isActive;
}

/**
function selectFilter() {
  const filterbar = document.getElementById("torrentDisplay");
  const buttons = filterbar.querySelectorAll("input[type='radio']");
  const storage = window.localStorage.getItem("snarkfilter");
  const selectedBtn = buttons[storage && parseInt(storage.replace("btn", "")) || 0];
  selectedBtn.checked = true;
  selectedBtn.click();
}
**/

function selectFilter() {
  const filterbar = document.getElementById("torrentDisplay");
  const buttons = filterbar.querySelectorAll("input[type='radio']");
  console.log("Number of radio buttons:", buttons.length);
  const storage = window.localStorage.getItem("snarkfilter");
  console.log("Storage value:", storage);
  const selectedBtn = buttons[storage && parseInt(storage.replace("btn", "")) || 0];
  console.log("Selected button index:", storage && parseInt(storage.replace("btn", "")) || 0);
  selectedBtn.checked = true;
  console.log("Is selected button checked?:", selectedBtn.checked);
  selectedBtn.click();
}

function refreshFilters() {
  const storage = window.localStorage.getItem("snarkfilter");
  const filterbar = document.getElementById("torrentDisplay");
  const pagenav = document.getElementById("pagenavtop");
  //const url = getURL();

  //debouncedRefreshTorrents();

//  refreshTorrents();

  checkPagenav();
}

function checkFilterBar() {
  const storage = window.localStorage.getItem("snarkfilter");
  const down = document.getElementById("down");
  const filterbar = document.getElementById("torrentDisplay");
  const noload = document.getElementById("noload");
  const query = window.location.search;
  const sortIcon = document.querySelectorAll(".sortIcon");
  if (filterbar) {
    refreshFilters();
    document.querySelectorAll(".sortIcon").forEach(sort => {
      sort.addEventListener("click", refreshFilters);
    });
  } else if (noload || down) {
    refreshAll();
  }
}

function refreshAll() {
  //debouncedRefreshTorrents();

//  refreshTorrents();

  initLinkToggler();
  checkFilterBar();
}

function checkPagenav() {
  var pagenav = document.getElementById("pagenavtop");
  var path = window.location.pathname;
  var storageFilter = "snarkfilter";
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

document.addEventListener("DOMContentLoaded", () => {
  initLinkToggler();
  initFilterBar();
}, true);

export {initFilterBar, checkFilterBar, refreshFilters, selectFilter, checkPagenav};